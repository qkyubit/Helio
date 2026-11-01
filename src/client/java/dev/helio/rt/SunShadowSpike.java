package dev.helio.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.vk.GpuTimers;
import dev.helio.mixin.VulkanCommandEncoderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * M0 spike: ray-traced sun shadows over the vanilla frame.
 * <ul>
 *   <li>CPU-meshes full opaque blocks around the player into one BLAS + TLAS (synchronous rebuild
 *       when the player moves {@value #REBUILD_DISTANCE} blocks; hitches, fine for a spike).</li>
 *   <li>After vanilla's level frame graph, records a compute dispatch into Mojang's own command buffer
 *       that traces one ray per pixel to the sun and darkens the colour target.</li>
 * </ul>
 * Mojang keeps every image in VK_IMAGE_LAYOUT_GENERAL and separates operations with full memory
 * barriers, so no layout transitions are needed; we add the same barrier after our dispatch.
 */
public final class SunShadowSpike {
	private static final int RADIUS = 64, HALF_HEIGHT = 48, REBUILD_DISTANCE = 24;
	private static final float SHADOW_STRENGTH = 0.55f;

	// Corners of each face as (dx,dy,dz) of the neighbour, then 4 corners.
	private static final int[][] FACES = {
		{1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
		{-1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0},
		{0, 1, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
		{0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
		{0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1},
		{0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0},
	};

	private static VulkanDevice device;
	private static long vma, sampler, setLayout, pipelineLayout, pipeline, cmdPool;
	private static Buf blasBuf, tlasBuf;
	private static long blas, tlas;
	private static BlockPos sceneOrigin;
	private static boolean failed;

	public static final Matrix4f levelProjection = new Matrix4f();
	/** Last shadow scene rebuild, for the overlay: CPU mesh + synchronous AS build, and its size. */
	public static long lastRebuildMs, sceneTriangles;

	private record Buf(long buffer, long allocation, long address, long mapped) {
		void free() {
			vmaDestroyBuffer(vma, buffer, allocation);
		}
	}

	private SunShadowSpike() {}

	/** Called right after vanilla's level frame graph executed. */
	public static void afterLevel(Vec3 cameraPos, Matrix4f viewRotation, float sunAngle) {
		if (failed || !Helio.rayTracing || !HelioConfig.current.rayTracing || Helio.device == null) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		try {
			if (device == null) init(Helio.device);
			BlockPos cam = BlockPos.containing(cameraPos);
			if (sceneOrigin == null || sceneOrigin.distManhattan(cam) > REBUILD_DISTANCE) buildScene(level, cam);
			if (tlas == 0) return;

			Vector3f sun = new Matrix4f().rotateY((float) (-Math.PI / 2)).rotateX(sunAngle).transformDirection(new Vector3f(0, 1, 0));
			if (sun.y < 0.02f) return; // ponytail: night = no shadows; moon light is M3
			dispatch(cameraPos, viewRotation, sun);
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Sun shadow spike failed; disabled for this session", t);
		}
	}

	private static void init(VulkanDevice dev) throws IOException {
		device = dev;
		VkDevice vk = dev.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			// Own allocator: Mojang's VMA lacks the buffer-device-address flag acceleration structures need.
			PointerBuffer pVma = s.mallocPointer(1);
			check(vmaCreateAllocator(VmaAllocatorCreateInfo.calloc(s)
				.flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
				.physicalDevice(vk.getPhysicalDevice()).device(vk).instance(vk.getPhysicalDevice().getInstance())
				.vulkanApiVersion(VK_API_VERSION_1_2)
				.pVulkanFunctions(VmaVulkanFunctions.calloc(s).set(vk.getPhysicalDevice().getInstance(), vk)), pVma), "vmaCreateAllocator");
			vma = pVma.get(0);

			LongBuffer h = s.mallocLong(1);
			check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).maxLod(0), null, h), "sampler");
			sampler = h.get(0);

			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(3, s);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			// Push descriptors: no pool, and safe to rewrite every frame while earlier frames are in flight.
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "set layout");
			setLayout = h.get(0);

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(96);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default()
				.pSetLayouts(s.longs(setLayout)).pPushConstantRanges(pc), null, h), "pipeline layout");
			pipelineLayout = h.get(0);

			ByteBuffer spv = compile("sun_shadow.comp");
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, h), "shader module");
			long module = h.get(0);
			MemoryUtil.memFree(spv);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
			ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
			check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "compute pipeline");
			pipeline = h.get(0);
			vkDestroyShaderModule(vk, module, null);

			check(vkCreateCommandPool(vk, VkCommandPoolCreateInfo.calloc(s).sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(dev.graphicsQueue().queueFamilyIndex()), null, h), "command pool");
			cmdPool = h.get(0);

		}
		Helio.LOG.info("Sun shadow spike initialised");
	}

	/** GLSL from the jar to SPIR-V 1.4 via the shaderc Minecraft already ships. */
	public static ByteBuffer compile(String name) throws IOException {
		String src;
		try (InputStream in = SunShadowSpike.class.getResourceAsStream("/assets/helio/shaders/" + name)) {
			if (in == null) throw new IOException("missing shader " + name);
			src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		long compiler = shaderc_compiler_initialize(), opts = shaderc_compile_options_initialize();
		try {
			shaderc_compile_options_set_target_env(opts, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_target_spirv(opts, shaderc_spirv_version_1_4);
			shaderc_compile_options_set_optimization_level(opts, shaderc_optimization_level_performance);
			long result = shaderc_compile_into_spv(compiler, src, shaderc_compute_shader, name, "main", opts);
			try {
				if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
					throw new IllegalStateException(name + ": " + shaderc_result_get_error_message(result));
				ByteBuffer bytes = shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				shaderc_result_release(result);
			}
		} finally {
			shaderc_compile_options_release(opts);
			shaderc_compiler_release(compiler);
		}
	}

	private static void buildScene(ClientLevel level, BlockPos origin) {
		long t0 = System.nanoTime();
		float[] verts = new float[1 << 16];
		int[] idx = new int[1 << 16];
		int nv = 0, ni = 0;
		int minY = Math.max(level.getMinY(), origin.getY() - HALF_HEIGHT), maxY = Math.min(level.getMaxY(), origin.getY() + HALF_HEIGHT);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
		for (int x = -RADIUS; x < RADIUS; x++) for (int z = -RADIUS; z < RADIUS; z++) for (int y = minY; y < maxY; y++) {
			p.set(origin.getX() + x, y, origin.getZ() + z);
			if (!level.getBlockState(p).canOcclude()) continue;
			for (int[] f : FACES) {
				if (level.getBlockState(n.setWithOffset(p, f[0], f[1], f[2])).canOcclude()) continue;
				if (nv + 12 > verts.length) verts = Arrays.copyOf(verts, verts.length * 2);
				if (ni + 6 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
				int base = nv / 3;
				for (int c = 0; c < 4; c++) {
					verts[nv++] = x + f[3 + c * 3];
					verts[nv++] = y - origin.getY() + f[4 + c * 3];
					verts[nv++] = z + f[5 + c * 3];
				}
				idx[ni++] = base; idx[ni++] = base + 1; idx[ni++] = base + 2;
				idx[ni++] = base; idx[ni++] = base + 2; idx[ni++] = base + 3;
			}
		}
		long t1 = System.nanoTime();
		destroyScene();
		sceneOrigin = origin;
		if (ni == 0) return;
		buildAccelerationStructures(verts, nv, idx, ni);
		lastRebuildMs = (System.nanoTime() - t0) / 1_000_000;
		sceneTriangles = ni / 3;
		Helio.LOG.debug("Shadow scene: {} triangles around {} (mesh {} ms, AS build {} ms)",
			ni / 3, origin.toShortString(), (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
	}

	private static void buildAccelerationStructures(float[] verts, int nv, int[] idx, int ni) {
		VkDevice vk = device.vkDevice();
		int triangles = ni / 3;
		Buf vb = buffer((long) nv * 4, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
		Buf ib = buffer((long) ni * 4, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
		MemoryUtil.memFloatBuffer(vb.mapped, nv).put(verts, 0, nv);
		MemoryUtil.memIntBuffer(ib.mapped, ni).put(idx, 0, ni);
		Buf instances = buffer(VkAccelerationStructureInstanceKHR.SIZEOF, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
		Buf blasScratch, tlasScratch;

		try (MemoryStack s = MemoryStack.stackPush()) {
			// BLAS: all terrain triangles, opaque.
			VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, s).sType$Default()
				.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
			VkAccelerationStructureGeometryTrianglesDataKHR tri = geom.geometry().triangles().sType$Default()
				.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(12).maxVertex(nv / 3 - 1).indexType(VK_INDEX_TYPE_UINT32);
			tri.vertexData().deviceAddress(vb.address);
			tri.indexData().deviceAddress(ib.address);
			VkAccelerationStructureBuildGeometryInfoKHR.Buffer blasInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, s).sType$Default()
				.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
				.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geom).geometryCount(1);
			VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
			vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, blasInfo.get(0), s.ints(triangles), sizes);
			blasBuf = buffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			blas = createAs(s, blasBuf, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
			blasScratch = buffer(sizes.buildScratchSize(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			blasInfo.dstAccelerationStructure(blas).scratchData().deviceAddress(blasScratch.address);

			// TLAS: one identity instance of the BLAS; back faces count (camera can be inside caves).
			VkAccelerationStructureInstanceKHR inst = VkAccelerationStructureInstanceKHR.create(instances.mapped);
			inst.transform().matrix(s.floats(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0));
			inst.instanceCustomIndex(0).mask(0xFF).instanceShaderBindingTableRecordOffset(0)
				.flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
				.accelerationStructureReference(vkGetAccelerationStructureDeviceAddressKHR(vk,
					VkAccelerationStructureDeviceAddressInfoKHR.calloc(s).sType$Default().accelerationStructure(blas)));
			VkAccelerationStructureGeometryKHR.Buffer instGeom = VkAccelerationStructureGeometryKHR.calloc(1, s).sType$Default()
				.geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
			instGeom.geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address);
			VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, s).sType$Default()
				.type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
				.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(instGeom).geometryCount(1);
			VkAccelerationStructureBuildSizesInfoKHR tlasSizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
			vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, tlasInfo.get(0), s.ints(1), tlasSizes);
			tlasBuf = buffer(tlasSizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			tlas = createAs(s, tlasBuf, tlasSizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
			tlasScratch = buffer(tlasSizes.buildScratchSize(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			tlasInfo.dstAccelerationStructure(tlas).scratchData().deviceAddress(tlasScratch.address);

			// Record + submit + wait. Runs on the render thread between Mojang's submits, so no queue race.
			PointerBuffer pCmd = s.mallocPointer(1);
			check(vkAllocateCommandBuffers(vk, VkCommandBufferAllocateInfo.calloc(s).sType$Default()
				.commandPool(cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pCmd), "alloc cmd");
			VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vk);
			check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
			vkCmdBuildAccelerationStructuresKHR(cmd, blasInfo,
				s.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, s).primitiveCount(triangles)));
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0,
				VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
					.dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR), null, null);
			vkCmdBuildAccelerationStructuresKHR(cmd, tlasInfo,
				s.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, s).primitiveCount(1)));
			check(vkEndCommandBuffer(cmd), "end");
			check(vkQueueSubmit(device.graphicsQueue().vkQueue(), VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(pCmd), VK_NULL_HANDLE), "submit");
			check(vkQueueWaitIdle(device.graphicsQueue().vkQueue()), "wait");
			vkFreeCommandBuffers(vk, cmdPool, cmd);
		}
		vb.free();
		ib.free();
		instances.free();
		blasScratch.free();
		tlasScratch.free();
	}

	private static long createAs(MemoryStack s, Buf storage, long size, int type) {
		LongBuffer h = s.mallocLong(1);
		check(vkCreateAccelerationStructureKHR(device.vkDevice(), VkAccelerationStructureCreateInfoKHR.calloc(s).sType$Default()
			.buffer(storage.buffer).size(size).type(type), null, h), "create AS");
		return h.get(0);
	}

	private static void destroyScene() {
		if (tlas == 0 && blas == 0) return;
		vkQueueWaitIdle(device.graphicsQueue().vkQueue()); // ponytail: stall on rebuild; M2 defers frees per frame
		vkDestroyAccelerationStructureKHR(device.vkDevice(), tlas, null);
		vkDestroyAccelerationStructureKHR(device.vkDevice(), blas, null);
		tlasBuf.free();
		blasBuf.free();
		tlas = blas = 0;
	}

	private static void dispatch(Vec3 cameraPos, Matrix4f viewRotation, Vector3f sun) {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		VulkanGpuTextureView color = (VulkanGpuTextureView) main.getColorTextureView();
		VulkanGpuTextureView depth = (VulkanGpuTextureView) main.getDepthTextureView();
		VulkanCommandEncoder encoder = device.createCommandEncoder();
		VkCommandBuffer cmd = ((VulkanCommandEncoderAccessor) encoder).helio$commandBuffer();

		try (MemoryStack s = MemoryStack.stackPush()) {
			GpuTimers.begin(cmd, GpuTimers.Pass.SUN_SHADOWS);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(3, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
				.pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(s).sType$Default().pAccelerationStructures(s.longs(tlas)));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(2).sType$Default().dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(color.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, w);

			ByteBuffer pc = s.malloc(96);
			new Matrix4f(levelProjection).mul(viewRotation).invert().get(0, pc);
			pc.putFloat(64, (float) (cameraPos.x - sceneOrigin.getX())).putFloat(68, (float) (cameraPos.y - sceneOrigin.getY()))
				.putFloat(72, (float) (cameraPos.z - sceneOrigin.getZ())).putFloat(76, 0)
				.putFloat(80, sun.x).putFloat(84, sun.y).putFloat(88, sun.z).putFloat(92, SHADOW_STRENGTH);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

			vkCmdDispatch(cmd, (main.width + 7) / 8, (main.height + 7) / 8, 1);
			GpuTimers.end(cmd, GpuTimers.Pass.SUN_SHADOWS);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
		}
	}

	private static Buf buffer(long size, int usage, boolean hostVisible) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer pBuf = s.mallocLong(1);
			PointerBuffer pAlloc = s.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(s);
			check(vmaCreateBuffer(vma,
				VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT),
				VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO)
					.flags(hostVisible ? VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0),
				pBuf, pAlloc, info), "vmaCreateBuffer");
			long address = vkGetBufferDeviceAddress(device.vkDevice(), VkBufferDeviceAddressInfo.calloc(s).sType$Default().buffer(pBuf.get(0)));
			// AS storage needs 256-byte and scratch 128-byte alignment (NVIDIA); buffer alignment already covers it.
			if ((address & 255) != 0) throw new IllegalStateException("buffer address not 256-aligned: 0x" + Long.toHexString(address));
			return new Buf(pBuf.get(0), pAlloc.get(0), address, info.pMappedData());
		}
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}

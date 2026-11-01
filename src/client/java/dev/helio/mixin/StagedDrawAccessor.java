package dev.helio.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
	@Accessor("format")
	VertexFormat helio$format();

	@Accessor("primitiveTopology")
	PrimitiveTopology helio$primitiveTopology();

	@Accessor("vertexBufferSlices")
	List<ByteBufferBuilder.Result> helio$vertexBufferSlices();
}

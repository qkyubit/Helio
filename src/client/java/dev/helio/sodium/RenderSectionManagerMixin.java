package dev.helio.sodium;

import dev.helio.rt.Scene;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
	@Shadow private RenderSection getRenderSection(int x, int y, int z) { throw new AssertionError(); }
	@Shadow private void submitSectionTask(ChunkJobCollector collector, RenderSection section, UploadResourceBudget budget) { throw new AssertionError(); }

	/** Sections looked at per frame, and builds asked for per frame at most. */
	@Unique private static final int SCAN = 2048, BUILDS = 16;
	@Unique private int helio$cursor;

	// Sodium dropped a section (unloaded): so does the scene, as RenderSectionMixin does for vanilla.
	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void helio$remove(int x, int y, int z, CallbackInfo ci) {
		Scene.remove(SectionPos.asLong(x, y, z));
	}

	// Sodium builds the sections it finds visible; shadows and reflections need those out of view too, within the
	// RT distance (as Scene.withHiddenSections asks vanilla). After Sodium's own submissions, a slice of that area is
	// looked at each frame and sections waiting for a build are submitted the way Sodium submits its deferred ones.
	@Inject(method = "submitSectionTasks", at = @At("TAIL"))
	private void helio$hiddenSections(ChunkJobCollector important, ChunkJobCollector semiImportant, ChunkJobCollector deferred,
		UploadResourceBudget budget, Viewport viewport, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		int r = Scene.sodiumBuildRadius();
		if (level == null || r <= 0) return;
		int w = 2 * r + 1, minY = level.getMinSectionY(), height = level.getSectionsCount(), total = w * w * height;
		int cx = Scene.sodiumCameraX(), cz = Scene.sodiumCameraZ(), builds = 0;
		for (int n = 0; n < Math.min(SCAN, total) && builds < BUILDS && budget.isAvailable(); n++) {
			int i = helio$cursor = (helio$cursor + 1) % total;
			int y = i % height, xz = i / height;
			RenderSection section = getRenderSection(cx - r + xz % w, minY + y, cz - r + xz / w);
			if (section == null || section.isDisposed() || section.getPendingUpdate() == 0) continue;
			submitSectionTask(deferred, section, budget);
			builds++;
		}
	}
}

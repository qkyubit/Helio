package dev.helio.config;

import dev.helio.Helio;
import dev.helio.SelfCheck;
import dev.helio.config.HelioConfig.DebugView;
import dev.helio.config.HelioConfig.DlssQuality;
import dev.helio.config.HelioConfig.Lighting;
import dev.helio.config.HelioConfig.Preset;
import dev.helio.config.HelioConfig.ReflexMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/** Helio settings. Opened from Mod Menu or the "Helio settings" key. */
public class HelioScreen extends OptionsSubScreen {
	public HelioScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("helio.settings"));
	}

	@Override
	protected void addOptions() {
		HelioConfig c = HelioConfig.current;
		List<Preset> presets = Arrays.stream(Preset.values()).filter(p -> p != Preset.CUSTOM || c.preset == Preset.CUSTOM).toList();
		list.addBig(CycleButton.builder((Preset p) -> Component.translatable("helio.preset." + p.name().toLowerCase()), c.preset)
			.withValues(presets)
			.withTooltip(p -> Tooltip.create(Component.translatable("helio.preset." + p.name().toLowerCase() + ".tooltip")))
			.create(Component.translatable("helio.preset"), (b, p) -> {
				c.applyPreset(p);
				rebuildWidgets();
			}));

		list.addSmall(
			unsupportedIf(!Helio.rayTracing, "no Vulkan ray tracing", CycleButton.onOffBuilder(c.rayTracing)
				.create(Component.translatable("helio.ray_tracing"), (b, v) -> changed(() -> c.rayTracing = v))),
			unsupportedIf(!Helio.rayTracing, "no Vulkan ray tracing", CycleButton.builder((Integer n) -> Component.translatable("helio.rt_distance.value", n), c.rayTracingDistance)
				.withValues(4, 6, 8, 12, 16, 24, 32)
				.withTooltip(n -> Tooltip.create(Component.translatable("helio.rt_distance.tooltip")))
				.create(Component.translatable("helio.rt_distance"), (b, v) -> c.rayTracingDistance = v)));
		list.addSmall(
			unsupportedIf(!Helio.rayTracing, "no Vulkan ray tracing", CycleButton.builder((Lighting l) -> Component.translatable("helio.lighting." + l.name().toLowerCase()), c.lighting)
				.withValues(Lighting.values())
				.withTooltip(l -> Tooltip.create(Component.translatable("helio.lighting." + l.name().toLowerCase() + ".tooltip")))
				.create(Component.translatable("helio.lighting"), (b, v) -> changed(() -> c.lighting = v))),
			unsupportedIf(!Helio.dlss, "DLSS " + Helio.dlssStatus, CycleButton.builder((DlssQuality q) -> Component.translatable("helio.dlss_quality." + q.name().toLowerCase()), c.dlssQuality)
				.withValues(DlssQuality.values())
				.withTooltip(q -> Tooltip.create(Component.translatable("helio.dlss_quality.tooltip")))
				.create(Component.translatable("helio.dlss_quality"), (b, v) -> changed(() -> c.dlssQuality = v))));
		list.addSmall(
			unsupportedIf(!Helio.dlss || !Helio.ngxCaps.frameGeneration(), "DLSS " + Helio.dlssStatus,
				// ponytail: 2x only until M4 adds multi frame generation (up to 1 + ngxCaps.mfgMax()).
				CycleButton.builder((Integer n) -> n == 1 ? Component.translatable("options.off") : Component.literal(n + "x"), c.frameGeneration)
					.withValues(1, 2)
					.withTooltip(n -> Tooltip.create(Component.translatable("helio.frame_generation.tooltip")))
					.create(Component.translatable("helio.frame_generation"), (b, v) -> changed(() -> c.frameGeneration = v))),
			unsupportedIf(!Helio.reflex, "no VK_NV_low_latency2", CycleButton.builder((ReflexMode m) -> Component.translatable("helio.reflex." + m.name().toLowerCase()), c.reflex)
				.withValues(ReflexMode.values())
				.withTooltip(m -> Tooltip.create(Component.translatable("helio.reflex.tooltip")))
				.create(Component.translatable("helio.reflex"), (b, v) -> changed(() -> c.reflex = v))));
		list.addSmall(
			CycleButton.onOffBuilder(c.perfOverlay)
				.create(Component.translatable("helio.perf_overlay"), (b, v) -> c.perfOverlay = v),
			unsupportedIf(!Helio.rayTracing, "no Vulkan ray tracing", CycleButton.builder((DebugView v) -> Component.translatable("helio.debug_view." + v.name().toLowerCase()), c.debugView)
				.withValues(DebugView.values())
				.withTooltip(v -> Tooltip.create(Component.translatable("helio.debug_view.tooltip")))
				.create(Component.translatable("helio.debug_view"), (b, v) -> c.debugView = v)));
		list.addSmall(
			unsupportedIf(!Helio.rayTracing, "no Vulkan ray tracing", CycleButton.onOffBuilder(c.accumulate)
				.withTooltip(v -> Tooltip.create(Component.translatable("helio.accumulate.tooltip")))
				.create(Component.translatable("helio.accumulate"), (b, v) -> c.accumulate = v)),
			unsupportedIf(!Helio.rayTracing || Helio.ngxCaps == null || !Helio.ngxCaps.rayReconstruction(), "no DLSS Ray Reconstruction",
				CycleButton.onOffBuilder(c.rayReconstruction)
					.withTooltip(v -> Tooltip.create(Component.translatable("helio.ray_reconstruction.tooltip")))
					.create(Component.translatable("helio.ray_reconstruction"), (b, v) -> c.rayReconstruction = v)));
	}

	/** Any manual change means the settings no longer match a preset. */
	private void changed(Runnable set) {
		set.run();
		if (HelioConfig.current.preset != Preset.CUSTOM) {
			HelioConfig.current.preset = Preset.CUSTOM;
			rebuildWidgets();
		}
	}

	private static AbstractWidget unsupportedIf(boolean unsupported, String reason, AbstractWidget w) {
		if (unsupported) {
			w.active = false;
			w.setTooltip(Tooltip.create(Component.translatable("helio.unsupported", reason)));
		}
		return w;
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("helio.copy_report"), b -> {
			minecraft.keyboardHandler.setClipboard(SelfCheck.debugReport());
			b.setMessage(Component.translatable("helio.copied"));
		}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
	}

	@Override
	public void removed() {
		HelioConfig.save();
	}
}

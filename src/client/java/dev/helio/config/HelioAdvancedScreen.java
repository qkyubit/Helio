package dev.helio.config;

import dev.helio.Helio;
import dev.helio.config.HelioConfig.Preset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

/**
 * Advanced settings (M9's tuning screen, started early in M7 step 5 with the ray budget): each value with a
 * reset to its default beside it, and a reset for all. The defaults are {@link HelioConfig#DEFAULTS}, Balanced's.
 * Changing anything here makes the preset Custom, as in the main screen.
 */
public class HelioAdvancedScreen extends OptionsSubScreen {
	public HelioAdvancedScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("helio.advanced.title"));
	}

	@Override
	protected void addOptions() {
		row("helio.fog_steps", c -> c.fogSteps, (c, v) -> c.fogSteps = v, 0, 8, "");
		row("helio.cloud_steps", c -> c.cloudSteps, (c, v) -> c.cloudSteps = v, 8, 64, "");
		row("helio.light_candidates", c -> c.lightCandidates, (c, v) -> c.lightCandidates = v, 1, 16, "");
		row("helio.specular_cutoff", c -> c.specularCutoff, (c, v) -> c.specularCutoff = v, 0, 100, "%");
		row("helio.cache_feed", c -> c.cacheFeed, (c, v) -> c.cacheFeed = v, 4, 64, "");
	}

	private interface Setter {
		void set(HelioConfig c, int value);
	}

	/** The value's slider, and a button that puts it back to its default. */
	private void row(String key, ToIntFunction<HelioConfig> get, Setter set, int min, int max, String suffix) {
		HelioConfig c = HelioConfig.current;
		int initial = get.applyAsInt(HelioConfig.DEFAULTS);
		IntConsumer change = v -> {
			set.set(c, v);
			c.preset = Preset.CUSTOM;
		};
		AbstractWidget slider = HelioScreen.slider(key, get.applyAsInt(c), min, max, suffix, change);
		Button reset = Button.builder(Component.translatable("helio.reset_value", initial + suffix), b -> {
			change.accept(initial);
			rebuildWidgets();
		}).build();
		if (!Helio.rayTracing) slider.active = reset.active = false;
		reset.setTooltip(Tooltip.create(Component.translatable("helio.reset_value.tooltip")));
		list.addSmall(slider, reset);
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("helio.reset_all"), b -> {
			HelioConfig c = HelioConfig.current, d = HelioConfig.DEFAULTS;
			c.fogSteps = d.fogSteps;
			c.cloudSteps = d.cloudSteps;
			c.lightCandidates = d.lightCandidates;
			c.specularCutoff = d.specularCutoff;
			c.cacheFeed = d.cacheFeed;
			c.preset = Preset.CUSTOM;
			rebuildWidgets();
		}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
	}

	@Override
	public void removed() {
		HelioConfig.save();
	}
}

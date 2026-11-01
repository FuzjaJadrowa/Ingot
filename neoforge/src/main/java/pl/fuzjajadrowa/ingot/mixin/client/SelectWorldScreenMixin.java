package pl.fuzjajadrowa.ingot.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.fuzjajadrowa.ingot.client.screen.ConversionConfirmationScreen;
import pl.fuzjajadrowa.ingot.client.screen.IngotSelectWorldAccess;
import pl.fuzjajadrowa.ingot.client.screen.WorldSelectionListReloadAccess;
import pl.fuzjajadrowa.ingot.conversion.ConversionDirection;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

import java.io.IOException;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen implements IngotSelectWorldAccess {
    @Shadow @Final private Button selectButton;
    @Shadow private WorldSelectionList list;
    @Unique private Button ingot$convertButton;
    @Unique private LevelSummary ingot$selectedSummary;
    @Unique private ConversionDirection ingot$direction;

    protected SelectWorldScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void ingot$addConvertButton(CallbackInfo ci) {
        Button create = children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(Component.translatable("selectWorld.create").getString()))
                .findFirst().orElse(null);
        if (create != null) {
            int center = width / 2;
            create.setWidth(150);
            create.setX(center + 4);
            selectButton.setMessage(Component.translatable("ingot.button.play"));
            selectButton.setWidth(72);
            selectButton.setX(center - 154);
        }
        ingot$convertButton = addRenderableWidget(Button.builder(Component.translatable("ingot.button.convert"), button -> ingot$openConfirmation())
                .bounds(width / 2 - 76, height - 52, 72, 20).build());
        ingot$convertButton.active = false;
    }

    @Inject(method = "updateButtonStatus", at = @At("TAIL"))
    private void ingot$updateConvertStatus(LevelSummary summary, CallbackInfo ci) {
        selectButton.setMessage(Component.translatable("ingot.button.play"));
        ingot$selectedSummary = summary;
        ingot$direction = null;
        if (ingot$convertButton == null) return;
        if (summary == null) {
            ingot$convertButton.active = false;
            ingot$convertButton.setTooltip(null);
            return;
        }
        try {
            WorldStorageDetector.Detection detection = WorldStorageDetector.inspect(minecraft.getLevelSource().getLevelPath(summary.getLevelId()));
            if (!summary.isLocked() && detection.format() == WorldStorageDetector.Format.ANVIL) ingot$direction = ConversionDirection.ANVIL_TO_INGOT;
            if (!summary.isLocked() && detection.format() == WorldStorageDetector.Format.INGOT) ingot$direction = ConversionDirection.INGOT_TO_ANVIL;
            ingot$convertButton.active = ingot$direction != null;
            ingot$convertButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                    ingot$direction == null ? "ingot.button.convert.unavailable"
                            : "ingot.button.convert.direction." + ingot$direction.name().toLowerCase(java.util.Locale.ROOT))));
        } catch (IOException | RuntimeException failure) {
            ingot$convertButton.active = false;
            ingot$convertButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("ingot.button.convert.unavailable")));
        }
    }

    @Unique private void ingot$openConfirmation() {
        if (ingot$selectedSummary == null || ingot$direction == null) return;
        var path = minecraft.getLevelSource().getLevelPath(ingot$selectedSummary.getLevelId());
        minecraft.setScreen(new ConversionConfirmationScreen((SelectWorldScreen) (Object) this, path,
                ingot$selectedSummary.getLevelId(), ingot$direction));
    }

    @Override public void ingot$refreshWorldList() {
        if (list != null) ((WorldSelectionListReloadAccess) list).ingot$reloadWorldList();
    }
}
package pl.fuzjajadrowa.ingot.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;
import pl.fuzjajadrowa.ingot.client.screen.IngotWorldEntryAccess;

import java.io.IOException;
import java.time.Instant;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin implements IngotWorldEntryAccess {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final LevelSummary summary;
    @Unique private WorldStorageDetector.Detection ingot$detection;

    @Override public LevelSummary ingot$summary() { return summary; }

    @Override public WorldStorageDetector.Detection ingot$storageDetection() {
        if (ingot$detection == null) {
            try {
                ingot$detection = WorldStorageDetector.inspect(minecraft.getLevelSource().getLevelPath(summary.getLevelId()));
            } catch (IOException | RuntimeException failure) {
                ingot$detection = new WorldStorageDetector.Detection(WorldStorageDetector.Format.UNSAFE,
                        false, false, java.util.List.of(minecraft.getLevelSource().getLevelPath(summary.getLevelId())));
            }
        }
        return ingot$detection;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void ingot$renderFormat(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
                                    int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        WorldStorageDetector.Format format = ingot$storageDetection().format();
        Component label;
        int color;
        ResourceLocation sprite = null;
        switch (format) {
            case ANVIL -> { label = Component.translatable("ingot.storage.format.anvil"); sprite = ResourceLocation.fromNamespaceAndPath("ingot", "anvil"); color = 0xFFFFAA00; }
            case INGOT -> { label = Component.translatable("ingot.storage.format.ingot"); sprite = ResourceLocation.fromNamespaceAndPath("ingot", "ingot"); color = 0xFFFFFFFF; }
            case EMPTY -> { label = Component.translatable("ingot.storage.format.empty"); color = 0xFFAAAAAA; }
            case MIXED -> { label = Component.translatable("ingot.storage.format.mixed"); color = 0xFFFF5555; }
            default -> { label = Component.translatable("ingot.storage.format.unknown"); color = 0xFFFF5555; }
        }
        String secondLine = summary.getLevelId();
        if (summary.getLastPlayed() != -1L) {
            secondLine += " (" + WorldSelectionList.DATE_FORMAT.format(Instant.ofEpochMilli(summary.getLastPlayed())) + ")";
        }
        int iconX = x + 35 + minecraft.font.width(secondLine) + 5;
        if (sprite != null) graphics.blitSprite(sprite, iconX, y + 10, 11, 11);
        int textX = iconX + (sprite == null ? 0 : 13);
        graphics.drawString(minecraft.font, label, textX, y + 12, color, false);
    }
}
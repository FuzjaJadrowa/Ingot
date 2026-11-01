package pl.fuzjajadrowa.ingot.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import pl.fuzjajadrowa.ingot.Ingot;
import pl.fuzjajadrowa.ingot.conversion.ConversionDirection;

import java.io.IOException;
import java.nio.file.Path;

public final class ConversionConfirmationScreen extends Screen {
    private final Screen parent;
    private final Path world;
    private final String levelId;
    private final ConversionDirection direction;

    public ConversionConfirmationScreen(Screen parent, Path world, String levelId, ConversionDirection direction) {
        super(Component.translatable("ingot.conversion.confirm.title"));
        this.parent = parent;
        this.world = world;
        this.levelId = levelId;
        this.direction = direction;
    }

    @Override protected void init() {
        int center = width / 2;
        int messageHeight = (font.split(description(), Math.max(1, width - 50)).size() + 1) * 9;
        int firstY = 100 + messageHeight;
        addRenderableWidget(Button.builder(Component.translatable("ingot.conversion.confirm.backup"), button -> createBackup())
                .bounds(center - 155, firstY, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("selectWorld.backupJoinSkipButton"), button -> beginConversion())
                .bounds(center + 5, firstY, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(center - 75, 124 + messageHeight, 150, 20).build());
    }

    private Component description() {
        Component source = Component.translatable(direction == ConversionDirection.ANVIL_TO_INGOT
                ? "ingot.storage.format.anvil" : "ingot.storage.format.ingot");
        Component target = Component.translatable(direction == ConversionDirection.ANVIL_TO_INGOT
                ? "ingot.storage.format.ingot" : "ingot.storage.format.anvil");
        return Component.translatable("ingot.conversion.confirm.description", source, target);
    }

    private void createBackup() {
        try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(levelId)) {
            if (EditWorldScreen.makeBackupAndShowToast(access)) beginConversion();
        } catch (IOException | RuntimeException failure) {
            Ingot.LOGGER.error("Minecraft world backup failed; world conversion was not started for {}", world, failure);
            Component details = Component.literal(failure.getMessage() == null ? "" : failure.getMessage());
            minecraft.getToasts().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP,
                    Component.translatable("selectWorld.edit.backupFailed"), details));
        }
    }

    private void beginConversion() {
        minecraft.setScreen(new WorldConversionScreen(parent, world, direction));
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Component heading = title.copy().withStyle(style -> style.withBold(true).withColor(0xFFCC55));
        graphics.drawCenteredString(font, heading, width / 2, 50, 0xFFFFCC55);
        int y = 70;
        for (var line : font.split(description(), Math.max(1, width - 50))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xA0A0A0);
            y += 9;
        }
    }
}
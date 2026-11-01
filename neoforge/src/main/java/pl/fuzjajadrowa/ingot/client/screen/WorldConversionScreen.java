package pl.fuzjajadrowa.ingot.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import pl.fuzjajadrowa.ingot.Ingot;
import pl.fuzjajadrowa.ingot.conversion.ConversionDirection;
import pl.fuzjajadrowa.ingot.conversion.ConversionProgress;
import pl.fuzjajadrowa.ingot.conversion.ConversionJournal;
import pl.fuzjajadrowa.ingot.conversion.WorldConversionStager;
import pl.fuzjajadrowa.ingot.client.screen.IngotSelectWorldAccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class WorldConversionScreen extends Screen {
    private final Screen parent;
    private final Path world;
    private final ConversionDirection direction;
    private volatile ConversionProgress progress = new ConversionProgress(ConversionProgress.Stage.PREFLIGHT, "Preparing world", 0, 0);
    private volatile Component failure;
    private volatile boolean finished;
    private volatile boolean recoveryRunning;
    private boolean started;

    public WorldConversionScreen(Screen parent, Path world, ConversionDirection direction) {
        super(Component.translatable("ingot.conversion.progress.title"));
        this.parent = parent;
        this.world = world;
        this.direction = direction;
    }

    @Override protected void init() {
        if (!started) {
            started = true;
            CompletableFuture.runAsync(this::convert, Util.backgroundExecutor());
        }
        boolean recoverable = failure != null && Files.exists(world.resolve(ConversionJournal.FILE_NAME), LinkOption.NOFOLLOW_LINKS);
        if (recoverable) {
            addRenderableWidget(Button.builder(Component.translatable("ingot.conversion.progress.recover"), button -> retryRecovery())
                    .bounds(width / 2 - 100, height / 4 + 150, 200, 20).build()).active = finished && !recoveryRunning;
        }
    }

    private void retryRecovery() {
        if (recoveryRunning || !finished) return;
        recoveryRunning = true;
        rebuildWidgets();
        CompletableFuture.runAsync(() -> {
            try {
                WorldConversionStager.recover(world);
                minecraft.execute(() -> {
                    recoveryRunning = false;
                    if (parent instanceof IngotSelectWorldAccess access) access.ingot$refreshWorldList();
                    minecraft.setScreen(parent);
                });
            } catch (IOException recoveryFailure) {
                Ingot.LOGGER.error("Manual conversion recovery failed for {}", world, recoveryFailure);
                minecraft.execute(() -> {
                    recoveryRunning = false;
                    failure = Component.translatable("ingot.conversion.error.recovery_failed");
                    rebuildWidgets();
                });
            }
        }, Util.backgroundExecutor());
    }

    private void convert() {
        try {
            WorldConversionStager.Result staged = WorldConversionStager.stage(world, direction, this::updateProgress);
            WorldConversionStager.publish(world, staged, this::updateProgress);
            minecraft.execute(() -> {
                finished = true;
                progress = new ConversionProgress(ConversionProgress.Stage.COMPLETE,
                        "Conversion complete", 1, 1);
                if (parent instanceof IngotSelectWorldAccess access) access.ingot$refreshWorldList();
                minecraft.setScreen(parent);
            });
        } catch (Exception conversionFailure) {
            Ingot.LOGGER.error("World conversion failed for {}", world, conversionFailure);
            String recoveryMessage = "";
            Path journal = world.resolve(ConversionJournal.FILE_NAME);
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    WorldConversionStager.recover(world);
                    recoveryMessage = " (original storage restored)";
                } catch (IOException recoveryFailure) {
                    conversionFailure.addSuppressed(recoveryFailure);
                    recoveryMessage = " (recovery is still required; world files were preserved)";
                    Ingot.LOGGER.error("World conversion recovery remains pending for {}", world, recoveryFailure);
                }
            }
            String finalRecoveryMessage = recoveryMessage;
            minecraft.execute(() -> {
                failure = Component.translatable("ingot.conversion.error.failed", finalRecoveryMessage);
                finished = true;
                rebuildWidgets();
            });
        }
    }

    private void updateProgress(ConversionProgress next) {
        minecraft.execute(() -> progress = next);
    }

    private double displayProgress() {
        double fraction = progress.fraction();
        if (progress.stage() == ConversionProgress.Stage.PREFLIGHT) return 0.01;
        if (progress.stage() == ConversionProgress.Stage.STAGING) return 0.05 + fraction * 0.35;
        if (progress.stage() == ConversionProgress.Stage.VERIFICATION) {
            return progress.operation().startsWith("Verifying published") ? 0.72 + fraction * 0.24 : 0.4 + fraction * 0.15;
        }
        if (progress.stage() == ConversionProgress.Stage.PUBLICATION) return 0.55 + fraction * 0.15;
        return finished && failure == null ? 1.0 : 0.55;
    }

    @Override public void onClose() {
        if (finished) minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        String statusKey = progress.stage() == ConversionProgress.Stage.COMPLETE
                ? "ingot.conversion.status.staged"
                : "ingot.conversion.status." + progress.stage().name().toLowerCase(java.util.Locale.ROOT);
        Component source = Component.translatable(direction == ConversionDirection.ANVIL_TO_INGOT
                ? "ingot.storage.format.anvil" : "ingot.storage.format.ingot");
        Component target = Component.translatable(direction == ConversionDirection.ANVIL_TO_INGOT
                ? "ingot.storage.format.ingot" : "ingot.storage.format.anvil");
        Component status = failure == null ? Component.translatable(statusKey) : failure;
        Component description = Component.empty().append(source).append(" → ").append(target).append(": ").append(status);
        graphics.drawCenteredString(font, description, width / 2, height / 4 + 90, failure == null ? 0xA0A0A0 : 0xFFFF5555);
        int barWidth = Math.min(300, width - 40);
        int left = (width - barWidth) / 2;
        int top = height / 4 + 112;
        graphics.fill(left, top, left + barWidth, top + 8, 0xFF444444);
        graphics.fill(left, top, left + (int) (barWidth * displayProgress()), top + 8, 0xFFCCAA33);
        if (progress.total() > 0) {
            graphics.drawCenteredString(font,
                    Component.translatable("optimizeWorld.progress.counter", progress.completed(), progress.total()),
                    width / 2, top + 12, 0xA0A0A0);
        }
    }
}
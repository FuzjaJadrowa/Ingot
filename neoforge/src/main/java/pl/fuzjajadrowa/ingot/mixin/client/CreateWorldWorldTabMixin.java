package pl.fuzjajadrowa.ingot.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.fuzjajadrowa.ingot.client.screen.IngotCreateWorldAccess;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

import java.util.List;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldWorldTabMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ingot$addStorageFormatSelector(CreateWorldScreen screen, CallbackInfo ci) {
        IngotCreateWorldAccess access = (IngotCreateWorldAccess) screen;
        StringWidget label = new StringWidget(266, 20,
                Component.translatable("ingot.createWorld.storageFormat"), Minecraft.getInstance().font).alignLeft();
        CycleButton<WorldStorageDetector.Format> selector = CycleButton.<WorldStorageDetector.Format>builder(
                        format -> Component.translatable("ingot.storage.format." + format.name().toLowerCase(java.util.Locale.ROOT)))
                .withValues(List.of(WorldStorageDetector.Format.ANVIL, WorldStorageDetector.Format.INGOT))
                .withInitialValue(access.ingot$getStorageFormat())
                .displayOnlyValue()
                .create(0, 0, 44, 20, Component.empty(), (button, format) -> access.ingot$setStorageFormat(format));

        GridLayoutTabAccessor accessor = (GridLayoutTabAccessor) (Object) this;
        LinearLayout row = LinearLayout.horizontal().spacing(0);
        row.addChild(label);
        row.addChild(selector);
        accessor.ingot$getLayout().addChild(row, 3, 0, 1, 2);
    }
}
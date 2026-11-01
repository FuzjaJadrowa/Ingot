package pl.fuzjajadrowa.ingot.mixin.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.fuzjajadrowa.ingot.client.screen.IngotCreateWorldAccess;
import pl.fuzjajadrowa.ingot.world.IngotWorldManager;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin implements IngotCreateWorldAccess {
    @Shadow @Final WorldCreationUiState uiState;
    @Unique private WorldStorageDetector.Format ingot$storageFormat = WorldStorageDetector.Format.INGOT;

    @Override public WorldStorageDetector.Format ingot$getStorageFormat() {
        return ingot$storageFormat;
    }

    @Override public void ingot$setStorageFormat(WorldStorageDetector.Format format) {
        if (format != WorldStorageDetector.Format.ANVIL && format != WorldStorageDetector.Format.INGOT) {
            throw new IllegalArgumentException("World creation supports only Anvil or Ingot storage");
        }
        ingot$storageFormat = format;
    }

    @Inject(method = "onCreate", at = @At("HEAD"))
    private void ingot$rememberStorageFormat(CallbackInfo ci) {
        IngotWorldManager.registerNewWorldFormat(
                net.minecraft.client.Minecraft.getInstance().getLevelSource().getLevelPath(uiState.getTargetFolder()),
                ingot$storageFormat);
    }
}
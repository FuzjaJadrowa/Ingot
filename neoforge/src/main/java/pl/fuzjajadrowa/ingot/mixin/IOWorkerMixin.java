package pl.fuzjajadrowa.ingot.mixin;

import pl.fuzjajadrowa.ingot.integration.IngotStorageAccess;
import pl.fuzjajadrowa.ingot.integration.MinecraftIngotStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Shadow @Final private RegionFileStorage storage;

    @Inject(method = "close", at = @At("RETURN"))
    private void ingot$surfaceCloseFailure(CallbackInfo ci) {
        MinecraftIngotStorage backend = ((IngotStorageAccess) (Object) storage).ingot$backend();
        if (backend != null) backend.checkHealthy();
    }
}
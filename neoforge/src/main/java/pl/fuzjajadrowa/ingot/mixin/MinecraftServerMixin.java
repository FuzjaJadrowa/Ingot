package pl.fuzjajadrowa.ingot.mixin;

import pl.fuzjajadrowa.ingot.Ingot;
import pl.fuzjajadrowa.ingot.world.IngotWorldManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;
    @Unique private IngotWorldManager.Session ingot$session;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ingot$open(CallbackInfo ci) throws IOException {
        ingot$session = IngotWorldManager.openWorld(storageSource.getLevelPath(LevelResource.ROOT));
        if (ingot$session != null) Ingot.LOGGER.warn("Experimental Ingot authoritative storage active for {}", ingot$session.root());
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void ingot$checkStorage(CallbackInfo ci) {
        if (ingot$session != null) ingot$session.checkHealthy();
    }

    // To prevent creating blank chunk by neoforge chunk map
    @Inject(method = {"reportChunkLoadFailure", "reportChunkSaveFailure"}, at = @At("HEAD"))
    private void ingot$fatalStorageFailure(Throwable failure, RegionStorageInfo info, ChunkPos pos, CallbackInfo ci) {
        if (ingot$session != null) throw ingot$session.fail(failure);
    }

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void ingot$unregister(CallbackInfo ci) {
        IngotWorldManager.closeWorld(ingot$session);
    }

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"))
    private void ingot$emergencyCleanup(CallbackInfo ci) {
        IngotWorldManager.closeWorld(ingot$session);
    }
}
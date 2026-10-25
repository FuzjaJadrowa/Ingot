package pl.fuzjajadrowa.ingot.mixin;

import pl.fuzjajadrowa.ingot.integration.IngotStorageAccess;
import pl.fuzjajadrowa.ingot.integration.MinecraftIngotStorage;
import pl.fuzjajadrowa.ingot.integration.VanillaRegionStorageTypes;
import pl.fuzjajadrowa.ingot.world.IngotWorldManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.io.IOException;
import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements IngotStorageAccess {
    @Unique private MinecraftIngotStorage ingot$storage;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ingot$initialize(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) throws IOException {
        if (!VanillaRegionStorageTypes.usesIngotStorageType(info.type())) return;
        IngotWorldManager.Session session = IngotWorldManager.forFolder(folder);
        if (session != null) ingot$storage = new MinecraftIngotStorage(folder, sync, session);
    }

    @Override public MinecraftIngotStorage ingot$backend() { return ingot$storage; }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void ingot$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) {
        if (ingot$storage != null) cir.setReturnValue(ingot$storage.read(pos));
    }
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void ingot$write(ChunkPos pos, CompoundTag tag, CallbackInfo ci) {
        if (ingot$storage != null) { ingot$storage.write(pos, tag); ci.cancel(); }
    }
    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void ingot$scan(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) {
        if (ingot$storage != null) { ingot$storage.scan(pos, visitor); ci.cancel(); }
    }
    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void ingot$flush(CallbackInfo ci) {
        if (ingot$storage != null) { ingot$storage.flush(); ci.cancel(); }
    }
    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void ingot$close(CallbackInfo ci) {
        if (ingot$storage != null) { ingot$storage.close(); ci.cancel(); }
    }
}
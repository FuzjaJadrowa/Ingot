package pl.fuzjajadrowa.ingot.mixin.client;

import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import pl.fuzjajadrowa.ingot.client.screen.WorldSelectionListReloadAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WorldSelectionList.class)
public interface WorldSelectionListAccess extends WorldSelectionListReloadAccess {
    @Override
    @Invoker("reloadWorldList")
    void ingot$reloadWorldList();
}
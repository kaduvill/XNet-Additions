package xnet.additions.mixin.client;

import mcjty.lib.gui.GenericGuiContainer;
import mcjty.lib.tileentity.GenericTileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GenericGuiContainer.class, remap = false)
public interface GenericGuiContainerAccessor {

    @Accessor(value = "tileEntity", remap = false)
    GenericTileEntity xnetadditions$getTileEntity();
}
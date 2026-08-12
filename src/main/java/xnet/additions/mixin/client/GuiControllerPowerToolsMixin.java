package xnet.additions.mixin.client;

import mcjty.lib.gui.WindowManager;
import mcjty.xnet.blocks.controller.gui.GuiController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xnet.additions.powertools.client.PowerToolsWindow;

import java.awt.Rectangle;
import java.util.List;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerPowerToolsMixin {
    @Unique private PowerToolsWindow xnetadditions$powerToolsWindow;
    @Unique private boolean xnetadditions$powerToolsOpen;

    @Inject(method = "initGui", at = @At("HEAD"), remap = true)
    private void xnetadditions$beforePowerToolsInit(CallbackInfo ci) {
        if (xnetadditions$powerToolsWindow != null) {xnetadditions$powerToolsOpen = xnetadditions$powerToolsWindow.isOpen();}
        xnetadditions$powerToolsWindow = null;
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = true)
    private void xnetadditions$afterPowerToolsInit(CallbackInfo ci) {
        xnetadditions$powerToolsWindow = new PowerToolsWindow((GuiController) (Object) this, xnetadditions$powerToolsOpen);
    }

    @Inject(method = "registerWindows", at = @At("TAIL"), remap = false)
    private void xnetadditions$registerPowerToolsWindow(WindowManager manager, CallbackInfo ci) {
        if (xnetadditions$powerToolsWindow != null) {manager.addWindow(xnetadditions$powerToolsWindow.getWindow());}
    }

    @Inject(method = "drawGuiContainerBackgroundLayer", at = @At("HEAD"), remap = true)
    private void xnetadditions$positionPowerToolsWindow(float partialTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (xnetadditions$powerToolsWindow != null) {xnetadditions$powerToolsWindow.updateBounds();}
    }

    @Inject(method = "getSideWindowBounds", at = @At("RETURN"), remap = false)
    private void xnetadditions$includePowerToolsBounds(CallbackInfoReturnable<List<Rectangle>> cir) {
        if (xnetadditions$powerToolsWindow == null) {return;}
        Rectangle bounds = xnetadditions$powerToolsWindow.getBounds();
        if (bounds.width > 0 && bounds.height > 0) {cir.getReturnValue().add(bounds);}
    }
}
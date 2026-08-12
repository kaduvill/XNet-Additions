package xnet.additions.mixin.client;

import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xnet.additions.powertools.client.PowerToolsWindow;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;

import java.awt.Rectangle;
import java.util.List;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerPowerToolsMixin implements DiagnosticsNetwork.Receiver {

    @Shadow(remap = false) private ToggleButton[] channelButtons;

    @Invoker(value = "selectChannelEditor", remap = false)
    protected abstract void xnetadditions$powerToolsSelectChannelEditor(int channel);

    @Unique private PowerToolsWindow xnetadditions$powerTools;

    @Inject(method = "initGui", at = @At("TAIL"), remap = true)
    private void xnetadditions$initializePowerTools(CallbackInfo ci) {
        if (xnetadditions$powerTools != null) {return;}
        GenericTileEntity tile = ((GenericGuiContainerAccessor) this).xnetadditions$getTileEntity();
        if (tile instanceof TileEntityController) {
            xnetadditions$powerTools = new PowerToolsWindow((GuiController) (Object) this,
                    (TileEntityController) tile, this::xnetadditions$selectNativeChannel);
        }
    }

    @Inject(method = "registerWindows", at = @At("TAIL"), remap = false)
    private void xnetadditions$registerPowerTools(WindowManager manager, CallbackInfo ci) {
        if (xnetadditions$powerTools != null) {xnetadditions$powerTools.register(manager);}
    }

    @Inject(method = "drawGuiContainerBackgroundLayer", at = @At("HEAD"), remap = true)
    private void xnetadditions$updatePowerTools(float partialTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (xnetadditions$powerTools != null) {xnetadditions$powerTools.update();}
    }

    @Inject(method = "getSideWindowBounds", at = @At("RETURN"), remap = false)
    private void xnetadditions$includePowerToolsBounds(CallbackInfoReturnable<List<Rectangle>> cir) {
        if (xnetadditions$powerTools == null) {return;}
        Rectangle bounds = xnetadditions$powerTools.getVisibleBounds();
        if (bounds != null) {cir.getReturnValue().add(bounds);}
    }

    @Override
    @Unique
    public void xnetadditions$receiveDiagnostics(DiagnosticsNetwork.Response response) {
        if (xnetadditions$powerTools != null) {xnetadditions$powerTools.receive(response);}
    }

    @Unique
    private void xnetadditions$selectNativeChannel(int channel) {
        if (channel < 0 || channel >= channelButtons.length || channelButtons[channel] == null) {return;}
        channelButtons[channel].setPressed(true);
        xnetadditions$powerToolsSelectChannelEditor(channel);
    }
}
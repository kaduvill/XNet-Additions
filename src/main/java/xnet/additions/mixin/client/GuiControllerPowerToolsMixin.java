package xnet.additions.mixin.client;

import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.widgets.TextField;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xnet.additions.powertools.client.ControllerNavigator;
import xnet.additions.powertools.client.PowerToolsWindow;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;

import java.awt.Rectangle;
import java.util.List;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerPowerToolsMixin implements DiagnosticsNetwork.Receiver, ControllerNavigator {

    @Shadow(remap = false) private ToggleButton[] channelButtons;
    @Shadow(remap = false) private WidgetList connectorList;
    @Shadow(remap = false) private List<SidedPos> connectorPositions;
    @Shadow(remap = false) private TextField searchBar;
    @Shadow(remap = false) private String rememberedSearchText;
    @Shadow(remap = false) private boolean needsRefresh;
    @Shadow(remap = false) private SidedPos editingConnector;
    @Shadow(remap = false) private int editingChannel;

    @Invoker(value = "selectChannelEditor", remap = false)
    protected abstract void xnetadditions$powerToolsSelectChannelEditor(int channel);

    @Invoker(value = "selectConnectorEditor", remap = false)
    protected abstract void xnetadditions$powerToolsSelectConnectorEditor(SidedPos connector, int channel);

    @Unique private PowerToolsWindow xnetadditions$powerTools;
    @Unique private SidedPos xnetadditions$pendingReveal;

    @Inject(method = "initGui", at = @At("TAIL"), remap = true)
    private void xnetadditions$initializePowerTools(CallbackInfo ci) {
        if (xnetadditions$powerTools != null) {return;}
        GenericTileEntity tile = ((GenericGuiContainerAccessor) this).xnetadditions$getTileEntity();
        if (tile instanceof TileEntityController) {
            xnetadditions$powerTools = new PowerToolsWindow((GuiController) (Object) this,
                    (TileEntityController) tile, this::xnetadditions$selectNativeChannel, this);
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

    @Inject(method = "drawGuiContainerBackgroundLayer", at = @At("TAIL"), remap = true)
    private void xnetadditions$observeControllerEditor(float partialTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (xnetadditions$pendingReveal != null) {
            int index = connectorPositions.indexOf(xnetadditions$pendingReveal);
            if (index >= 0) {
                connectorList.setSelected(index);
                int first = connectorList.getFirstSelected();
                int visible = connectorList.getCountSelected();
                if (index < first) {connectorList.setFirstSelected(index);}
                else if (visible > 0 && index >= first + visible) {connectorList.setFirstSelected(index - visible + 1);}
                xnetadditions$pendingReveal = null;
            } else if (xnetadditions$isNavigationReady() && searchBar.getText().isEmpty()) {xnetadditions$pendingReveal = null;}
        }
        if (xnetadditions$powerTools != null && editingConnector != null && editingChannel >= 0) {
            xnetadditions$powerTools.observe(editingConnector, editingChannel);
        }
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

    @Override
    @Unique
    public boolean xnetadditions$isNavigationReady() {
        return connectorList != null && searchBar != null && GuiController.fromServer_channels != null && GuiController.fromServer_connectedBlocks != null;
    }

    @Override
    @Unique
    public boolean xnetadditions$navigate(SidedPos connector, int channel) {
        if (!xnetadditions$isNavigationReady() || channel < 0 || channel >= channelButtons.length || channel >= GuiController.fromServer_channels.size() || channelButtons[channel] == null || !xnetadditions$isConnected(connector)) {return false;}
        boolean hidden = !connectorPositions.contains(connector);
        xnetadditions$powerToolsSelectConnectorEditor(connector, channel);
        if (connector.equals(editingConnector) && channel == editingChannel) {
            if (hidden && !searchBar.getText().isEmpty()) {
                searchBar.setText("");
                rememberedSearchText = "";
                needsRefresh = true;
            }
            xnetadditions$pendingReveal = connector;
        }
        return true;
    }

    @Unique
    private void xnetadditions$selectNativeChannel(int channel) {
        if (channel < 0 || channel >= channelButtons.length || channelButtons[channel] == null) {return;}
        channelButtons[channel].setPressed(true);
        xnetadditions$powerToolsSelectChannelEditor(channel);
    }

    @Unique
    private boolean xnetadditions$isConnected(SidedPos connector) {
        if (connector == null || GuiController.fromServer_connectedBlocks == null) {return false;}
        for (ConnectedBlockClientInfo block : GuiController.fromServer_connectedBlocks) {
            if (connector.equals(block.getPos())) {return true;}
        }
        return false;
    }
}
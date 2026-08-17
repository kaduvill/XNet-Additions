package xnet.additions.mixin.remoteconnector.client;

import mcjty.lib.gui.widgets.AbstractContainerWidget;
import mcjty.lib.gui.widgets.BlockRender;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.Logging;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xnet.additions.powertools.remoteconnector.client.RemoteConnectorMouseHandler;
import xnet.additions.powertools.remoteconnector.client.RemoteGuiConnector;
import xnet.additions.powertools.remoteconnector.network.RemoteConnectorNetwork;

import java.util.List;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerRemoteConnectorMixin
        implements RemoteConnectorMouseHandler,
        RemoteConnectorNetwork.Receiver {
    @Shadow(remap = false)
    private WidgetList connectorList;

    @Shadow(remap = false)
    private List<SidedPos> connectorPositions;

    @Shadow(remap = false)
    private boolean needsRefresh;

    @Unique
    private boolean xnetadditions$decorateRemoteRows;

    @Unique
    private int xnetadditions$remoteRequestId;

    @Unique
    private SidedPos xnetadditions$remoteTarget;

    @Inject(method = "populateList", at = @At("HEAD"), remap = false)
    private void xnetadditions$beforeRemoteConnectorPopulate(
            CallbackInfo ci) {
        xnetadditions$decorateRemoteRows =
                needsRefresh
                        && GuiController.fromServer_channels != null
                        && GuiController.fromServer_connectedBlocks != null;
    }

    @Inject(method = "populateList", at = @At("RETURN"), remap = false)
    private void xnetadditions$addRemoteConnectorTooltips(
            CallbackInfo ci) {
        if (!xnetadditions$decorateRemoteRows) {
            return;
        }
        xnetadditions$decorateRemoteRows = false;

        int count = Math.min(
                connectorList.getChildCount(), connectorPositions.size());
        for (int i = 0; i < count; i++) {
            BlockRender icon =
                    xnetadditions$findBlockIcon(connectorList.getChild(i));
            if (icon != null && icon.getTooltips() != null) {
                icon.getTooltips().add(
                        TextFormatting.WHITE
                                + "Right-click to open connector");
            }
        }
    }

    @Override
    @Unique
    public boolean xnetadditions$handleRemoteConnectorRightClick(
            Widget<?> widget) {
        if (!(widget instanceof BlockRender)
                || !"block".equals(widget.getUserObject())) {
            return false;
        }

        int count = Math.min(
                connectorList.getChildCount(), connectorPositions.size());
        for (int i = 0; i < count; i++) {
            if (!connectorList.getChild(i).containsWidget(widget)) {
                continue;
            }

            GuiController gui = (GuiController) (Object) this;
            TileEntityController controller = gui.getTileEntity();
            xnetadditions$remoteTarget = connectorPositions.get(i);
            int requestId = ++xnetadditions$remoteRequestId;
            RemoteConnectorNetwork.CHANNEL.sendToServer(
                    RemoteConnectorNetwork.Request.open(
                            controller.getPos(),
                            xnetadditions$remoteTarget,
                            requestId));
            return true;
        }
        return false;
    }

    @Override
    @Unique
    public void xnetadditions$receiveRemoteConnector(
            RemoteConnectorNetwork.Response response) {
        GuiController gui = (GuiController) (Object) this;
        if (response.getRequestId() != xnetadditions$remoteRequestId
                || xnetadditions$remoteTarget == null
                || !xnetadditions$remoteTarget.equals(response.getTarget())
                || !gui.getTileEntity().getPos()
                .equals(response.getControllerPos())) {
            return;
        }

        xnetadditions$remoteTarget = null;
        if (response.getKind() == RemoteConnectorNetwork.OPEN) {
            Minecraft.getMinecraft().displayGuiScreen(
                    new RemoteGuiConnector(gui, response));
        } else if (!response.getMessage().isEmpty()) {
            Logging.warn(Minecraft.getMinecraft().player,
                    response.getMessage());
        }
    }

    @Unique
    private BlockRender xnetadditions$findBlockIcon(Widget<?> widget) {
        if (widget instanceof BlockRender
                && "block".equals(widget.getUserObject())) {
            return (BlockRender) widget;
        }

        if (widget instanceof AbstractContainerWidget) {
            for (Widget<?> child :
                    ((AbstractContainerWidget<?>) widget).getChildren()) {
                BlockRender found =
                        xnetadditions$findBlockIcon(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
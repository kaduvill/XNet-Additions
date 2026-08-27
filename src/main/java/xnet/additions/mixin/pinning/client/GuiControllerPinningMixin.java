package xnet.additions.mixin.pinning.client;

import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.BlockRender;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.Logging;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xnet.additions.powertools.pinning.client.ConnectorPinMouseHandler;
import xnet.additions.powertools.pinning.client.ConnectorPinStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerPinningMixin implements ConnectorPinMouseHandler {
    @Shadow(remap = false) private WidgetList connectorList;
    @Shadow(remap = false) private List<SidedPos> connectorPositions;
    @Shadow(remap = false) private boolean needsRefresh;

    @Unique private boolean xnetadditions$pinListRebuildPending;
    @Unique private SidedPos xnetadditions$pinSelectedConnector;
    @Unique private SidedPos xnetadditions$pinVisibleConnector;

    @Inject(method = "populateList", at = @At("HEAD"), remap = false)
    private void xnetadditions$beforePinnedPopulate(CallbackInfo ci) {
        xnetadditions$pinListRebuildPending = needsRefresh
                && GuiController.fromServer_channels != null
                && GuiController.fromServer_connectedBlocks != null;
    }

    @Redirect(
            method = "populateList",
            at = @At(
                    value = "FIELD",
                    target = "Lmcjty/xnet/blocks/controller/gui/GuiController;fromServer_connectedBlocks:Ljava/util/List;"
            ),
            remap = false
    )
    private List<ConnectedBlockClientInfo> xnetadditions$orderPinnedConnectors() {
        List<ConnectedBlockClientInfo> connectedBlocks = GuiController.fromServer_connectedBlocks;
        TileEntityController controller = xnetadditions$getController();
        if (controller == null || connectedBlocks == null) return connectedBlocks;

        Set<SidedPos> pins = ConnectorPinStore.getPins(controller);
        if (pins.isEmpty()) return connectedBlocks;

        int pinned = 0;
        for (ConnectedBlockClientInfo block : connectedBlocks) {
            if (pins.contains(block.getPos())) pinned++;
        }
        if (pinned == 0 || pinned == connectedBlocks.size()) return connectedBlocks;

        List<ConnectedBlockClientInfo> ordered = new ArrayList<>(connectedBlocks.size());

        for (ConnectedBlockClientInfo block : connectedBlocks) {
            if (pins.contains(block.getPos())) ordered.add(block);
        }

        for (ConnectedBlockClientInfo block : connectedBlocks) {
            if (!pins.contains(block.getPos())) ordered.add(block);
        }

        return ordered;
    }

    @Inject(method = "populateList", at = @At("RETURN"), remap = false)
    private void xnetadditions$afterPinnedPopulate(CallbackInfo ci) {
        if (!xnetadditions$pinListRebuildPending) return;
        xnetadditions$pinListRebuildPending = false;

        TileEntityController controller = xnetadditions$getController();
        if (controller != null) {
            xnetadditions$decoratePinnedRows(ConnectorPinStore.getPins(controller));
        }

        if (xnetadditions$pinSelectedConnector != null) {
            int selected = connectorPositions.indexOf(xnetadditions$pinSelectedConnector);
            if (selected >= 0) connectorList.setSelected(selected);
        }

        if (xnetadditions$pinVisibleConnector != null) {
            int visible = connectorPositions.indexOf(xnetadditions$pinVisibleConnector);
            if (visible >= 0) xnetadditions$keepVisible(visible);
        }

        xnetadditions$pinSelectedConnector = null;
        xnetadditions$pinVisibleConnector = null;
    }

    @Override
    @Unique
    public boolean xnetadditions$handleConnectorPinClick(Widget<?> widget) {
        if (!(widget instanceof BlockRender)
                || !"block".equals(widget.getUserObject())) return false;

        int count = Math.min(connectorList.getChildCount(), connectorPositions.size());

        for (int i = 0; i < count; i++) {
            if (!connectorList.getChild(i).containsWidget(widget)) continue;

            TileEntityController controller = xnetadditions$getController();
            if (controller == null) return true;

            SidedPos pin = connectorPositions.get(i);
            int selected = connectorList.getSelected();
            xnetadditions$pinSelectedConnector = selected >= 0 && selected < connectorPositions.size()
                    ? connectorPositions.get(selected) : null;

            int first = connectorList.getFirstSelected();
            int visible = connectorList.getCountSelected();
            xnetadditions$pinVisibleConnector = xnetadditions$pinSelectedConnector != null
                    && selected >= first && selected < first + visible
                    ? xnetadditions$pinSelectedConnector : pin;

            if (!ConnectorPinStore.togglePin(controller, pin)) {
                xnetadditions$pinSelectedConnector = null;
                xnetadditions$pinVisibleConnector = null;

                if (Minecraft.getMinecraft().player != null) {
                    Logging.warn(Minecraft.getMinecraft().player, "Could not save connector pins");
                }
                return true;
            }

            needsRefresh = true;
            return true;
        }

        return false;
    }

    @Unique
    private void xnetadditions$decoratePinnedRows(Set<SidedPos> pins) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiController gui = (GuiController) (Object) this;
        int count = Math.min(connectorList.getChildCount(), connectorPositions.size());

        for (int i = 0; i < count; i++) {
            Widget<?> rowWidget = connectorList.getChild(i);
            if (!(rowWidget instanceof Panel) || ((Panel) rowWidget).getChildCount() == 0) continue;

            Panel row = (Panel) rowWidget;
            Widget<?> icon = row.getChild(0);
            if (!(icon instanceof BlockRender)) continue;

            boolean pinned = pins.contains(connectorPositions.get(i));
            if (icon.getTooltips() != null) {
                icon.getTooltips().add(pinned
                        ? TextFormatting.YELLOW + "Pinned (Left Shift + click to unpin)"
                        : TextFormatting.WHITE + "Left Shift + click to pin");
            }

            if (pinned) {
                BlockRender blockIcon = (BlockRender) icon;
                Panel iconPanel = new Panel(mc, gui).setLayout(new PositionalLayout()).setDesiredWidth(16).setDesiredHeight(16);
                blockIcon.setLayoutHint(new PositionalLayout.PositionalHint(0, 0, 16, 16));

                Label marker = new Label(mc, gui) {
                    @Override
                    public void draw(int x, int y) {
                    }

                    @Override
                    public void drawPhase2(int x, int y) {
                        int markerWidth = mc.fontRenderer.getStringWidth("*");
                        GlStateManager.disableLighting();
                        GlStateManager.disableDepth();
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        mc.fontRenderer.drawStringWithShadow("*",
                                x + getBounds().x + getBounds().width - markerWidth - 1,
                                y + getBounds().y,
                                0xffffe080);
                        GlStateManager.enableDepth();
                    }
                };
                marker.setLayoutHint(new PositionalLayout.PositionalHint(0, 0, 16, 16));

                iconPanel.addChild(blockIcon);
                iconPanel.addChild(marker);
                row.removeChild(blockIcon);
                row.getChildren().add(0, iconPanel);
            }
        }
    }

    @Unique
    private void xnetadditions$keepVisible(int index) {
        int first = connectorList.getFirstSelected();
        int visible = connectorList.getCountSelected();

        if (index < first) {
            connectorList.setFirstSelected(index);
        } else if (visible > 0 && index >= first + visible) {
            connectorList.setFirstSelected(index - visible + 1);
        }
    }

    @Unique
    private TileEntityController xnetadditions$getController() {
        return ((GuiController) (Object) this).getTileEntity();
    }
}
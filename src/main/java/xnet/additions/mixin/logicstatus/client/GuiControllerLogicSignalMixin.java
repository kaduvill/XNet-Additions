package xnet.additions.mixin.logicstatus.client;

import mcjty.lib.gui.Window;
import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.xnet.api.channels.Color;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xnet.additions.logicstatus.client.LogicSignalStatusReceiver;
import xnet.additions.logicstatus.network.LogicSignalNetwork;
import xnet.additions.mixin.batchedit.client.GenericGuiContainerAccessor;

import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerLogicSignalMixin implements LogicSignalStatusReceiver {

    @Unique private static final int xnetadditions$POLL_TICKS = 5;
    @Unique private static final int xnetadditions$PANEL_WIDTH = 220;
    @Unique private static final int xnetadditions$PANEL_HEIGHT = 18;
    @Unique private Panel xnetadditions$logicSignalPanel;
    @Unique private boolean xnetadditions$logicSignalVisible;
    @Unique private int xnetadditions$activeSignalMask = -1;
    @Unique private int xnetadditions$renderedSignalMask = Integer.MIN_VALUE;
    @Unique private long xnetadditions$lastSignalRequestTick = Long.MIN_VALUE;
    @Unique private List<ChannelClientInfo> xnetadditions$observedChannels;
    @Unique private boolean xnetadditions$controllerHasLogic;

    @Inject(method = "initGui", at = @At("HEAD"), remap = true)
    private void xnetadditions$resetLogicSignals(CallbackInfo ci) {
        xnetadditions$logicSignalPanel = null;
        xnetadditions$logicSignalVisible = false;
        xnetadditions$activeSignalMask = -1;
        xnetadditions$renderedSignalMask = Integer.MIN_VALUE;
        xnetadditions$lastSignalRequestTick = Long.MIN_VALUE;
        xnetadditions$observedChannels = null;
        xnetadditions$controllerHasLogic = false;
    }

    @Inject(method = "registerWindows", at = @At("TAIL"), remap = false)
    private void xnetadditions$registerLogicSignalWindow(WindowManager manager, CallbackInfo ci) {
        GuiController gui = (GuiController) (Object) this;
        xnetadditions$logicSignalPanel = new Panel(Minecraft.getMinecraft(), gui).setLayout(new PositionalLayout())
                .setFilledBackground(0xff3f3f3f, 0xff777777).setFilledRectThickness(1);
        xnetadditions$logicSignalPanel.setBounds(new Rectangle(0, 0, 0, 0));
        manager.addWindow(new Window(gui, xnetadditions$logicSignalPanel));
    }

    @Inject(method = "getSideWindowBounds", at = @At("RETURN"), remap = false)
    private void xnetadditions$includeLogicSignalBounds(CallbackInfoReturnable<List<Rectangle>> cir) {
        if (xnetadditions$logicSignalVisible && xnetadditions$logicSignalPanel != null) {
            cir.getReturnValue().add(xnetadditions$logicSignalPanel.getBounds());
        }
    }

    @Inject(method = "drawGuiContainerBackgroundLayer", at = @At("HEAD"), remap = true)
    private void xnetadditions$updateLogicSignals(float partialTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (xnetadditions$logicSignalPanel == null || GuiController.fromServer_channels == null) {return;}
        if (xnetadditions$observedChannels != GuiController.fromServer_channels) {
            xnetadditions$observedChannels = GuiController.fromServer_channels;
            xnetadditions$controllerHasLogic = false;
            for (ChannelClientInfo channel : xnetadditions$observedChannels) {
                if (channel != null && "xnet.logic".equals(channel.getType().getID())) {
                    xnetadditions$controllerHasLogic = true;
                    break;
                }
            }
        }
        if (!xnetadditions$controllerHasLogic) {
            xnetadditions$activeSignalMask = -1;
            xnetadditions$renderedSignalMask = Integer.MIN_VALUE;
            xnetadditions$lastSignalRequestTick = Long.MIN_VALUE;
            xnetadditions$hideLogicSignalPanel();
            return;
        }
        xnetadditions$positionLogicSignalPanel();
        if (!xnetadditions$logicSignalVisible) {return;}
        xnetadditions$rebuildLogicSignalPanel();
        Minecraft mc = Minecraft.getMinecraft();
        TileEntityController controller = xnetadditions$getController();
        if (mc.world == null || controller == null) {return;}
        long tick = mc.world.getTotalWorldTime();
        if (xnetadditions$lastSignalRequestTick == Long.MIN_VALUE || tick < xnetadditions$lastSignalRequestTick
                || tick - xnetadditions$lastSignalRequestTick >= xnetadditions$POLL_TICKS) {
            xnetadditions$lastSignalRequestTick = tick;
            LogicSignalNetwork.CHANNEL.sendToServer(new LogicSignalNetwork.Request(controller.getPos()));
        }
    }

    @Override
    @Unique
    public void xnetadditions$setActiveSignalMask(BlockPos controllerPos, int activeMask) {
        TileEntityController controller = xnetadditions$getController();
        if (controller == null || !controller.getPos().equals(controllerPos)) {return;}
        int sanitized = activeMask & 0xffff;
        if (xnetadditions$activeSignalMask != sanitized) {
            xnetadditions$activeSignalMask = sanitized;
            xnetadditions$renderedSignalMask = Integer.MIN_VALUE;
        }
    }

    @Unique
    private TileEntityController xnetadditions$getController() {
        GenericTileEntity tile = ((GenericGuiContainerAccessor) this).xnetadditions$getTileEntity();
        return tile instanceof TileEntityController ? (TileEntityController) tile : null;
    }

    @Unique
    private void xnetadditions$positionLogicSignalPanel() {
        GuiController gui = (GuiController) (Object) this;
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        int width = Math.min(xnetadditions$PANEL_WIDTH, main.width);
        int x = main.x + (main.width - width) / 2;
        int y = main.y + main.height + 2;
        if (y + xnetadditions$PANEL_HEIGHT > gui.height) {
            xnetadditions$hideLogicSignalPanel();
            return;
        }
        Rectangle current = xnetadditions$logicSignalPanel.getBounds();
        if (current.x != x || current.y != y || current.width != width
                || current.height != xnetadditions$PANEL_HEIGHT) {
            xnetadditions$logicSignalPanel.setBounds(new Rectangle(x, y, width, xnetadditions$PANEL_HEIGHT));
        }
        xnetadditions$logicSignalVisible = true;
    }

    @Unique
    private void xnetadditions$hideLogicSignalPanel() {
        if (!xnetadditions$logicSignalVisible) {return;}
        xnetadditions$logicSignalVisible = false;
        xnetadditions$logicSignalPanel.setBounds(new Rectangle(0, 0, 0, 0));
    }

    @Unique
    private void xnetadditions$rebuildLogicSignalPanel() {
        if (!xnetadditions$logicSignalVisible || xnetadditions$renderedSignalMask == xnetadditions$activeSignalMask) {return;}
        xnetadditions$renderedSignalMask = xnetadditions$activeSignalMask;
        GuiController gui = (GuiController) (Object) this;
        Minecraft mc = Minecraft.getMinecraft();
        xnetadditions$logicSignalPanel.removeChildren();
        xnetadditions$logicSignalPanel.addChild(new Label(mc, gui).setText("Active:")
                .setTooltips("Controller-wide active logic signals")
                .setLayoutHint(new PositionalLayout.PositionalHint(5, 2, 38, 14)));
        if (xnetadditions$activeSignalMask < 0) {
            xnetadditions$logicSignalPanel.addChild(new Label(mc, gui).setText("...")
                    .setLayoutHint(new PositionalLayout.PositionalHint(43, 2, 24, 14)));
            return;
        }
        int x = 43;
        int shown = 0;
        for (Color color : Color.values()) {
            if (color == Color.OFF || (xnetadditions$activeSignalMask & (1 << color.ordinal())) == 0) {
                continue;
            }
            int argb = 0xff000000 | color.getColor();
            Panel swatch = new Panel(mc, gui).setLayout(new PositionalLayout())
                    .setFilledBackground(argb, argb).setFilledRectThickness(1)
                    .setTooltips(xnetadditions$formatColorName(color))
                    .setLayoutHint(new PositionalLayout.PositionalHint(x + shown * 11, 4, 9, 9));
            xnetadditions$logicSignalPanel.addChild(swatch);
            shown++;
        }
        if (shown == 0) {
            xnetadditions$logicSignalPanel.addChild(new Label(mc, gui).setText("None")
                    .setLayoutHint(new PositionalLayout.PositionalHint(43, 2, 32, 14)));
        }
    }

    @Unique
    private static String xnetadditions$formatColorName(Color color) {
        String name = color.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
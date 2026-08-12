package xnet.additions.powertools.client;

import mcjty.lib.gui.Window;
import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import xnet.additions.powertools.diagnostics.client.ControllerDiagnosticsPanel;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;

import javax.annotation.Nullable;
import java.awt.Rectangle;
import java.util.function.IntConsumer;

public final class PowerToolsWindow {

    private static final int MAX_WIDTH = 180;
    private static final int MIN_WIDTH = 100;
    private static final int GAP = 2;
    private static final int LAUNCHER_WIDTH = 40;
    private static final int LAUNCHER_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 18;

    private final GuiController gui;
    private final Panel root;
    private final Panel content;
    private final Window window;
    private final ControllerDiagnosticsPanel diagnostics;
    private boolean open;
    private boolean visible;
    private int layoutWidth = -1;
    private int layoutHeight = -1;

    public PowerToolsWindow(GuiController gui, TileEntityController controller, IntConsumer selectChannel) {
        this.gui = gui;
        Minecraft mc = Minecraft.getMinecraft();
        root = new Panel(mc, gui).setLayout(new PositionalLayout())
                .setFilledBackground(0xff303030, 0xff555555).setFilledRectThickness(1);
        content = new Panel(mc, gui).setLayout(new PositionalLayout());
        root.setBounds(new Rectangle(0, 0, 0, 0));
        window = new Window(gui, root);
        diagnostics = new ControllerDiagnosticsPanel(gui, controller, content, selectChannel);
        rebuild(LAUNCHER_WIDTH, LAUNCHER_HEIGHT);
    }

    public void register(WindowManager manager) {
        manager.addWindow(window);
    }

    public void update() {
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        if (main == null || main.y < 0 || main.y + main.height > gui.height) {
            hide();
            return;
        }
        int available = main.x - GAP;
        if (open && available < MIN_WIDTH) {open = false;}
        if (!open && available < LAUNCHER_WIDTH) {
            hide();
            return;
        }
        int width = open ? Math.min(MAX_WIDTH, available) : LAUNCHER_WIDTH;
        int height = open ? main.height : LAUNCHER_HEIGHT;
        int x = main.x - GAP - width;
        if (layoutWidth != width || layoutHeight != height) {rebuild(width, height);}
        Rectangle bounds = root.getBounds();
        if (bounds.x != x || bounds.y != main.y || bounds.width != width || bounds.height != height) {
            root.setBounds(new Rectangle(x, main.y, width, height));
        }
        visible = true;
        if (open) {diagnostics.update();}
    }

    @Nullable
    public Rectangle getVisibleBounds() {
        return visible ? root.getBounds() : null;
    }

    public void receive(DiagnosticsNetwork.Response response) {
        diagnostics.receive(response);
    }

    private void toggle() {
        if (open) {
            open = false;
            layoutWidth = -1;
            return;
        }
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        if (main == null || main.x - GAP < MIN_WIDTH) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player != null) {mc.player.sendStatusMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Not enough room to the left of the Controller GUI"), true);}
            return;
        }
        open = true;
        layoutWidth = -1;
        diagnostics.shown();
    }

    private void rebuild(int width, int height) {
        layoutWidth = width;
        layoutHeight = height;
        root.removeChildren();
        Minecraft mc = Minecraft.getMinecraft();
        if (!open) {
            root.addChild(new Button(mc, gui).setText("Tools").setTooltips("Open XNet Additions Power Tools")
                    .setLayoutHint(new PositionalLayout.PositionalHint(1, 1, Math.max(1, width - 2), Math.max(1, height - 2)))
                    .addButtonEvent(parent -> toggle()));
            return;
        }
        int closeX = width - 18;
        int diagnosticsX = Math.max(44, closeX - 40);
        root.addChild(new Label(mc, gui).setText("Power").setColor(0xffffe3a0)
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 3, Math.max(1, diagnosticsX - 6), 12)));
        ToggleButton diagnosticsButton = new ToggleButton(mc, gui).setCheckMarker(false).setText("Diag").setPressed(true)
                .setTooltips("Controller Diagnostics")
                .setLayoutHint(new PositionalLayout.PositionalHint(diagnosticsX, 2, Math.max(1, closeX - diagnosticsX - 2), 14))
                .addButtonEvent(parent -> ((ToggleButton) parent).setPressed(true));
        root.addChild(diagnosticsButton);
        root.addChild(new Button(mc, gui).setText("x").setTooltips("Close Power Tools")
                .setLayoutHint(new PositionalLayout.PositionalHint(closeX, 2, 16, 14))
                .addButtonEvent(parent -> toggle()));
        content.setLayoutHint(new PositionalLayout.PositionalHint(1, HEADER_HEIGHT, Math.max(1, width - 2), Math.max(1, height - HEADER_HEIGHT - 1)));
        root.addChild(content);
        diagnostics.resize(Math.max(1, width - 2), Math.max(1, height - HEADER_HEIGHT - 1));
    }

    private void hide() {
        if (!visible) {return;}
        visible = false;
        root.setBounds(new Rectangle(0, 0, 0, 0));
    }
}
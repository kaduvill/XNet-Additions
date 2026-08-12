package xnet.additions.powertools.client;

import mcjty.lib.gui.Window;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;

import java.awt.Rectangle;

public final class PowerToolsWindow {
    private static final int PANEL_WIDTH = 180;
    private static final int MIN_PANEL_WIDTH = 100;
    private static final int PANEL_GAP = 2;
    private static final int LAUNCHER_WIDTH = 40;
    private static final int LAUNCHER_HEIGHT = 18;

    private final GuiController gui;
    private final Panel panel;
    private final Window window;
    private final Button toggleButton;
    private boolean open;
    private boolean panelFits;
    private int panelWidth = PANEL_WIDTH;

    public PowerToolsWindow(GuiController gui, boolean open) {
        this.gui = gui;
        this.open = open;
        Minecraft mc = Minecraft.getMinecraft();
        panel = new Panel(mc, gui).setLayout(new PositionalLayout()).setFilledBackground(0xff3f3f3f, 0xff777777).setFilledRectThickness(1);
        panel.setBounds(new Rectangle(0, 0, 0, 0));
        toggleButton = new Button(mc, gui).addButtonEvent(parent -> toggle());
        window = new Window(gui, panel);
        rebuild();
        updateBounds();
    }

    public Window getWindow() {return window;}
    public Rectangle getBounds() {return panel.getBounds();}
    public boolean isOpen() {return open;}

    public void updateBounds() {
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        int availableWidth = Math.min(PANEL_WIDTH, Math.max(0, main.x - PANEL_GAP));
        boolean fits = availableWidth >= MIN_PANEL_WIDTH && main.y >= 0 && main.y + main.height <= gui.height;
        boolean layoutChanged = fits != panelFits || availableWidth != panelWidth;
        panelFits = fits;
        panelWidth = availableWidth;
        if (open && !panelFits) {
            open = false;
            layoutChanged = true;
        }
        if (layoutChanged) {rebuild();}

        int x = 0;
        int y = 0;
        int width = 0;
        int height = 0;
        if (open) {
            x = main.x - panelWidth - PANEL_GAP;
            y = main.y;
            width = panelWidth;
            height = main.height;
        } else if (main.x >= LAUNCHER_WIDTH + PANEL_GAP && main.y + 2 >= 0 && main.y + 2 + LAUNCHER_HEIGHT <= gui.height) {
            x = main.x - LAUNCHER_WIDTH - PANEL_GAP;
            y = main.y + 2;
            width = LAUNCHER_WIDTH;
            height = LAUNCHER_HEIGHT;
        }

        Rectangle bounds = panel.getBounds();
        if (bounds.x != x || bounds.y != y || bounds.width != width || bounds.height != height) {panel.setBounds(new Rectangle(x, y, width, height));}
    }

    private void toggle() {
        if (!open && !panelFits) {return;}
        open = !open;
        rebuild();
        updateBounds();
    }

    private void rebuild() {
        panel.removeChildren();
        if (!open) {
            toggleButton.setText("Tools").setEnabled(panelFits)
                    .setTooltips(panelFits ? "Open XNet Power Tools" : "Not enough horizontal room at this GUI scale")
                    .setLayoutHint(new PositionalLayout.PositionalHint(1, 1, LAUNCHER_WIDTH - 2, LAUNCHER_HEIGHT - 2));
            panel.addChild(toggleButton);
            return;
        }

        panel.addChild(new Label(Minecraft.getMinecraft(), gui).setText("Power Tools").setColor(0xffffe3a0)
                .setLayoutHint(new PositionalLayout.PositionalHint(5, 3, panelWidth - 29, 14)));
        toggleButton.setText("-").setEnabled(true).setTooltips("Close XNet Power Tools")
                .setLayoutHint(new PositionalLayout.PositionalHint(panelWidth - 18, 2, 16, 14));
        panel.addChild(toggleButton);
        panel.addChild(new Label(Minecraft.getMinecraft(), gui).setText("Select a tool")
                .setLayoutHint(new PositionalLayout.PositionalHint(5, 22, panelWidth - 10, 14)));
    }
}
package xnet.additions.powertools.client;

import mcjty.lib.gui.Window;
import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.xnet.api.channels.Color;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import xnet.additions.config.client.XNetAdditionsClientConfig;
import xnet.additions.powertools.diagnostics.client.ControllerDiagnosticsPanel;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;
import xnet.additions.powertools.health.client.ControllerHealthPanel;
import xnet.additions.powertools.health.network.HealthNetwork;
import xnet.additions.powertools.history.client.ConnectorHistory;
import xnet.additions.powertools.history.client.ConnectorHistoryPanel;
import xnet.additions.powertools.logic.client.LogicPanel;
import xnet.additions.powertools.logic.network.LogicSnapshotNetwork;
import xnet.additions.powertools.logicstatus.client.LogicSignalStatusReceiver;
import xnet.additions.powertools.probe.SideProbe;
import xnet.additions.powertools.probe.client.SideProbePanel;
import xnet.additions.powertools.probe.network.SideProbeNetwork;

import javax.annotation.Nullable;
import java.awt.Rectangle;
import java.util.function.IntConsumer;

public final class PowerToolsWindow {

    private static final int TAB_DIAGNOSTICS = 0;
    private static final int TAB_HEALTH = 1;
    private static final int TAB_LOGIC = 2;
    private static final int TAB_HISTORY = 3;
    private static final int TAB_PROBE = 4;
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
    private final ControllerHealthPanel health;
    private final LogicPanel logicPanel;
    private final ConnectorHistory history = new ConnectorHistory();
    private final ConnectorHistoryPanel historyPanel;
    private final SideProbePanel probePanel;
    private int tab = TAB_DIAGNOSTICS;
    private boolean open;
    private boolean visible;
    private boolean initialShowPending;
    private int layoutWidth = -1;
    private int layoutHeight = -1;

    public PowerToolsWindow(GuiController gui, TileEntityController controller, IntConsumer selectChannel, ControllerNavigator navigator) {
        this.gui = gui;
        Minecraft mc = Minecraft.getMinecraft();
        root = new Panel(mc, gui).setLayout(new PositionalLayout())
                .setFilledBackground(0xff303030, 0xff555555).setFilledRectThickness(1);
        content = new Panel(mc, gui).setLayout(new PositionalLayout());
        root.setBounds(new Rectangle(0, 0, 0, 0));
        window = new Window(gui, root);
        diagnostics = new ControllerDiagnosticsPanel(gui, controller, content, selectChannel);
        health = new ControllerHealthPanel(gui, controller, content, selectChannel, navigator);
        logicPanel = new LogicPanel(gui, controller, content, navigator, () -> gui instanceof LogicSignalStatusReceiver
                ? ((LogicSignalStatusReceiver) gui).xnetadditions$getActiveSignalMask() : -1);
        historyPanel = new ConnectorHistoryPanel(gui, content, history, navigator);
        probePanel = new SideProbePanel(gui, controller, content);
        String startupPanel = XNetAdditionsClientConfig.getLeftPanelOnOpen();
        String selectedPanel = XNetAdditionsClientConfig.LEFT_CLOSED.equals(startupPanel)
                || XNetAdditionsClientConfig.LEFT_LAST_USED.equals(startupPanel)
                ? XNetAdditionsClientConfig.getLastUsedLeftPanel() : startupPanel;
        if (XNetAdditionsClientConfig.LEFT_HEALTH.equals(selectedPanel)) {tab = TAB_HEALTH;}
        else if (XNetAdditionsClientConfig.LEFT_LOGIC.equals(selectedPanel)) {tab = TAB_LOGIC;}
        else if (XNetAdditionsClientConfig.LEFT_RECENT.equals(selectedPanel)) {tab = TAB_HISTORY;}
        else if (XNetAdditionsClientConfig.LEFT_SIDE_PROBE.equals(selectedPanel)) {tab = TAB_PROBE;}
        open = !XNetAdditionsClientConfig.LEFT_CLOSED.equals(startupPanel);
        initialShowPending = open;
        rebuild(LAUNCHER_WIDTH, LAUNCHER_HEIGHT);
    }

    public void register(WindowManager manager) {manager.addWindow(window);}

    public void update() {
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        if (main == null || main.y < 0 || main.y + main.height > gui.height) {
            hide();
            return;
        }
        int available = main.x - GAP;
        if (open && available < MIN_WIDTH) {
            if (tab == TAB_LOGIC) {logicPanel.cancelPendingNavigation();}
            open = false;
            initialShowPending = false;
        }
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
        if (open) {
            if (initialShowPending) {initialShowPending = false; shown();}
            if (tab == TAB_HISTORY) {historyPanel.update();}
            else if (tab == TAB_LOGIC) {logicPanel.update();}
            else if (tab == TAB_HEALTH) {health.update();}
            else if (tab == TAB_PROBE) {probePanel.update();}
            else {diagnostics.update();}
        }
    }

    @Nullable
    public Rectangle getVisibleBounds() {return visible ? root.getBounds() : null;}
    public void receive(DiagnosticsNetwork.Response response) {diagnostics.receive(response);}
    public void receive(HealthNetwork.Response response) {health.receive(response);}
    public void receive(LogicSnapshotNetwork.Response response) {logicPanel.receive(response);}
    public void receive(SideProbeNetwork.Response response) {probePanel.receive(response);}

    public void observe(SidedPos connector, int channel) {
        history.visit(connector, channel);
        if (open && tab == TAB_PROBE) {probePanel.observe(connector, channel);}
    }

    public void inspectLogicColor(Color color, boolean directSource) {
        logicPanel.selectColor(color, directSource);
        if (!open) {
            tab = TAB_LOGIC;
            rememberTab();
            toggle();
            if (!open) {logicPanel.cancelPendingNavigation();}
        } else if (tab != TAB_LOGIC) {
            selectTab(TAB_LOGIC);
        } else {
            logicPanel.shown();
        }
    }

    public void inspectSides(SidedPos target, int channel, SideProbe.Type type, EnumFacing configuredSide,
                             @Nullable SidedPos currentControllerTarget, int currentControllerChannel) {
        probePanel.focus(target, channel, type, configuredSide, currentControllerTarget, currentControllerChannel);
        if (!open) {
            tab = TAB_PROBE;
            rememberTab();
            toggle();
        } else if (tab != TAB_PROBE) {
            selectTab(TAB_PROBE);
        } else {
            probePanel.shown();
        }
    }

    private void toggle() {
        if (open) {
            if (tab == TAB_LOGIC) {logicPanel.cancelPendingNavigation();}
            open = false;
            initialShowPending = false;
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
        initialShowPending = false;
        layoutWidth = -1;
        shown();
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
        int tabWidth = Math.min(30, Math.max(1, (closeX - 8) / 5));
        int diagnosticsX = closeX - tabWidth * 5 - 8;
        int healthX = diagnosticsX + tabWidth + 2;
        int logicX = healthX + tabWidth + 2;
        int historyX = logicX + tabWidth + 2;
        int probeX = historyX + tabWidth + 2;

        if (diagnosticsX > 44) {
            root.addChild(new Label(mc, gui).setText("Power").setColor(0xffffe3a0)
                    .setLayoutHint(new PositionalLayout.PositionalHint(4, 3, diagnosticsX - 6, 12)));
        }

        String diagnosticsText = tabWidth >= 30 ? "Diag" : "D";
        String healthText = tabWidth >= 36 ? "Health" : "H";
        String logicText = tabWidth >= 32 ? "Logic" : "L";
        String historyText = tabWidth >= 30 ? "Hist" : "R";
        String probeText = tabWidth >= 30 ? "Side" : "S";

        root.addChild(new ToggleButton(mc, gui).setCheckMarker(false).setText(diagnosticsText).setPressed(tab == TAB_DIAGNOSTICS)
                .setTooltips("Controller Diagnostics")
                .setLayoutHint(new PositionalLayout.PositionalHint(diagnosticsX, 2, tabWidth, 14))
                .addButtonEvent(parent -> selectTab(TAB_DIAGNOSTICS)));
        root.addChild(new ToggleButton(mc, gui).setCheckMarker(false).setText(healthText).setPressed(tab == TAB_HEALTH)
                .setTooltips("Network configuration health")
                .setLayoutHint(new PositionalLayout.PositionalHint(healthX, 2, tabWidth, 14))
                .addButtonEvent(parent -> selectTab(TAB_HEALTH)));
        root.addChild(new ToggleButton(mc, gui).setCheckMarker(false).setText(logicText).setPressed(tab == TAB_LOGIC)
                .setTooltips("Logic signal sources and references")
                .setLayoutHint(new PositionalLayout.PositionalHint(logicX, 2, tabWidth, 14))
                .addButtonEvent(parent -> selectTab(TAB_LOGIC)));
        root.addChild(new ToggleButton(mc, gui).setCheckMarker(false).setText(historyText).setPressed(tab == TAB_HISTORY)
                .setTooltips("Recent connector locations")
                .setLayoutHint(new PositionalLayout.PositionalHint(historyX, 2, tabWidth, 14))
                .addButtonEvent(parent -> selectTab(TAB_HISTORY)));
        root.addChild(new ToggleButton(mc, gui).setCheckMarker(false).setText(probeText).setPressed(tab == TAB_PROBE)
                .setTooltips("Side Prober")
                .setLayoutHint(new PositionalLayout.PositionalHint(probeX, 2, tabWidth, 14))
                .addButtonEvent(parent -> selectTab(TAB_PROBE)));
        root.addChild(new Button(mc, gui).setText("x").setTooltips("Close Power Tools")
                .setLayoutHint(new PositionalLayout.PositionalHint(closeX, 2, 16, 14))
                .addButtonEvent(parent -> toggle()));

        content.setLayoutHint(new PositionalLayout.PositionalHint(1, HEADER_HEIGHT, Math.max(1, width - 2), Math.max(1, height - HEADER_HEIGHT - 1)));
        root.addChild(content);
        int contentWidth = Math.max(1, width - 2);
        int contentHeight = Math.max(1, height - HEADER_HEIGHT - 1);

        if (tab == TAB_HISTORY) {historyPanel.resize(contentWidth, contentHeight);}
        else if (tab == TAB_LOGIC) {logicPanel.resize(contentWidth, contentHeight);}
        else if (tab == TAB_HEALTH) {health.resize(contentWidth, contentHeight);}
        else if (tab == TAB_PROBE) {probePanel.resize(contentWidth, contentHeight);}
        else {diagnostics.resize(contentWidth, contentHeight);}
    }

    private void selectTab(int tab) {
        if (this.tab == tab) {rebuild(layoutWidth, layoutHeight); return;}
        if (this.tab == TAB_LOGIC) {logicPanel.cancelPendingNavigation();}
        this.tab = tab;
        initialShowPending = false;
        rememberTab();
        rebuild(layoutWidth, layoutHeight);
        shown();
    }

    private void shown() {
        if (tab == TAB_HISTORY) {historyPanel.shown();}
        else if (tab == TAB_LOGIC) {logicPanel.shown();}
        else if (tab == TAB_HEALTH) {health.shown();}
        else if (tab == TAB_PROBE) {probePanel.shown();}
        else {diagnostics.shown();}
    }

    private void rememberTab() {
        String panel = tab == TAB_HEALTH ? XNetAdditionsClientConfig.LEFT_HEALTH
                : tab == TAB_LOGIC ? XNetAdditionsClientConfig.LEFT_LOGIC
                : tab == TAB_HISTORY ? XNetAdditionsClientConfig.LEFT_RECENT
                : tab == TAB_PROBE ? XNetAdditionsClientConfig.LEFT_SIDE_PROBE
                : XNetAdditionsClientConfig.LEFT_DIAGNOSTICS;
        XNetAdditionsClientConfig.rememberLastLeftPanel(panel);
    }

    private void hide() {
        if (!visible) {return;}
        if (tab == TAB_LOGIC) {logicPanel.cancelPendingNavigation();}
        visible = false;
        root.setBounds(new Rectangle(0, 0, 0, 0));
    }
}
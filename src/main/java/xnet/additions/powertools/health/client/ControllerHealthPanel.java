package xnet.additions.powertools.health.client;

import mcjty.lib.base.StyleConfig;
import mcjty.lib.gui.events.DefaultSelectionEvent;
import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import net.minecraft.client.Minecraft;
import xnet.additions.powertools.client.ControllerNavigator;
import xnet.additions.powertools.health.HealthFinding;
import xnet.additions.powertools.health.network.HealthNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

public final class ControllerHealthPanel {
    private static int nextRequestId;
    private final GuiController gui;
    private final TileEntityController controller;
    private final Panel panel;
    private final IntConsumer selectChannel;
    private final ControllerNavigator navigator;
    private int width = 178;
    private int height = 217;
    private int requestId;
    private int revision;
    private int renderedRevision = Integer.MIN_VALUE;
    private boolean scanned;
    private boolean pending;
    private boolean hasResult;
    private String status = "";
    private List<HealthFinding> findings = Collections.emptyList();
    private List<ChannelClientInfo> observedChannels;

    public ControllerHealthPanel(GuiController gui, TileEntityController controller, Panel panel,
                                 IntConsumer selectChannel, ControllerNavigator navigator) {
        this.gui = gui;
        this.controller = controller;
        this.panel = panel;
        this.selectChannel = selectChannel;
        this.navigator = navigator;
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height) {return;}
        this.width = width;
        this.height = height;
        revision++;
    }

    public void shown() {
        observedChannels = GuiController.fromServer_channels;
        if (!scanned) {requestScan();}
        revision++;
    }

    public void update() {
        if (observedChannels != GuiController.fromServer_channels) {
            observedChannels = GuiController.fromServer_channels;
            revision++;
        }
        if (renderedRevision != revision) {rebuild();}
    }

    public void receive(HealthNetwork.Response response) {
        if (!matchesController(response) || response.getRequestId() != requestId) {return;}
        pending = false;
        if (response.getKind() == HealthNetwork.RESPONSE_RESULT) {
            findings = response.getFindings();
            hasResult = true;
            status = "";
        } else if (response.getKind() == HealthNetwork.RESPONSE_ERROR) {
            findings = Collections.emptyList();
            hasResult = false;
            status = response.getMessage();
        }
        revision++;
    }

    private void requestScan() {
        if (pending || controller.getWorld() == null) {return;}
        scanned = true;
        pending = true;
        hasResult = false;
        findings = Collections.emptyList();
        status = "";
        requestId = nextRequestId();
        try {
            HealthNetwork.CHANNEL.sendToServer(new HealthNetwork.Request(controller.getPos(), requestId));
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            pending = false;
            status = "Could not request Health scan";
        }
        revision++;
    }

    private boolean matchesController(HealthNetwork.Response response) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world != null && controller.getWorld() == mc.world
                && mc.world.provider.getDimension() == response.getDimension()
                && controller.getPos().equals(response.getControllerPos());
    }

    private void rebuild() {
        panel.removeChildren();
        int inner = Math.max(1, width - 8);
        label("Network Health", 4, 2, Math.max(1, inner - 58), 12, 0xffffe3a0);
        panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText("Refresh").setEnabled(!pending)
                .setTooltips("Scan this Controller network again")
                .setLayoutHint(new PositionalLayout.PositionalHint(Math.max(4, width - 58), 1, 54, 14))
                .addButtonEvent(parent -> requestScan()));

        if (pending) {
            label("Scanning...", 4, 19, inner, 11, StyleConfig.colorTextInListNormal);
            renderedRevision = revision;
            return;
        }

        if (!status.isEmpty()) {
            Label error = label(status, 4, 19, inner, 11, 0xffff8080);
            error.setTooltips(status);
            renderedRevision = revision;
            return;
        }

        if (!hasResult) {
            label("No scan yet", 4, 19, inner, 11, StyleConfig.colorTextInListNormal);
            renderedRevision = revision;
            return;
        }

        int errors = 0;
        int warnings = 0;
        for (HealthFinding finding : findings) {
            if (finding.getSeverity() == HealthFinding.Severity.ERROR) {errors++;}
            else {warnings++;}
        }

        label(errors + (errors == 1 ? " error" : " errors") + " · "
                        + warnings + (warnings == 1 ? " warning" : " warnings"),
                4, 18, inner, 11, 0xffdddddd);

        if (findings.isEmpty()) {
            label("No obvious issues found", 4, 33, inner, 11, StyleConfig.colorTextInListNormal);
            renderedRevision = revision;
            return;
        }

        List<HealthFinding> entries = new ArrayList<>(findings);
        WidgetList list = new WidgetList(Minecraft.getMinecraft(), gui)
                .setPropagateEventsToChildren(false)
                .setRowheight(26)
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 32, inner, Math.max(1, height - 35)));

        for (HealthFinding finding : entries) {
            list.addChild(createRow(finding));
        }

        list.addSelectionEvent(new DefaultSelectionEvent() {
            @Override
            public void select(Widget<?> parent, int index) {
                list.setSelected(-1);
                if (index >= 0 && index < entries.size()) {open(entries.get(index));}
            }
        });

        panel.addChild(list);
        renderedRevision = revision;
    }

    private Panel createRow(HealthFinding finding) {
        Minecraft mc = Minecraft.getMinecraft();
        String scope = finding.getChannel() < 0 ? "Controller" : "[" + (finding.getChannel() + 1) + "] " + channelType(finding.getChannel());
        int color = finding.getSeverity() == HealthFinding.Severity.ERROR ? 0xffff7070 : 0xffffd070;
        String marker = finding.getSeverity() == HealthFinding.Severity.ERROR ? "!! " : "! ";

        Panel row = new Panel(mc, gui) {
            @Override
            public Widget<?> getWidgetAtPosition(int x, int y) {
                return this;
            }
        }.setLayout(new PositionalLayout());

        if (finding.getChannel() >= 0) {
            row.setTooltips(finding.getMessage(), "Click to open");
        } else {
            row.setTooltips(finding.getMessage());
        }

        row.addChild(new Label(mc, gui).setText(marker + scope).setColor(color)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setLayoutHint(new PositionalLayout.PositionalHint(2, 1, Math.max(1, width - 14), 11)));
        row.addChild(new Label(mc, gui).setText(finding.getMessage()).setColor(StyleConfig.colorTextInListNormal)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(5, 0)
                .setLayoutHint(new PositionalLayout.PositionalHint(2, 13, Math.max(1, width - 14), 11)));
        return row;
    }

    private String channelType(int channel) {
        if (observedChannels == null || channel < 0 || channel >= observedChannels.size()) {return "Channel";}
        ChannelClientInfo info = observedChannels.get(channel);
        return info == null ? "Channel" : info.getType().getName();
    }

    private void open(HealthFinding finding) {
        if (finding.getChannel() < 0) {return;}
        if (finding.getConnector() != null) {
            navigator.xnetadditions$navigate(finding.getConnector(), finding.getChannel());
        } else {
            selectChannel.accept(finding.getChannel());
        }
    }

    private Label label(String text, int x, int y, int width, int height, int color) {
        Label label = new Label(Minecraft.getMinecraft(), gui).setText(text).setColor(color)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setLayoutHint(new PositionalLayout.PositionalHint(x, y, width, height));
        panel.addChild(label);
        return label;
    }

    private static int nextRequestId() {
        int id = ++nextRequestId;
        if (id == 0) {id = ++nextRequestId;}
        return id;
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof ThreadDeath) {throw (ThreadDeath) throwable;}
        if (throwable instanceof VirtualMachineError) {throw (VirtualMachineError) throwable;}
    }
}
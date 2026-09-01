package xnet.additions.powertools.health.client;

import mcjty.lib.base.StyleConfig;
import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.BlockPosTools;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.EnumFacing;
import xnet.additions.powertools.client.ControllerNavigator;
import xnet.additions.powertools.client.PowerToolsRow;
import xnet.additions.powertools.health.HealthFinding;
import xnet.additions.powertools.health.network.HealthNetwork;
import xnet.additions.powertools.probe.SideProbe;

import javax.annotation.Nullable;
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
    private HealthFinding selectedFinding;
    private WidgetList findingList;
    private List<ChannelClientInfo> observedChannels;
    private List<ConnectedBlockClientInfo> observedBlocks;

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
        observedBlocks = GuiController.fromServer_connectedBlocks;
        if (!scanned) {requestScan();}
        revision++;
    }

    public void update() {
        if (observedChannels != GuiController.fromServer_channels) {
            observedChannels = GuiController.fromServer_channels;
            revision++;
        }
        if (observedBlocks != GuiController.fromServer_connectedBlocks) {
            observedBlocks = GuiController.fromServer_connectedBlocks;
            revision++;
        }
        if (renderedRevision != revision) {rebuild();}
    }

    public void receive(HealthNetwork.Response response) {
        if (!matchesController(response) || response.getRequestId() != requestId) {return;}
        pending = false;
        selectedFinding = null;
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
        selectedFinding = null;
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
        findingList = null;
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
        int listWidth = Math.max(1, width - 8);
        findingList = PowerToolsRow.createList(gui)
                .setPropagateEventsToChildren(true)
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 32, listWidth, Math.max(1, height - 35)));

        int selected = -1;
        for (int i = 0; i < entries.size(); i++) {
            HealthFinding finding = entries.get(i);
            findingList.addChild(createRow(finding, listWidth));
            if (finding == selectedFinding) {selected = i;}
        }
        if (selected >= 0) {findingList.setSelected(selected);}
        else {selectedFinding = null;}

        panel.addChild(findingList);
        renderedRevision = revision;
    }

    private Panel createRow(HealthFinding finding, int rowWidth) {
        Minecraft mc = Minecraft.getMinecraft();
        ChannelClientInfo channel = findChannel(finding.getChannel());
        ConnectedBlockClientInfo block = findBlock(finding.getConnector());
        boolean error = finding.getSeverity() == HealthFinding.Severity.ERROR;
        int color = error ? 0xffff7070 : 0xffffd070;
        String severity = error ? "Error" : "Warning";

        List<String> tooltips = new ArrayList<>();
        tooltips.add(TextFormatting.GREEN + "Severity: " + (error ? TextFormatting.RED : TextFormatting.YELLOW) + severity);
        tooltips.add(TextFormatting.WHITE + finding.getMessage());

        if (finding.getChannel() >= 0) {
            String channelName = String.valueOf(finding.getChannel() + 1);
            if (channel != null) {
                channelName += ": " + (channel.getChannelName().isEmpty() ? channel.getType().getName() : channel.getChannelName());
            }
            tooltips.add(TextFormatting.GREEN + "Channel: " + TextFormatting.WHITE + channelName);
        }

        if (finding.getConnector() != null) {
            String target = block == null
                    ? BlockPosTools.toString(finding.getConnector().getPos())
                    : block.getName().isEmpty()
                    ? I18n.format(block.getBlockUnlocName()).trim()
                    : block.getName();
            tooltips.add(TextFormatting.GREEN + "Target: " + TextFormatting.WHITE + target);
        }

        if (finding.getChannel() >= 0) {
            tooltips.add(TextFormatting.GRAY + (finding.getConnector() == null ? "Click to open channel" : "Click to open connector"));
        }

        PowerToolsRow row = new PowerToolsRow(gui, rowWidth, finding.getMessage(),
                StyleConfig.colorTextInListNormal, tooltips.toArray(new String[0]));
        row.setRowAction(() -> {
            if (open(finding)) {
                selectedFinding = finding;
            } else {
                selectedFinding = null;
                if (findingList != null) {findingList.setSelected(-1);}
            }
        });
        row.addMetadata(new Label(mc, gui).setText(TextFormatting.BOLD + "\u26A0").setColor(color).setDesiredWidth(12));
        row.addBlock(block);
        row.addChannel(finding.getChannel(), channel);
        if (finding.getConnector() != null && finding.getProbeType() != null) {
            row.addAction("?", () -> {
                selectedFinding = finding;
                inspect(finding, channel);
            }, "Inspect sides");
        }
        return row;
    }

    @Nullable
    private ChannelClientInfo findChannel(int channel) {
        if (observedChannels == null || channel < 0 || channel >= observedChannels.size()) {return null;}
        return observedChannels.get(channel);
    }

    @Nullable
    private ConnectedBlockClientInfo findBlock(@Nullable SidedPos connector) {
        if (observedBlocks == null || connector == null) {return null;}
        for (ConnectedBlockClientInfo block : observedBlocks) {
            if (connector.equals(block.getPos())) {return block;}
        }
        return null;
    }

    private boolean open(HealthFinding finding) {
        if (finding.getChannel() < 0) {return false;}
        if (finding.getConnector() != null) {
            return navigator.xnetadditions$navigate(finding.getConnector(), finding.getChannel());
        }
        if (findChannel(finding.getChannel()) == null) {return false;}
        selectChannel.accept(finding.getChannel());
        return true;
    }
    public void observeControllerSelection(SidedPos connector, int channel) {
        if (selectedFinding == null) {return;}
        SidedPos selectedConnector = selectedFinding.getConnector();
        boolean sameChannel = selectedFinding.getChannel() == channel;
        boolean sameConnector = selectedConnector == null || selectedConnector.equals(connector);
        if (sameChannel && sameConnector) {return;}
        selectedFinding = null;
        if (findingList != null) {findingList.setSelected(-1);}
    }
    private void inspect(HealthFinding finding, @Nullable ChannelClientInfo channel) {
        SidedPos connector = finding.getConnector();
        SideProbe.Type probeType = finding.getProbeType();
        if (connector == null || probeType == null) {return;}
        EnumFacing configuredSide = connector.getSide();
        if (probeType != SideProbe.Type.EU && channel != null) {
            for (ConnectorClientInfo info : channel.getConnectors().values()) {
                if (connector.equals(info.getPos()) && info.getConnectorSettings() instanceof AbstractConnectorSettings) {
                    configuredSide = ((AbstractConnectorSettings) info.getConnectorSettings()).getFacing();
                    break;
                }
            }
        }
        navigator.xnetadditions$navigate(connector, finding.getChannel());
        navigator.xnetadditions$inspectSides(connector, finding.getChannel(), probeType, configuredSide);
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
package xnet.additions.powertools.probe.client;

import mcjty.lib.base.StyleConfig;
import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.ChoiceLabel;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.BlockPosTools;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.apiimpl.logic.LogicConnectorSettings;
import mcjty.xnet.apiimpl.logic.Sensor;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.TextFormatting;
import xnet.additions.powertools.client.PowerToolsRow;
import xnet.additions.powertools.probe.SideProbe;
import xnet.additions.powertools.probe.network.SideProbeNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class SideProbePanel {
    private static int nextRequestId;
    private final GuiController gui;
    private final TileEntityController controller;
    private final Panel panel;
    private int width = 178;
    private int height = 217;
    private int requestId;
    private int revision;
    private int renderedRevision = Integer.MIN_VALUE;
    private boolean pending;
    private String status = "";
    @Nullable private SidedPos target;
    @Nullable private SideProbe.Type focusedType;
    @Nullable private EnumFacing configuredSide;
    @Nullable private SideProbe.Snapshot snapshot;
    @Nullable private SidedPos observedControllerTarget;
    private int observedControllerChannel = -1;
    private int selectedChannel = -1;
    private List<ConnectedBlockClientInfo> observedBlocks;
    private List<ChannelClientInfo> observedChannels;

    public SideProbePanel(GuiController gui, TileEntityController controller, Panel panel) {
        this.gui = gui;
        this.controller = controller;
        this.panel = panel;
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height) {return;}
        this.width = width;
        this.height = height;
        revision++;
    }

    public void shown() {
        if (target != null && snapshot == null && !pending) {requestProbe();}
        revision++;
    }

    public void observe(SidedPos target, int channel) {
        if (target == null || target.equals(observedControllerTarget) && channel == observedControllerChannel) {return;}
        observedControllerTarget = target;
        observedControllerChannel = channel;
        selectTarget(target, channel, inferType(target, channel), findConfiguredSide(target, channel));
    }
    public void observeTarget(SidedPos target) {
        if (target == null || target.equals(this.target)) {return;}

        selectTarget(target, selectedChannel, focusedType,
                selectedChannel >= 0 ? findConfiguredSide(target, selectedChannel) : null);
    }

    public void focus(SidedPos target, int channel, SideProbe.Type type, EnumFacing configuredSide,
                      @Nullable SidedPos currentControllerTarget, int currentControllerChannel) {
        observedControllerTarget = currentControllerTarget;
        observedControllerChannel = currentControllerChannel;
        selectTarget(target, channel, type, configuredSide);
    }

    public void update() {
        if (GuiController.fromServer_connectedBlocks != null && observedBlocks != GuiController.fromServer_connectedBlocks) {
            observedBlocks = GuiController.fromServer_connectedBlocks;
            if (target != null && findBlock(target) == null) {clearTarget();}
            revision++;
        }
        if (GuiController.fromServer_channels != null && observedChannels != GuiController.fromServer_channels) {
            observedChannels = GuiController.fromServer_channels;
            if (target != null && selectedChannel >= 0) {configuredSide = findConfiguredSide(target, selectedChannel);}
            revision++;
        }
        if (renderedRevision != revision) {rebuild();}
    }

    public void receive(SideProbeNetwork.Response response) {
        if (!matchesController(response) || response.getRequestId() != requestId || target == null || !target.equals(response.getTarget())) {return;}
        pending = false;
        if (response.getKind() == SideProbeNetwork.RESPONSE_RESULT) {
            snapshot = response.getSnapshot();
            status = "";
        } else if (response.getKind() == SideProbeNetwork.RESPONSE_ERROR) {
            snapshot = null;
            status = response.getMessage();
        }
        revision++;
    }

    private void selectTarget(SidedPos target, int channel, @Nullable SideProbe.Type type, @Nullable EnumFacing configuredSide) {
        boolean changed = !target.equals(this.target);
        this.target = target;
        this.selectedChannel = channel;
        this.focusedType = type == null ? SideProbe.Type.ENERGY : type;
        this.configuredSide = configuredSide;
        if (changed) {
            snapshot = null;
            status = "";
            requestProbe();
        } else if (snapshot == null && !pending) {
            requestProbe();
        }
        revision++;
    }

    private void clearTarget() {
        target = null;
        selectedChannel = -1;
        configuredSide = null;
        snapshot = null;
        pending = false;
        status = "";
        requestId = nextRequestId();
    }

    private void requestProbe() {
        if (pending || target == null || controller.getWorld() == null) {return;}
        pending = true;
        snapshot = null;
        status = "";
        requestId = nextRequestId();
        try {
            SideProbeNetwork.CHANNEL.sendToServer(new SideProbeNetwork.Request(controller.getPos(), target, requestId));
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            pending = false;
            status = "Could not request Side Probe";
        }
        revision++;
    }

    private boolean matchesController(SideProbeNetwork.Response response) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world != null && controller.getWorld() == mc.world
                && mc.world.provider.getDimension() == response.getDimension()
                && controller.getPos().equals(response.getControllerPos());
    }

    private void rebuild() {
        panel.removeChildren();
        int inner = Math.max(1, width - 8);
        label("Side Prober", 4, 2, Math.max(1, inner - 58), 12, 0xffffe3a0);
        panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText("Refresh").setEnabled(target != null && !pending)
                .setTooltips("Probe this target again")
                .setLayoutHint(new PositionalLayout.PositionalHint(Math.max(4, width - 58), 1, 54, 14))
                .addButtonEvent(parent -> requestProbe()));

        if (target == null) {
            label("Click a channel cell", 4, 21, inner, 11, StyleConfig.colorTextInListNormal);
            label("in the Controller list.", 4, 33, inner, 11, StyleConfig.colorTextInListNormal);
            renderedRevision = revision;
            return;
        }

        addTargetRow(inner);
        if (pending) {
            label("Probing...", 4, 51, inner, 11, StyleConfig.colorTextInListNormal);
            renderedRevision = revision;
            return;
        }
        if (!status.isEmpty()) {
            Label error = label(status, 4, 51, inner, 11, 0xffff8080);
            error.setTooltips(status);
            renderedRevision = revision;
            return;
        }
        if (snapshot == null || snapshot.getTypes().isEmpty()) {
            label("No probe data", 4, 51, inner, 11, StyleConfig.colorTextInListNormal);
            renderedRevision = revision;
            return;
        }

        List<SideProbe.Type> types = snapshot.getTypes();
        if (focusedType == null || !snapshot.has(focusedType)) {
            focusedType = snapshot.has(SideProbe.Type.ENERGY) ? SideProbe.Type.ENERGY : types.get(0);
        }

        String[] choices = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {choices[i] = types.get(i).getName();}
        ChoiceLabel choice = new ChoiceLabel(Minecraft.getMinecraft(), gui).addChoices(choices).setChoice(focusedType.getName())
                .setTooltips("Probe type")
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 49, inner, 14));
        choice.addChoiceEvent((parent, value) -> {
            for (SideProbe.Type type : types) {
                if (type.getName().equals(value)) {
                    focusedType = type;
                    revision++;
                    return;
                }
            }
        });
        panel.addChild(choice);

        int listWidth = Math.max(1, width - 8);
        WidgetList list = new WidgetList(Minecraft.getMinecraft(), gui).setRowheight(20).setNoSelectionMode(true)
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 66, listWidth, Math.max(1, height - 69)));
        if (focusedType.isSided()) {
            for (EnumFacing side : EnumFacing.VALUES) {
                boolean configured = side == configuredSide;
                list.addChild(createResultRow(focusedType, side, snapshot.get(focusedType, side), listWidth, configured));
                if (configured) {list.addHilightedRow(side.ordinal());}
            }
        } else {
            list.addChild(createResultRow(focusedType, null, snapshot.getAllSides(focusedType), listWidth, false));
        }
        panel.addChild(list);
        renderedRevision = revision;
    }

    private void addTargetRow(int rowWidth) {
        ConnectedBlockClientInfo block = findBlock(target);
        String name = targetName(block, target);
        PowerToolsRow row = new PowerToolsRow(gui, rowWidth, name, StyleConfig.colorTextInListNormal,
                TextFormatting.GREEN + "Target: " + TextFormatting.WHITE + name,
                TextFormatting.GREEN + "Position: " + TextFormatting.WHITE + BlockPosTools.toString(target.getPos()),
                TextFormatting.GREEN + "Connector face: " + TextFormatting.WHITE + target.getSide().getName().toUpperCase());
        row.addBlock(block);
        row.addMetadata(new Label(Minecraft.getMinecraft(), gui).setText("Target").setColor(0xff80ff80).setDesiredWidth(34));
        String side = (configuredSide == null ? target.getSide() : configuredSide).getName().substring(0, 1).toUpperCase();
        row.addMetadata(new Label(Minecraft.getMinecraft(), gui).setText(configuredSide == null ? side : "Cfg " + side)
                .setColor(configuredSide == null ? StyleConfig.colorTextInListNormal : 0xffffd070).setDesiredWidth(configuredSide == null ? 10 : 28)
                .setTooltips(configuredSide == null ? "Connector face: " + target.getSide().getName().toUpperCase()
                        : "Configured side: " + configuredSide.getName().toUpperCase()));
        row.setLayoutHint(new PositionalLayout.PositionalHint(4, 18, rowWidth, PowerToolsRow.HEIGHT));
        panel.addChild(row);
    }

    private Panel createResultRow(SideProbe.Type type, @Nullable EnumFacing side, SideProbe.Fact fact, int rowWidth, boolean configured) {
        Panel row = new Panel(Minecraft.getMinecraft(), gui).setLayout(new PositionalLayout()).setDesiredHeight(20);
        boolean narrow = rowWidth < 130;
        String direction = side == null ? (narrow ? "ALL:" : "ALL SIDES:")
                : (narrow ? side.getName().substring(0, 1).toUpperCase() : side.getName().toUpperCase());
        if (configured) {direction = "> " + direction;}
        String value = formatFact(type, fact, narrow);
        String[] tooltips = factTooltips(type, side, fact);
        int directionWidth = narrow ? 25 : 57;
        row.addChild(new Label(Minecraft.getMinecraft(), gui).setText(direction).setColor(configured ? 0xffffd070 : StyleConfig.colorTextInListNormal)
                .setTooltips(tooltips).setLayoutHint(new PositionalLayout.PositionalHint(3, 4, directionWidth, 12)));
        int valueColor = fact.failed() ? 0xffff7070 : (fact.hasAccess() || configured ? 0xffdddddd : StyleConfig.colorTextInListNormal);
        row.addChild(new Label(Minecraft.getMinecraft(), gui).setText(value).setDynamic(true)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setColor(valueColor)
                .setTooltips(tooltips).setLayoutHint(new PositionalLayout.PositionalHint(directionWidth + 4, 4, Math.max(1, rowWidth - directionWidth - 10), 12)));
        return row;
    }

    private String formatFact(SideProbe.Type type, SideProbe.Fact fact, boolean narrow) {
        if (fact.failed()) {return "Probe failed";}
        if (!fact.hasAccess()) {return "No access";}
        switch (type) {
            case ITEM:
                return fact.getCount() < 0 ? "Access" : fact.getCount() + (fact.getCount() == 1 ? " slot" : " slots");
            case FLUID: {
                String access = fact.canInput() && fact.canOutput() ? "Fill + Drain" : fact.canInput() ? "Fill" : fact.canOutput() ? "Drain" : "Access";
                if (narrow && fact.canInput() && fact.canOutput()) {access = "F+D";}
                if (narrow) {return access + " · " + fact.getCount() + "t";}
                return access + " · " + fact.getCount() + (fact.getCount() == 1 ? " tank" : " tanks");
            }
            case ENERGY:
                return fact.canInput() && fact.canOutput() ? "Both" : fact.canInput() ? "Insert" : fact.canOutput() ? "Extract" : "Access";
            case ADVANCED_ENERGY:
                if (fact.canInput() && fact.canOutput()) {return narrow ? "Both role" : "Both targets";}
                if (fact.canInput()) {return narrow ? "Insert role" : "Insert target";}
                if (fact.canOutput()) {return narrow ? "Extract role" : "Extract target";}
                return "Access";
            case GAS:
                if (fact.canInput() && fact.canOutput()) {return "Both now";}
                if (fact.canInput()) {return "Receive now";}
                if (fact.canOutput()) {return "Draw now";}
                return "Access";
            case EU:
                if (fact.canInput() && fact.canOutput()) {return narrow ? "Src + Sink" : "Source + Sink";}
                return fact.canInput() ? "Sink" : fact.canOutput() ? "Source" : "Access";
            case MANA:
            case ESSENTIA:
            default:
                return "Access";
        }
    }

    private String[] factTooltips(SideProbe.Type type, @Nullable EnumFacing side, SideProbe.Fact fact) {
        List<String> tooltips = new ArrayList<>();
        tooltips.add(TextFormatting.GREEN + "Type: " + TextFormatting.WHITE + type.getName());
        tooltips.add(TextFormatting.GREEN + "Side: " + TextFormatting.WHITE + (side == null ? "All sides" : side.getName().toUpperCase()));
        if (fact.failed()) {
            tooltips.add(TextFormatting.RED + "The target threw while being inspected");
            return tooltips.toArray(new String[0]);
        }
        if (!fact.hasAccess()) {
            tooltips.add(TextFormatting.GRAY + "No compatible access was exposed");
            return tooltips.toArray(new String[0]);
        }
        switch (type) {
            case ITEM:
                if (fact.getCount() >= 0) {tooltips.add(TextFormatting.GREEN + "Exposed slots: " + TextFormatting.WHITE + fact.getCount());}
                tooltips.add(TextFormatting.GRAY + "Insert/extract permission can depend on item, slot and state");
                break;
            case FLUID:
                tooltips.add(TextFormatting.GREEN + "Exposed tanks: " + TextFormatting.WHITE + fact.getCount());
                tooltips.add(TextFormatting.GREEN + "Tank properties: " + TextFormatting.WHITE + formatFact(type, fact, false).split(" · ")[0]);
                break;
            case ENERGY:
                tooltips.add(TextFormatting.GRAY + "Forge Energy canReceive/canExtract");
                break;
            case ADVANCED_ENERGY:
                tooltips.add(TextFormatting.GRAY + "Recognized special Flux role; live network state not tested");
                tooltips.add(TextFormatting.GRAY + "Connector buffer excluded");
                break;
            case GAS:
                tooltips.add(TextFormatting.GREEN + "Reported tanks: " + TextFormatting.WHITE + fact.getCount());
                tooltips.add(TextFormatting.GRAY + "Receive/draw is current-state and gas-type dependent");
                break;
            case MANA:
            case ESSENTIA:
                tooltips.add(TextFormatting.GRAY + "This integration ignores configured side");
                break;
            case EU:
                tooltips.add(TextFormatting.GRAY + "XNet EU source/sink lookup is target-level");
                tooltips.add(TextFormatting.GRAY + "EU facing override is disabled");
                break;
        }
        return tooltips.toArray(new String[0]);
    }

    @Nullable
    private SideProbe.Type inferType(SidedPos target, int channelIndex) {
        ChannelClientInfo channel = findChannel(channelIndex);
        if (channel == null) {return SideProbe.Type.ENERGY;}
        SideProbe.Type type = SideProbe.Type.fromChannelId(channel.getType().getID());
        if (type != null) {return type;}
        if (!"xnet.logic".equals(channel.getType().getID())) {return SideProbe.Type.ENERGY;}
        ConnectorClientInfo connector = findConnector(target, channel);
        if (connector == null || !(connector.getConnectorSettings() instanceof LogicConnectorSettings)) {return SideProbe.Type.ENERGY;}
        for (Sensor sensor : ((LogicConnectorSettings) connector.getConnectorSettings()).getSensors()) {
            if (sensor.getSensorMode() == Sensor.SensorMode.ITEM) {return SideProbe.Type.ITEM;}
            if (sensor.getSensorMode() == Sensor.SensorMode.FLUID) {return SideProbe.Type.FLUID;}
            if (sensor.getSensorMode() == Sensor.SensorMode.ENERGY) {return SideProbe.Type.ENERGY;}
        }
        return SideProbe.Type.ENERGY;
    }

    @Nullable
    private EnumFacing findConfiguredSide(SidedPos target, int channelIndex) {
        ChannelClientInfo channel = findChannel(channelIndex);
        if (channel != null && "ic2.eu".equals(channel.getType().getID())) {return target.getSide();}
        ConnectorClientInfo connector = findConnector(target, channel);
        IConnectorSettings settings = connector == null ? null : connector.getConnectorSettings();
        return settings instanceof AbstractConnectorSettings ? ((AbstractConnectorSettings) settings).getFacing() : target.getSide();
    }

    @Nullable
    private ChannelClientInfo findChannel(int channel) {
        List<ChannelClientInfo> channels = GuiController.fromServer_channels;
        return channels == null || channel < 0 || channel >= channels.size() ? null : channels.get(channel);
    }

    @Nullable
    private ConnectorClientInfo findConnector(SidedPos target, @Nullable ChannelClientInfo channel) {
        if (channel == null) {return null;}
        for (ConnectorClientInfo connector : channel.getConnectors().values()) {
            if (target.equals(connector.getPos())) {return connector;}
        }
        return null;
    }

    @Nullable
    private ConnectedBlockClientInfo findBlock(@Nullable SidedPos target) {
        if (observedBlocks == null || target == null) {return null;}
        for (ConnectedBlockClientInfo block : observedBlocks) {
            if (target.equals(block.getPos())) {return block;}
        }
        return null;
    }

    private String targetName(@Nullable ConnectedBlockClientInfo block, SidedPos target) {
        if (block == null) {return BlockPosTools.toString(target.getPos());}
        return block.getName().isEmpty() ? I18n.format(block.getBlockUnlocName()).trim() : block.getName();
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
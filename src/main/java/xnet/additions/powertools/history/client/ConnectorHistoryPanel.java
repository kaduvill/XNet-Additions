package xnet.additions.powertools.history.client;

import mcjty.lib.base.StyleConfig;
import mcjty.lib.gui.events.DefaultSelectionEvent;
import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.HorizontalLayout;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.BlockRender;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.BlockPosTools;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import xnet.additions.powertools.client.ControllerNavigator;

import java.util.ArrayList;
import java.util.List;

public final class ConnectorHistoryPanel {
    private final GuiController gui;
    private final Panel panel;
    private final ConnectorHistory history;
    private final ControllerNavigator navigator;
    private int width = 178;
    private int height = 217;
    private int renderedHistoryRevision = Integer.MIN_VALUE;
    private List<ChannelClientInfo> observedChannels;
    private List<ConnectedBlockClientInfo> observedBlocks;

    public ConnectorHistoryPanel(GuiController gui, Panel panel, ConnectorHistory history, ControllerNavigator navigator) {
        this.gui = gui;
        this.panel = panel;
        this.history = history;
        this.navigator = navigator;
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height) {return;}
        this.width = width;
        this.height = height;
        renderedHistoryRevision = Integer.MIN_VALUE;
    }

    public void shown() {renderedHistoryRevision = Integer.MIN_VALUE;}

    public void update() {
        boolean changed = renderedHistoryRevision != history.getRevision();
        if (GuiController.fromServer_channels != null && observedChannels != GuiController.fromServer_channels) {
            observedChannels = GuiController.fromServer_channels;
            changed = true;
        }
        if (GuiController.fromServer_connectedBlocks != null && observedBlocks != GuiController.fromServer_connectedBlocks) {
            observedBlocks = GuiController.fromServer_connectedBlocks;
            changed = true;
        }
        if (changed) {rebuild();}
    }

    private void rebuild() {
        panel.removeChildren();
        label("Recent connectors", 4, 2, Math.max(1, width - 8), 12, 0xffffe3a0);

        ConnectorHistory.Entry current = history.getCurrent();
        List<ConnectorHistory.Entry> entries = new ArrayList<>(ConnectorHistory.LIMIT + 1);
        if (current != null) {entries.add(current);}
        entries.addAll(history.getPrevious());

        WidgetList historyList = new WidgetList(Minecraft.getMinecraft(), gui)
                .setPropagateEventsToChildren(false)
                .setRowheight(16)
                .setEnabled(navigator.xnetadditions$isNavigationReady())
                .setLayoutHint(new PositionalLayout.PositionalHint(
                        4, 18, Math.max(1, width - 8), Math.max(1, height - 21)));

        for (ConnectorHistory.Entry entry : entries) {historyList.addChild(createRow(entry));}
        if (current != null) {historyList.setSelected(0);}
        historyList.addSelectionEvent(new DefaultSelectionEvent() {
            @Override
            public void select(Widget<?> parent, int index) {
                if (index > 0 && index < entries.size()) {open(entries.get(index));}
            }
        });

        panel.addChild(historyList);

        if (entries.isEmpty()) {
            label("Open a connector", 7, 20, Math.max(1, width - 14), 11,
                    StyleConfig.colorTextInListNormal);
            label("to start history.", 7, 32, Math.max(1, width - 14), 11,
                    StyleConfig.colorTextInListNormal);
        }

        renderedHistoryRevision = history.getRevision();
    }

    private Panel createRow(ConnectorHistory.Entry entry) {
        Minecraft mc = Minecraft.getMinecraft();
        ConnectedBlockClientInfo block = findBlock(entry);
        ChannelClientInfo channel = findChannel(entry);
        ConnectorClientInfo connector = findConnector(entry, channel);

        String target = block == null
                ? BlockPosTools.toString(entry.getConnector().getPos())
                : block.getName().isEmpty()
                ? I18n.format(block.getBlockUnlocName()).trim()
                : block.getName();

        String channelName = TextFormatting.GREEN + "Channel " + (entry.getChannel() + 1);
        if (channel != null) {
            channelName += TextFormatting.WHITE + ": " +
                    (channel.getChannelName().isEmpty() ? channel.getType().getName() : channel.getChannelName());
        }

        // The children are presentational; WidgetList owns hover and click for the row.
        Panel row = new Panel(mc, gui) {
            @Override
            public Widget<?> getWidgetAtPosition(int x, int y) {return this;}
        }.setLayout(new HorizontalLayout().setHorizontalMargin(0).setSpacing(0))
                .setTooltips(TextFormatting.WHITE + target, channelName);

        BlockRender blockIcon = new BlockRender(mc, gui);
        if (block != null) {blockIcon.setRenderItem(block.getConnectedBlock());}
        row.addChild(blockIcon);

        Button mode = new Button(mc, gui).setText("").setDesiredWidth(14);
        if (connector != null) {
            IndicatorIcon icon = connector.getConnectorSettings().getIndicatorIcon();
            if (icon != null) {
                mode.setImage(icon.getImage(), icon.getU(), icon.getV(), icon.getIw(), icon.getIh());
            }

            String indicator = connector.getConnectorSettings().getIndicator();
            if (indicator != null) {mode.setText(indicator);}
        }
        row.addChild(mode);

        String number = String.valueOf(entry.getChannel() + 1);
        ToggleButton channelButton = new ToggleButton(mc, gui)
                .setCheckMarker(false)
                .setText(number)
                .setDesiredWidth(14);

        if (channel != null) {
            IndicatorIcon icon = channel.getChannelSettings().getIndicatorIcon();
            if (icon != null) {
                channelButton.setImage(
                        icon.getImage(), icon.getU(), icon.getV(), icon.getIw(), icon.getIh());
            }

            String indicator = channel.getChannelSettings().getIndicator();
            if (indicator != null) {channelButton.setText(indicator + number);}
        }
        row.addChild(channelButton);

        row.addChild(new Label(mc, gui)
                .setText(target)
                .setDynamic(true)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setTextOffset(2, 0)
                .setColor(StyleConfig.colorTextInListNormal));
        return row;
    }

    private void open(ConnectorHistory.Entry entry) {
        if (!navigator.xnetadditions$isNavigationReady()) {return;}
        if (!navigator.xnetadditions$navigate(entry.getConnector(), entry.getChannel())) {
            history.remove(entry);
        }
    }

    private ConnectedBlockClientInfo findBlock(ConnectorHistory.Entry entry) {
        if (observedBlocks == null) {return null;}
        for (ConnectedBlockClientInfo block : observedBlocks) {
            if (entry.getConnector().equals(block.getPos())) {
                return block;
            }
        }
        return null;
    }

    private ChannelClientInfo findChannel(ConnectorHistory.Entry entry) {
        if (observedChannels == null || entry.getChannel() >= observedChannels.size()) {
            return null;
        }
        return observedChannels.get(entry.getChannel());
    }

    private ConnectorClientInfo findConnector(
            ConnectorHistory.Entry entry, ChannelClientInfo channel) {
        if (channel == null) {return null;}
        for (ConnectorClientInfo connector : channel.getConnectors().values()) {
            if (entry.getConnector().equals(connector.getPos())) {
                return connector;
            }
        }
        return null;
    }

    private Label label(String text, int x, int y, int width, int height, int color) {
        Label label = new Label(Minecraft.getMinecraft(), gui)
                .setText(text)
                .setColor(color)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setLayoutHint(new PositionalLayout.PositionalHint(x, y, width, height));
        panel.addChild(label);
        return label;
    }
}
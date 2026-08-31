package xnet.additions.powertools.diagnostics.client;

import mcjty.lib.base.StyleConfig;
import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import net.minecraft.client.Minecraft;
import xnet.additions.powertools.diagnostics.ControllerDiagnostics;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.BlockPosTools;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import xnet.additions.powertools.client.ControllerNavigator;
import xnet.additions.powertools.client.PowerToolsRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class ControllerDiagnosticsPanel {

    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_CHANNEL = 1;
    private static final int PAGE_PEAK = 2;
    private static final int PAGE_TIMING = 3;
    private final ControllerNavigator navigator;
    private int selectedTiming;
    private List<ConnectedBlockClientInfo> observedBlocks;
    private static int nextRequestId;
    private final GuiController gui;
    private final TileEntityController controller;
    private final Panel panel;
    private final IntConsumer selectChannel;
    private boolean snapshotPending;
    private boolean profilePending;
    private boolean profiling;
    private int page;
    private int selectedChannel = -1;
    private int snapshotRequestId;
    private int profileRequestId;
    private int progress;
    private int revision;
    private int renderedRevision = Integer.MIN_VALUE;
    private int width = 178;
    private int height = 217;
    private String status = "";
    private List<ChannelClientInfo> observedChannels;
    private ControllerDiagnostics.Snapshot snapshot;
    private ControllerDiagnostics.Result currentResult;
    private ControllerDiagnostics.Result previousResult;


    private static final class TimingConnector {
        private final ConnectorClientInfo connector;
        private final ConnectedBlockClientInfo block;
        private final int timing;
        private final String mode;
        private final String target;

        private TimingConnector(ConnectorClientInfo connector, ConnectedBlockClientInfo block,
                                int timing, String mode, String target) {
            this.connector = connector;
            this.block = block;
            this.timing = timing;
            this.mode = mode;
            this.target = target;
        }
    }
    public ControllerDiagnosticsPanel(GuiController gui, TileEntityController controller, Panel panel,
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
        restoreProfile();
        observedChannels = GuiController.fromServer_channels;
        observedBlocks = GuiController.fromServer_connectedBlocks;
        requestSnapshot();
        revision++;
    }

    public void update() {
        if (observedChannels != GuiController.fromServer_channels) {
            observedChannels = GuiController.fromServer_channels;
            requestSnapshot();
            revision++;
        }

        if (observedBlocks != GuiController.fromServer_connectedBlocks) {
            observedBlocks = GuiController.fromServer_connectedBlocks;
            revision++;
        }

        if (renderedRevision != revision) {rebuild();}
    }

    public void receive(DiagnosticsNetwork.Response response) {
        if (!matchesController(response)) {return;}
        if (response.getKind() == DiagnosticsNetwork.RESPONSE_SNAPSHOT) {
            if (response.getRequestId() != snapshotRequestId) {return;}

            snapshotPending = false;
            snapshot = response.getSnapshot();
            restoreProfile();

            boolean selectedChannelPresent = selectedChannel >= 0
                    && selectedChannel < ControllerDiagnostics.CHANNELS
                    && snapshot.present[selectedChannel];

            if (!selectedChannelPresent) {
                if (page == PAGE_CHANNEL || page == PAGE_TIMING) {
                    page = PAGE_OVERVIEW;
                }
                selectedTiming = 0;
            } else if (selectedTiming != 0 && timingCount(selectedChannel, selectedTiming) == 0) {
                selectedTiming = 0;
            }

            revision++;
            return;
        }
        if (response.getKind() == DiagnosticsNetwork.RESPONSE_ERROR && response.getRequestId() == snapshotRequestId) {
            snapshotPending = false;
            status = response.getMessage();
            revision++;
            return;
        }
        restoreProfile();
        if (response.getRequestId() != profileRequestId) {return;}
        if (response.getKind() == DiagnosticsNetwork.RESPONSE_RESULT) {requestSnapshot();}
        else if (response.getKind() != DiagnosticsNetwork.RESPONSE_STARTED
                && response.getKind() != DiagnosticsNetwork.RESPONSE_PROGRESS
                && response.getKind() != DiagnosticsNetwork.RESPONSE_BUSY
                && response.getKind() != DiagnosticsNetwork.RESPONSE_ERROR) {return;}
        revision++;
    }

    private void requestSnapshot() {
        if (GuiController.fromServer_channels == null || controller.getWorld() == null) {return;}
        snapshotRequestId = nextRequestId();
        snapshotPending = true;
        if (!send(new DiagnosticsNetwork.Request(DiagnosticsNetwork.SNAPSHOT, controller.getPos(), snapshotRequestId),
                "Could not request Controller snapshot")) {snapshotPending = false;}
    }

    private void startProfile() {
        if (profilePending || profiling || controller.getWorld() == null) {return;}
        profileRequestId = nextRequestId();
        ControllerDiagnosticsSessionStore.begin(controller, profileRequestId);
        restoreProfile();
        revision++;
        if (!send(new DiagnosticsNetwork.Request(DiagnosticsNetwork.START_PROFILE, controller.getPos(), profileRequestId),
                "Could not start Controller profiler")) {
            ControllerDiagnosticsSessionStore.failed(controller, profileRequestId, "Could not start Controller profiler");
            restoreProfile();
        }
    }

    private void restoreProfile() {
        ControllerDiagnosticsSessionStore.Session session = ControllerDiagnosticsSessionStore.get(controller);
        if (session == null) {
            profilePending = false;
            profiling = false;
            profileRequestId = 0;
            progress = 0;
            status = "";
            currentResult = null;
            previousResult = null;
            return;
        }
        profilePending = session.pending;
        profiling = session.profiling;
        profileRequestId = session.requestId;
        progress = session.progress;
        status = session.status;
        currentResult = session.currentResult;
        previousResult = session.previousResult;
    }

    private boolean send(DiagnosticsNetwork.Request request, String failure) {
        try {
            DiagnosticsNetwork.CHANNEL.sendToServer(request);
            return true;
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            status = failure;
            revision++;
            return false;
        }
    }

    private boolean matchesController(DiagnosticsNetwork.Response response) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world != null && controller.getWorld() == mc.world
                && mc.world.provider.getDimension() == response.getDimension()
                && controller.getPos().equals(response.getControllerPos());
    }

    private void rebuild() {
        panel.removeChildren();

        if (page == PAGE_CHANNEL) {buildChannelPage();}
        else if (page == PAGE_TIMING) {buildTimingPage();}
        else if (page == PAGE_PEAK) {buildPeakPage();}
        else {buildOverview();}

        renderedRevision = revision;
    }

    private void buildOverview() {
        boolean compact = compact();
        int inner = innerWidth();
        label(compact ? "Controller" : "Controller Diagnostics", 4, 2, inner, 11, 0xffffe3a0);
        String summary = snapshot == null ? "Waiting for snapshot..."
                : compact ? snapshot.presentChannels + "/8 ch  " + snapshot.enabledChannels + " on  " + snapshot.configuredConnectors + " cfg"
                : snapshot.presentChannels + "/8 ch · " + snapshot.enabledChannels + " on · "
                + snapshot.configuredConnectors + " cfg · " + snapshot.advancedConnectors + " adv";
        label(summary, 4, 14, inner, 11, 0xffdddddd);
        String profileText = profilePending ? "Starting..."
                : profiling ? (compact ? progress + " / " + ControllerDiagnostics.PROFILE_TICKS : "Profiling... " + progress + " / " + ControllerDiagnostics.PROFILE_TICKS)
                : "Profile 1200t";
        panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText(profileText)
                .setEnabled(!profilePending && !profiling).setTooltips("Measure this Controller server-side for 1200 ticks")
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 27, inner, 15))
                .addButtonEvent(parent -> startProfile()));
        Label statusLabel = label(getStatusLine(), 4, 44, inner, 11, 0xffbbbbbb);
        if (!getStatusLine().isEmpty()) {statusLabel.setTooltips(getStatusLine());}
        ControllerDiagnostics.Result result = currentResult;
        label("Total  " + (result == null ? "—" : formatNanos(result.totalNanos, compact)), 4, 56, inner, 11, 0xffffffff);
        label("Avg/t  " + (result == null ? "—" : formatNanos(result.totalNanos / Math.max(1, result.samples), compact)), 4, 68, inner, 11, 0xffffffff);
        Button peak = new Button(Minecraft.getMinecraft(), gui)
                .setText("Peak  " + (result == null ? "—" : formatNanos(result.peakNanos, compact)) + (result == null ? "" : "  >"))
                .setEnabled(result != null).setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(2, -1)
                .setLayoutHint(new PositionalLayout.PositionalHint(4, 80, inner, 11));
        if (result != null) {peak.addButtonEvent(parent -> setPage(PAGE_PEAK, -1));}
        panel.addChild(peak);
        long core = result == null ? 0L : result.getCoreNanos();
        label("Core  " + (result == null ? "—" : formatNanos(core, compact)
                + (compact ? "" : "  " + percent(core, result.totalNanos))), 4, 93, inner, 11, 0xffffffff);
        label("Channels", 4, 107, inner, 11, 0xffffe3a0);
        if (snapshot == null) {return;}
        int row = 0;
        int dominant = dominantChannel(result);
        for (int channel = 0; channel < ControllerDiagnostics.CHANNELS; channel++) {
            if (!snapshot.present[channel]) {continue;}
            long time = result == null ? 0L : result.channelTotals[channel];
            String text = (channel + 1) + " " + typeName(channel, compact);
            if (result != null) {text += "  " + formatNanos(time, compact) + (compact ? "" : "  " + percent(time, result.totalNanos));}
            text += "  >";
            final int selected = channel;
            panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText(text)
                    .setColor(channel == dominant ? 0xff705000 : StyleConfig.colorTextNormal)
                    .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(2, -1)
                    .setTooltips("Open " + snapshot.typeNames[channel] + " diagnostics")
                    .setLayoutHint(new PositionalLayout.PositionalHint(4, 119 + row * 12, inner, 11))
                    .addButtonEvent(parent -> openChannel(selected)));
            row++;
        }
    }

    private void buildChannelPage() {
        int channel = selectedChannel;
        if (snapshot == null || channel < 0 || channel >= ControllerDiagnostics.CHANNELS || !snapshot.present[channel]) {
            page = PAGE_OVERVIEW;
            buildOverview();
            return;
        }
        boolean compact = compact();
        int inner = innerWidth();
        addNavigation(typeName(channel, compact), PAGE_OVERVIEW);
        label("Channel " + (channel + 1) + (snapshot.enabled[channel] ? "" : " · Disabled"), 4, 16, inner, 11, 0xffdddddd);
        label("PROFILE", 4, 29, inner, 11, 0xffffe3a0);
        ControllerDiagnostics.Result result = currentResult;
        long total = result == null ? 0L : result.channelTotals[channel];
        label("Total  " + (result == null ? "—" : formatNanos(total, compact)
                + (compact ? "" : "  " + percent(total, result.totalNanos))), 4, 42, inner, 11, 0xffffffff);
        label("Peak  " + (result == null ? "—" : formatNanos(result.channelPeaks[channel], compact)), 4, 54, inner, 11, 0xffffffff);
        label("Calls  " + (result == null ? "—" : result.channelCalls[channel] + " / " + result.samples), 4, 66, inner, 11, 0xffffffff);
        label("CONNECTIONS", 4, 82, inner, 11, 0xffffe3a0);
        boolean logic = "xnet.logic".equals(snapshot.typeIds[channel]);
        int y = 95;
        label((logic ? "Sensors  " : compact ? "Extract  " : "Local extract  ") + snapshot.extractors[channel], 4, y, inner, 11, 0xffffffff);
        y += 12;
        label((logic ? "Outputs  " : compact ? "Insert  " : "Local insert  ") + snapshot.consumers[channel], 4, y, inner, 11, 0xffffffff);
        y += 12;
        if (snapshot.routedConsumers[channel] > 0) {
            label((logic ? "Routed outputs  " : compact ? "Routed  " : "Routed insert  ") + snapshot.routedConsumers[channel], 4, y, inner, 11, 0xffdddddd);
            y += 12;
        }
        label("Advanced  " + snapshot.advanced[channel], 4, y, inner, 11, 0xffffffff);
        y += 16;
        label("SCHEDULE", 4, y, inner, 11, 0xffffe3a0);
        y += 13;
        Label operations = label("Operations/1200  " + snapshot.scheduledOperations[channel], 4, y, inner, 11, 0xffffffff);
        operations.setTooltips("Scheduled operations pr cycle");
        y += 12;
        label("Max same tick  " + snapshot.maxSameTick[channel], 4, y, inner, 11, 0xffffffff);
        y += 12;
        if (snapshot.adaptive[channel]) {
            label("Adaptive  Active", 4, y, inner, 11, 0xffffffff);
            y += 12;
        }
        String timingRange = formatTimingRange(channel);
        boolean hasTiming = !timingRange.isEmpty();

        Panel timingRow = new Panel(Minecraft.getMinecraft(), gui).setLayout(new PositionalLayout());
        timingRow.setLayoutHint(new PositionalLayout.PositionalHint(4, y, inner, 11));

        Button timings = new Button(Minecraft.getMinecraft(), gui).setText(hasTiming ? ">" : "")
                .setEnabled(hasTiming).setHorizontalAlignment(HorizontalAlignment.ALIGN_RIGHT).setTextOffset(-2, -1)
                .setLayoutHint(new PositionalLayout.PositionalHint(0, 0, inner, 11));
        if (hasTiming) {
            timings.setTooltips(timingTooltip(channel));
            timings.addButtonEvent(parent -> setPage(PAGE_TIMING, channel));
        }
        timingRow.addChild(timings);

        timingRow.addChild(new Label(Minecraft.getMinecraft(), gui).setText("Timing").setEnabled(hasTiming)
                .setColor(StyleConfig.colorTextNormal).setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setLayoutHint(new PositionalLayout.PositionalHint(2, 0, 40, 11)));

        timingRow.addChild(new Label(Minecraft.getMinecraft(), gui).setText(hasTiming ? timingRange : "—").setEnabled(hasTiming)
                .setColor(StyleConfig.colorTextNormal).setHorizontalAlignment(HorizontalAlignment.ALIGN_CENTER)
                .setLayoutHint(new PositionalLayout.PositionalHint(42, 0, Math.max(1, inner - 56), 11)));

        panel.addChild(timingRow);
    }
    private void buildTimingPage() {
        int channel = selectedChannel;

        if (snapshot == null
                || channel < 0
                || channel >= ControllerDiagnostics.CHANNELS
                || !snapshot.present[channel]) {
            page = PAGE_OVERVIEW;
            buildOverview();
            return;
        }

        addNavigation("Channel " + (channel + 1) + " · Timing", PAGE_CHANNEL);

        int nextY = addTimingButtons(channel, 18);
        int localCount = timingCount(snapshot.localTimingCounts[channel], selectedTiming);
        int routedCount = timingCount(snapshot.routedTimingCounts[channel], selectedTiming);
        boolean routedUnknown = snapshot.routedConsumers[channel] < 0
                && ControllerDiagnostics.hasRoutedTiming(snapshot.typeIds[channel]);

        if (observedChannels == null
                || observedBlocks == null
                || channel >= observedChannels.size()
                || observedChannels.get(channel) == null) {
            label("Refreshing connectors...", 7, nextY + 2,
                    Math.max(1, width - 14), 11, StyleConfig.colorTextInListNormal);
            return;
        }

        ChannelClientInfo channelInfo = observedChannels.get(channel);
        List<TimingConnector> entries = new ArrayList<>();

        for (ConnectorClientInfo connector : channelInfo.getConnectors().values()) {
            ConnectedBlockClientInfo block = findBlock(connector);
            if (block == null) {continue;}

            int timing = ControllerDiagnostics.getScheduledTiming(
                    snapshot.typeIds[channel], connector.getConnectorSettings(), false);

            if (timing <= 0 || selectedTiming != 0 && timing != selectedTiming) {continue;}

            entries.add(new TimingConnector(
                    connector,
                    block,
                    timing,
                    ControllerDiagnostics.getModeLabel(
                            snapshot.typeIds[channel], connector.getConnectorSettings()),
                    targetName(block)
            ));
        }

        entries.sort((a, b) -> {
            if (selectedTiming == 0 && a.timing != b.timing) {
                return Integer.compare(a.timing, b.timing);
            }

            int name = a.target.compareToIgnoreCase(b.target);
            return name != 0
                    ? name
                    : a.connector.getPos().compareTo(b.connector.getPos());
        });

        int unavailable = Math.max(0, localCount - entries.size());
        boolean refreshing = entries.size() > localCount;

        if (routedCount > 0 || unavailable > 0 || refreshing) {
            String context = entries.size() + " local"
                    + (unavailable > 0 ? " · " + unavailable + " unavailable" : "")
                    + (routedCount > 0 ? " · " + routedCount + " routed" : "")
                    + (refreshing ? " · refreshing" : "");

            label(context, 4, nextY, innerWidth(), 11, 0xffbbbbbb)
                    .setTooltips("Only local connectors currently available in this Controller can be opened");
            nextY += 12;
        } else if (routedUnknown) {
            label("Routed timing not cached", 4, nextY, innerWidth(), 11, 0xff999999)
                    .setTooltips("Diagnostics does not build routing caches; routed timing appears after normal channel use creates one");
            nextY += 12;
        }

        int listWidth = Math.max(1, width - 8);
        WidgetList list = PowerToolsRow.createList(gui)
                .setPropagateEventsToChildren(true)
                .setInvisibleSelection(true)
                .setEnabled(navigator.xnetadditions$isNavigationReady())
                .setLayoutHint(new PositionalLayout.PositionalHint(
                        4, nextY, listWidth, Math.max(1, height - nextY - 3)));

        for (TimingConnector entry : entries) {
            list.addChild(createTimingRow(entry, listWidth));
        }

        panel.addChild(list);

        if (entries.isEmpty()) {
            String empty = routedCount > 0 && unavailable == 0
                    ? "Routed only — open its Controller"
                    : "No navigable local connectors";

            label(empty, 7, nextY + 2,
                    Math.max(1, width - 14), 11, StyleConfig.colorTextInListNormal);
        }
    }
    private void buildPeakPage() {
        boolean compact = compact();
        int inner = innerWidth();
        addNavigation("Peak Tick", PAGE_OVERVIEW);
        ControllerDiagnostics.Result result = currentResult;
        if (result == null) {
            label("No profile result", 4, 20, inner, 11, 0xffffffff);
            return;
        }
        label("Tick " + result.peakSample + " / " + result.samples, 4, 18, inner, 11, 0xffdddddd);
        label("Total  " + formatNanos(result.peakNanos, compact), 4, 31, inner, 11, 0xffffffff);
        label("Core  " + formatNanos(result.getPeakCoreNanos(), compact), 4, 43, inner, 11, 0xffffffff);
        label("Channels", 4, 57, inner, 11, 0xffffe3a0);
        if (snapshot == null) {return;}
        int row = 0;
        for (int channel = 0; channel < ControllerDiagnostics.CHANNELS; channel++) {
            if (!snapshot.present[channel]) {continue;}
            String text = (channel + 1) + " " + typeName(channel, compact) + "  "
                    + formatNanos(result.peakChannels[channel], compact)
                    + (compact ? "" : "  " + percent(result.peakChannels[channel], result.peakNanos)) + "  >";
            final int selected = channel;
            panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText(text)
                    .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(2, -1)
                    .setTooltips("Open " + snapshot.typeNames[channel] + " diagnostics")
                    .setLayoutHint(new PositionalLayout.PositionalHint(4, 69 + row * 12, inner, 11))
                    .addButtonEvent(parent -> openChannel(selected)));
            row++;
        }
    }

    private String formatTimingRange(int channel) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < ControllerDiagnostics.TIMINGS.length; i++) {
            int count = snapshot.localTimingCounts[channel][i] + snapshot.routedTimingCounts[channel][i];
            if (count <= 0) {continue;}
            if (first < 0) {first = ControllerDiagnostics.TIMINGS[i];}
            last = ControllerDiagnostics.TIMINGS[i];
        }
        if (first < 0) {return "";}
        return first == last ? first + "t" : first + "t - " + last + "t";
    }
    private int addTimingButtons(int channel, int y) {
        int x = 4;
        int maxX = Math.max(4, width - 4);

        x = addTimingButton(channel, 0, "All", x, y, maxX);

        for (int i = 0; i < ControllerDiagnostics.TIMINGS.length; i++) {
            int count = snapshot.localTimingCounts[channel][i]
                    + snapshot.routedTimingCounts[channel][i];

            if (count == 0) {continue;}

            String text = ControllerDiagnostics.TIMINGS[i] + "t ×" + count;
            int buttonWidth = Math.min(innerWidth(),
                    Math.max(28, Minecraft.getMinecraft().fontRenderer.getStringWidth(text) + 8));

            if (x > 4 && x + buttonWidth > maxX) {
                x = 4;
                y += 16;
            }

            x = addTimingButton(
                    channel, ControllerDiagnostics.TIMINGS[i], text, x, y, maxX);
        }

        return y + 16;
    }

    private int addTimingButton(int channel, int timing, String text,
                                int x, int y, int maxX) {
        int buttonWidth = Math.min(innerWidth(),
                Math.max(28, Minecraft.getMinecraft().fontRenderer.getStringWidth(text) + 8));

        int local = timingCount(snapshot.localTimingCounts[channel], timing);
        int routed = timingCount(snapshot.routedTimingCounts[channel], timing);
        String tooltip = routed > 0
                ? local + " local · " + routed + " routed"
                : local + " local";

        ToggleButton button = new ToggleButton(Minecraft.getMinecraft(), gui)
                .setCheckMarker(false)
                .setText(text)
                .setPressed(selectedTiming == timing)
                .setTooltips(tooltip)
                .setLayoutHint(new PositionalLayout.PositionalHint(
                        x, y, Math.min(buttonWidth, maxX - x), 14));

        button.addButtonEvent(parent -> {
            selectedTiming = timing;
            revision++;
        });

        panel.addChild(button);
        return x + buttonWidth + 2;
    }

    private Panel createTimingRow(TimingConnector entry, int rowWidth) {
        Minecraft mc = Minecraft.getMinecraft();
        String blockName = I18n.format(entry.block.getBlockUnlocName()).trim();
        String position = BlockPosTools.toString(entry.connector.getPos().getPos());
        String detail = selectedTiming == 0 ? entry.timing + "t" : "";

        PowerToolsRow row = new PowerToolsRow(
                gui,
                rowWidth,
                detail,
                StyleConfig.colorTextInListNormal,
                TextFormatting.GREEN + "Connector: " + TextFormatting.WHITE + entry.target,
                TextFormatting.GREEN + "Block: " + TextFormatting.WHITE + blockName,
                TextFormatting.GREEN + "Mode: " + TextFormatting.WHITE + entry.mode,
                TextFormatting.GREEN + "Timing: " + TextFormatting.WHITE + entry.timing + " ticks",
                TextFormatting.GREEN + "Position: " + TextFormatting.WHITE + position,
                TextFormatting.GRAY + "Click to open connector settings"
        );

        row.setRowAction(() -> openTimingConnector(entry));
        row.addBlock(entry.block);

        Button icon = new Button(mc, gui).setText("").setDesiredWidth(14);
        IndicatorIcon indicator = entry.connector.getConnectorSettings().getIndicatorIcon();

        if (indicator != null) {
            icon.setImage(
                    indicator.getImage(),
                    indicator.getU(),
                    indicator.getV(),
                    indicator.getIw(),
                    indicator.getIh()
            );
        }

        row.addMetadata(icon);
        row.addMetadata(new Label(mc, gui)
                .setText(entry.target)
                .setDynamic(true)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setColor(StyleConfig.colorTextInListNormal));

        return row;
    }

    private void openTimingConnector(TimingConnector entry) {
        if (navigator.xnetadditions$isNavigationReady()
                && navigator.xnetadditions$navigate(
                entry.connector.getPos(), selectedChannel)) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            mc.player.sendStatusMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Connector is no longer available in this Controller"), true);
        }
    }
    private void addNavigation(String title, int backPage) {
        panel.addChild(new Button(Minecraft.getMinecraft(), gui)
                .setText("<")
                .setTooltips(backPage == PAGE_CHANNEL
                        ? "Back to Channel diagnostics"
                        : "Back to Controller overview")
                .setLayoutHint(new PositionalLayout.PositionalHint(3, 1, 16, 14))
                .addButtonEvent(parent -> setPage(backPage, selectedChannel)));

        panel.addChild(new Button(Minecraft.getMinecraft(), gui)
                .setText("^")
                .setTooltips("Controller Diagnostics overview")
                .setLayoutHint(new PositionalLayout.PositionalHint(21, 1, 16, 14))
                .addButtonEvent(parent -> setPage(PAGE_OVERVIEW, selectedChannel)));

        label(title, 41, 2, Math.max(1, width - 45), 12, 0xffffe3a0);
    }

    private Label label(String text, int x, int y, int width, int height, int color) {
        Label label = new Label(Minecraft.getMinecraft(), gui).setText(text).setColor(color)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT)
                .setLayoutHint(new PositionalLayout.PositionalHint(x, y, width, height));
        panel.addChild(label);
        return label;
    }

    private void openChannel(int channel) {
        if (selectedChannel != channel) {selectedTiming = 0;}
        setPage(PAGE_CHANNEL, channel);
        selectChannel.accept(channel);
    }

    private void setPage(int page, int channel) {
        this.page = page;
        selectedChannel = channel;
        revision++;
    }

    private int timingCount(int channel, int timing) {
        return timingCount(snapshot.localTimingCounts[channel], timing)
                + timingCount(snapshot.routedTimingCounts[channel], timing);
    }

    private static int timingCount(int[] counts, int timing) {
        int count = 0;

        for (int i = 0; i < ControllerDiagnostics.TIMINGS.length; i++) {
            if (timing == 0 || timing == ControllerDiagnostics.TIMINGS[i]) {
                count += counts[i];
            }
        }
        return count;
    }

    private String[] timingTooltip(int channel) {
        List<String> lines = new ArrayList<>();
        lines.add(TextFormatting.YELLOW + "Scheduled timing");

        for (int i = 0; i < ControllerDiagnostics.TIMINGS.length; i++) {
            int local = snapshot.localTimingCounts[channel][i];
            int routed = snapshot.routedTimingCounts[channel][i];
            int total = local + routed;
            if (total <= 0) {continue;}

            String line = TextFormatting.WHITE + Integer.toString(ControllerDiagnostics.TIMINGS[i]) + "t ×" + total;
            if (routed > 0) {line += TextFormatting.GRAY + " · " + local + " local / " + routed + " routed";}
            lines.add(line);
        }

        if (snapshot.routedConsumers[channel] < 0 && ControllerDiagnostics.hasRoutedTiming(snapshot.typeIds[channel])) {
            lines.add(TextFormatting.GRAY + "Routed timing not cached");
        }

        lines.add(TextFormatting.GRAY + "Click to inspect connectors");
        return lines.toArray(new String[lines.size()]);
    }

    private ConnectedBlockClientInfo findBlock(ConnectorClientInfo connector) {
        if (observedBlocks == null) {return null;}
        for (ConnectedBlockClientInfo block : observedBlocks) {
            if (connector.getPos().equals(block.getPos())) {return block;}
        }
        return null;
    }

    private static String targetName(ConnectedBlockClientInfo block) {
        return block.getName().isEmpty()
                ? I18n.format(block.getBlockUnlocName()).trim()
                : block.getName();
    }

    private String getStatusLine() {
        if (profilePending || profiling || !status.isEmpty()) {return status;}
        if (currentResult != null && previousResult != null && previousResult.totalNanos > 0L) {
            double change = (currentResult.totalNanos - previousResult.totalNanos) * 100.0D / previousResult.totalNanos;
            return compact() ? String.format(Locale.ROOT, "Previous %+.1f%%", change)
                    : "Previous " + formatNanos(previousResult.totalNanos, false) + " · " + String.format(Locale.ROOT, "%+.1f%%", change);
        }
        if (currentResult != null) {return "Profile complete";}
        return snapshotPending ? "Refreshing structure..." : "";
    }

    private int dominantChannel(ControllerDiagnostics.Result result) {
        if (result == null) {return -1;}
        int dominant = -1;
        long max = 0L;
        for (int i = 0; i < ControllerDiagnostics.CHANNELS; i++) {
            if (result.channelTotals[i] > max) {max = result.channelTotals[i]; dominant = i;}
        }
        return dominant;
    }

    private String typeName(int channel, boolean compact) {
        if (!compact) {return snapshot.typeNames[channel];}
        switch (snapshot.typeIds[channel]) {
            case "xnet.item": return "Item";
            case "xnet.fluid": return "Fluid";
            case "xnet.logic": return "Logic";
            case "xnet.energy": return "Energy";
            case "advanced.energy": return "AdvE";
            case "mekanism.gas": return "Gas";
            case "botania.mana": return "Mana";
            case "tc.essentia": return "Ess";
            case "ic2.eu": return "EU";
            default: return snapshot.typeNames[channel];
        }
    }

    private boolean compact() {return width < 150;}
    private int innerWidth() {return Math.max(1, width - 8);}

    private static String formatNanos(long nanos, boolean compact) {
        return String.format(Locale.ROOT, compact ? "%.3fms" : "%.3f ms", nanos / 1_000_000.0D);
    }

    private static String percent(long part, long total) {
        return total <= 0L ? "0%" : String.format(Locale.ROOT, "%.1f%%", part * 100.0D / total);
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
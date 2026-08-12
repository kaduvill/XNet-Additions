package xnet.additions.powertools.diagnostics.client;

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

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class ControllerDiagnosticsPanel {

    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_CHANNEL = 1;
    private static final int PAGE_PEAK = 2;
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

    public ControllerDiagnosticsPanel(GuiController gui, TileEntityController controller, Panel panel, IntConsumer selectChannel) {
        this.gui = gui;
        this.controller = controller;
        this.panel = panel;
        this.selectChannel = selectChannel;
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height) {return;}
        this.width = width;
        this.height = height;
        revision++;
    }

    public void shown() {
        observedChannels = GuiController.fromServer_channels;
        requestSnapshot();
        revision++;
    }

    public void update() {
        if (observedChannels != GuiController.fromServer_channels) {
            observedChannels = GuiController.fromServer_channels;
            requestSnapshot();
        }
        if (renderedRevision != revision) {rebuild();}
    }

    public void receive(DiagnosticsNetwork.Response response) {
        if (!matchesController(response)) {return;}
        if (response.getKind() == DiagnosticsNetwork.RESPONSE_SNAPSHOT) {
            if (response.getRequestId() != snapshotRequestId) {return;}
            snapshotPending = false;
            snapshot = response.getSnapshot();
            if (page == PAGE_CHANNEL && (selectedChannel < 0 || !snapshot.present[selectedChannel])) {
                page = PAGE_OVERVIEW;
            }
            revision++;
            return;
        }
        if (response.getRequestId() != profileRequestId) {
            if (response.getKind() == DiagnosticsNetwork.RESPONSE_ERROR
                    && response.getRequestId() == snapshotRequestId) {
                snapshotPending = false;
                status = response.getMessage();
                revision++;
            }
            return;
        }
        switch (response.getKind()) {
            case DiagnosticsNetwork.RESPONSE_STARTED:
                profilePending = false;
                profiling = true;
                progress = 0;
                status = "Server profiling active";
                break;
            case DiagnosticsNetwork.RESPONSE_PROGRESS:
                profilePending = false;
                profiling = true;
                progress = response.getSamples();
                break;
            case DiagnosticsNetwork.RESPONSE_RESULT:
                profilePending = false;
                profiling = false;
                progress = ControllerDiagnostics.PROFILE_TICKS;
                previousResult = currentResult;
                currentResult = response.getResult();
                status = "";
                requestSnapshot();
                break;
            case DiagnosticsNetwork.RESPONSE_BUSY:
            case DiagnosticsNetwork.RESPONSE_ERROR:
                profilePending = false;
                profiling = false;
                status = response.getMessage();
                break;
            default: return;
        }
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
        profilePending = true;
        progress = 0;
        status = "Starting server profiler...";
        revision++;
        if (!send(new DiagnosticsNetwork.Request(DiagnosticsNetwork.START_PROFILE, controller.getPos(), profileRequestId),
                "Could not start Controller profiler")) {profilePending = false;}
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
                    .setColor(channel == dominant ? 0xffffe080 : 0xffffffff)
                    .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(2, -1)
                    .setTooltips("Open " + snapshot.typeNames[channel] + " diagnostics", "Also selects native channel " + (channel + 1))
                    .setLayoutHint(new PositionalLayout.PositionalHint(4, 119 + row * 12, inner, 11))
                    .addButtonEvent(parent -> openChannel(selected)));
            row++;
        }
    }

    private void buildChannelPage() {
        int channel = selectedChannel;
        if (snapshot == null || channel < 0 || !snapshot.present[channel]) {
            page = PAGE_OVERVIEW;
            buildOverview();
            return;
        }
        boolean compact = compact();
        int inner = innerWidth();
        addNavigation(typeName(channel, compact));
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
        label((logic ? "Sensors  " : compact ? "Extract  " : "Local extract  ") + snapshot.extractors[channel], 4, 95, inner, 11, 0xffffffff);
        label((logic ? "Outputs  " : compact ? "Insert  " : "Local insert  ") + snapshot.consumers[channel], 4, 107, inner, 11, 0xffffffff);
        String routed = snapshot.routedConsumers[channel] < 0 ? "— (cache cold)" : Integer.toString(snapshot.routedConsumers[channel]);
        label((compact ? "Routed  " : "Routed insert  ") + routed, 4, 119, inner, 11, 0xffdddddd);
        label("Advanced  " + snapshot.advanced[channel], 4, 131, inner, 11, 0xffffffff);
        label("SCHEDULE", 4, 147, inner, 11, 0xffffe3a0);
        label((compact ? "Nominal/1200  " : "Local nominal/1200  ") + snapshot.nominalChecks[channel], 4, 160, inner, 11, 0xffffffff);
        label("Max same tick  " + snapshot.maxSameTick[channel], 4, 172, inner, 11, 0xffffffff);
        label("Schedule  " + scheduleName(snapshot.schedules[channel], compact), 4, 184, inner, 11, 0xffffffff);
        Label timings = label("Timing  " + (snapshot.timingText[channel].isEmpty() ? "—" : snapshot.timingText[channel]),
                4, 196, inner, 11, 0xffdddddd);
        if (!snapshot.timingText[channel].isEmpty()) {timings.setTooltips(snapshot.timingText[channel]);}
    }

    private void buildPeakPage() {
        boolean compact = compact();
        int inner = innerWidth();
        addNavigation("Peak Tick");
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

    private void addNavigation(String title) {
        panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText("<").setTooltips("Back to Controller overview")
                .setLayoutHint(new PositionalLayout.PositionalHint(3, 1, 16, 14))
                .addButtonEvent(parent -> setPage(PAGE_OVERVIEW, -1)));
        panel.addChild(new Button(Minecraft.getMinecraft(), gui).setText("^").setTooltips("Controller Diagnostics overview")
                .setLayoutHint(new PositionalLayout.PositionalHint(21, 1, 16, 14))
                .addButtonEvent(parent -> setPage(PAGE_OVERVIEW, -1)));
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
        setPage(PAGE_CHANNEL, channel);
        selectChannel.accept(channel);
    }

    private void setPage(int page, int channel) {
        this.page = page;
        selectedChannel = channel;
        revision++;
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

    private static String scheduleName(byte schedule, boolean compact) {
        switch (schedule) {
            case ControllerDiagnostics.SCHEDULE_ALIGNED: return "Aligned";
            case ControllerDiagnostics.SCHEDULE_PHASED: return "Phased";
            case ControllerDiagnostics.SCHEDULE_EVERY_TICK: return compact ? "Every tick" : "Every call";
            case ControllerDiagnostics.SCHEDULE_ADAPTIVE: return compact ? "Adaptive" : "Phased + adaptive";
            default: return "—";
        }
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
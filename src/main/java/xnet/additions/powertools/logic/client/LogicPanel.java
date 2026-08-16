package xnet.additions.powertools.logic.client;

import mcjty.lib.base.StyleConfig;
import mcjty.lib.gui.events.DefaultSelectionEvent;
import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.BlockRender;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.lib.varia.BlockPosTools;
import mcjty.lib.varia.FluidTools;
import mcjty.xnet.api.channels.Color;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.apiimpl.logic.LogicConnectorSettings;
import mcjty.xnet.apiimpl.logic.Sensor;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import xnet.additions.powertools.client.ControllerNavigator;
import xnet.additions.powertools.client.PowerToolsRow;
import xnet.additions.powertools.logic.network.LogicSnapshotNetwork;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.function.IntSupplier;

public final class LogicPanel {

    private static final int SIGNAL_MASK = 0xffff & ~(1 << Color.OFF.ordinal());

    private enum FilterMode {
        USED("Used"),
        ALL("All"),
        UNUSED("Unused");

        private final String label;

        FilterMode(String label) {
            this.label = label;
        }

        private FilterMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final class Source {
        private final int channel;
        private final ChannelClientInfo channelInfo;
        private final SidedConsumer key;
        private final ConnectorClientInfo connector;
        private final LogicConnectorSettings settings;

        private Source(int channel, ChannelClientInfo channelInfo, SidedConsumer key, ConnectorClientInfo connector, LogicConnectorSettings settings) {
            this.channel = channel;
            this.channelInfo = channelInfo;
            this.key = key;
            this.connector = connector;
            this.settings = settings;
        }
    }

    private static final class LocalReference {
        private final int channel;
        private final ChannelClientInfo channelInfo;
        private final ConnectorClientInfo connector;
        private final AbstractConnectorSettings settings;
        private final byte operator;

        private LocalReference(int channel, ChannelClientInfo channelInfo, ConnectorClientInfo connector, AbstractConnectorSettings settings, byte operator) {
            this.channel = channel;
            this.channelInfo = channelInfo;
            this.connector = connector;
            this.settings = settings;
            this.operator = operator;
        }
    }

    private static final class ReferenceEntry {
        private final LocalReference local;
        private final LogicSnapshotNetwork.RoutedReference routed;

        private ReferenceEntry(LocalReference local) {
            this.local = local;
            this.routed = null;
        }

        private ReferenceEntry(LogicSnapshotNetwork.RoutedReference routed) {
            this.local = null;
            this.routed = routed;
        }

        private boolean isRouted() {
            return routed != null;
        }

        private int getChannel() {
            return local == null ? routed.getChannel() : local.channel;
        }

        private SidedPos getTarget() {
            return local == null ? routed.getTarget() : local.connector.getPos();
        }

        private int getMask() {
            return local == null ? routed.getColorMask() : local.settings.getColorsMask();
        }
    }

    private final GuiController gui;
    private final TileEntityController controller;
    private final Panel panel;
    private final ControllerNavigator navigator;
    private final IntSupplier activeMaskSupplier;
    private final List<Source> sources = new ArrayList<>();
    private final List<LocalReference> localReferences = new ArrayList<>();
    private final List<LogicSnapshotNetwork.RoutedReference> routedReferences = new ArrayList<>();
    private final Map<Long, Integer> serverSourceMasks = new HashMap<>();

    private int width = 178;
    private int height = 217;
    private int producerMask;
    private int localReferenceMask;
    private int routedReferenceMask;
    private int renderedActiveMask = Integer.MIN_VALUE;
    private int nextRequestId;
    private int acceptedRequestId = -1;
    private int pendingDirectRequest = -1;
    private boolean requestedOnce;
    private boolean snapshotReady;
    private boolean refreshingNative;
    private FilterMode filter = FilterMode.USED;
    private Color selectedColor;
    private Color pendingDirectColor;
    private List<ChannelClientInfo> observedChannels;
    private List<ConnectedBlockClientInfo> observedBlocks;

    public LogicPanel(GuiController gui, TileEntityController controller, Panel panel, ControllerNavigator navigator, IntSupplier activeMaskSupplier) {
        this.gui = gui;
        this.controller = controller;
        this.panel = panel;
        this.navigator = navigator;
        this.activeMaskSupplier = activeMaskSupplier;
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height) {return;}
        this.width = width;
        this.height = height;
        renderedActiveMask = Integer.MIN_VALUE;
    }

    public void shown() {
        renderedActiveMask = Integer.MIN_VALUE;
        if (pendingDirectColor != null) {
            requestSnapshot(true);
        } else if (!requestedOnce) {
            requestSnapshot(false);
        }
        rebuild();
    }

    public void update() {
        boolean changed = false;
        boolean configurationChanged = false;

        if (GuiController.fromServer_channels != null && observedChannels != GuiController.fromServer_channels) {
            boolean hadChannels = observedChannels != null;
            observedChannels = GuiController.fromServer_channels;
            if (hadChannels && !refreshingNative) {clearServerSnapshot();}
            if (refreshingNative) {refreshingNative = false;}
            configurationChanged = true;
        }

        if (GuiController.fromServer_connectedBlocks != null && observedBlocks != GuiController.fromServer_connectedBlocks) {
            observedBlocks = GuiController.fromServer_connectedBlocks;
            configurationChanged = true;
        }

        if (configurationChanged) {
            rebuildConfiguration();
            changed = true;
        }

        int activeMask = activeMaskSupplier.getAsInt();
        if (renderedActiveMask != activeMask) {
            renderedActiveMask = activeMask;
            changed = true;
        }

        if (changed) {rebuild();}
    }

    public void selectColor(Color color, boolean directSource) {
        if (color == null || color == Color.OFF) {return;}
        selectedColor = color;
        pendingDirectColor = directSource ? color : null;
        pendingDirectRequest = -1;

        if (!isVisible(color)) {
            filter = isUsed(color) ? FilterMode.USED : FilterMode.ALL;
        }
        rebuild();
    }

    public void cancelPendingNavigation() {
        pendingDirectColor = null;
        pendingDirectRequest = -1;
    }

    public void receive(LogicSnapshotNetwork.Response response) {
        if (!controller.getPos().equals(response.getControllerPos()) || response.getRequestId() < acceptedRequestId) {return;}
        acceptedRequestId = response.getRequestId();

        serverSourceMasks.clear();
        for (LogicSnapshotNetwork.SourceState source : response.getSources()) {
            serverSourceMasks.put(sourceKey(source.getChannel(), source.getConsumerId(), source.getSide()), source.getColorMask());
        }

        routedReferences.clear();
        routedReferences.addAll(response.getRoutedReferences());
        routedReferenceMask = 0;
        for (LogicSnapshotNetwork.RoutedReference reference : routedReferences) {
            routedReferenceMask |= reference.getColorMask();
        }
        routedReferenceMask &= SIGNAL_MASK;
        snapshotReady = true;

        if (pendingDirectColor != null && pendingDirectRequest == response.getRequestId()) {
            Color color = pendingDirectColor;
            pendingDirectColor = null;
            pendingDirectRequest = -1;
            navigateUniqueCurrentSource(color);
        }

        ensureSelectedVisible();
        rebuild();
    }

    private void requestSnapshot(boolean direct) {
        int requestId = ++nextRequestId;
        requestedOnce = true;
        if (direct) {pendingDirectRequest = requestId;}
        LogicSnapshotNetwork.request(controller.getPos(), requestId);
    }

    private void refresh() {
        refreshingNative = true;
        clearServerSnapshot();
        observedChannels = null;
        observedBlocks = null;
        gui.refresh();
        rebuildConfiguration();
        requestSnapshot(false);
        rebuild();
    }

    private void clearServerSnapshot() {
        snapshotReady = false;
        serverSourceMasks.clear();
        routedReferences.clear();
        routedReferenceMask = 0;
    }

    private void rebuildConfiguration() {
        sources.clear();
        localReferences.clear();
        producerMask = 0;
        localReferenceMask = 0;

        if (observedChannels == null || observedBlocks == null) {
            selectedColor = null;
            return;
        }

        for (int channelIndex = 0; channelIndex < observedChannels.size(); channelIndex++) {
            ChannelClientInfo channel = observedChannels.get(channelIndex);
            if (channel == null) {continue;}

            for (Map.Entry<SidedConsumer, ConnectorClientInfo> entry : channel.getConnectors().entrySet()) {
                ConnectorClientInfo connector = entry.getValue();
                if (findBlock(connector.getPos()) == null) {continue;}
                IConnectorSettings settings = connector.getConnectorSettings();

                if (settings instanceof LogicConnectorSettings) {
                    LogicConnectorSettings logic = (LogicConnectorSettings) settings;
                    if (logic.getLogicMode() == LogicConnectorSettings.LogicMode.SENSOR) {
                        boolean produces = false;
                        for (Sensor sensor : logic.getSensors()) {
                            if (sensor.getSensorMode() == Sensor.SensorMode.OFF || sensor.getOutputColor() == null || sensor.getOutputColor() == Color.OFF) {continue;}
                            producerMask |= bit(sensor.getOutputColor());
                            produces = true;
                        }
                        if (produces) {sources.add(new Source(channelIndex, channel, entry.getKey(), connector, logic));}
                    }
                }

                if (settings instanceof AbstractConnectorSettings) {
                    AbstractConnectorSettings common = (AbstractConnectorSettings) settings;
                    int mask = common.getColorsMask() & SIGNAL_MASK;
                    if (mask != 0) {
                        localReferenceMask |= mask;
                        localReferences.add(new LocalReference(channelIndex, channel, connector, common, getEffectiveOperator(channel.getType().getID(), common)));
                    }
                }
            }
        }

        producerMask &= SIGNAL_MASK;
        localReferenceMask &= SIGNAL_MASK;
        ensureSelectedVisible();
    }

    private void rebuild() {
        panel.removeChildren();

        label("LOGIC", 4, 2, Math.max(1, width - 54), 12, 0xffffe3a0);

        Button filterButton = new Button(Minecraft.getMinecraft(), gui).setText(filter.label)
                .setTooltips("Show Used → All → Unused");
        filterButton.setLayoutHint(new PositionalLayout.PositionalHint(Math.max(4, width - 48), 1, 44, 14));
        filterButton.addButtonEvent(parent -> {
            filter = filter.next();
            ensureSelectedVisible();
            rebuild();
        });
        panel.addChild(filterButton);

        List<Color> visibleColors = getVisibleColors();
        int buttonWidth = 14;
        int gap = 2;
        int columns = Math.max(1, (Math.max(1, width - 8) + gap) / (buttonWidth + gap));
        int paletteY = 18;
        int paletteRows = Math.max(1, (visibleColors.size() + columns - 1) / columns);

        for (int i = 0; i < visibleColors.size(); i++) {
            Color color = visibleColors.get(i);
            int x = 4 + (i % columns) * (buttonWidth + gap);
            int y = paletteY + (i / columns) * 16;
            panel.addChild(createColorButton(color, x, y, buttonWidth, 14));
        }

        if (visibleColors.isEmpty()) {
            String text = filter == FilterMode.UNUSED ? "No unused colors" : "No used logic colors";
            label(text, 6, paletteY + 1, Math.max(1, width - 12), 12, StyleConfig.colorTextInListNormal);
        }

        int headingY = paletteY + paletteRows * 16 + 2;
        if (selectedColor == null) {return;}

        int activeMask = activeMaskSupplier.getAsInt();
        String state = activeMask < 0 ? "..." : (isActive(selectedColor) ? "ACTIVE" : "INACTIVE");
        label(formatColorName(selectedColor).toUpperCase(Locale.ROOT) + " - " + state, 4, headingY, Math.max(1, width - 56), 13, 0xffffe3a0);

        Button refresh = new Button(Minecraft.getMinecraft(), gui).setText("Refresh")
                .setTooltips("Refresh logic data");
        refresh.setLayoutHint(new PositionalLayout.PositionalHint(Math.max(4, width - 50), headingY - 1, 46, 14));
        refresh.addButtonEvent(parent -> refresh());
        panel.addChild(refresh);

        int sectionY = headingY + 16;
        int remaining = height - sectionY - 2;
        if (remaining < 24) {return;}

        int sourceArea = remaining / 2;
        int referenceArea = remaining - sourceArea;
        addSourcesSection(sectionY, sourceArea);
        addReferencesSection(sectionY + sourceArea, referenceArea);
    }

    private ToggleButton createColorButton(Color color, int x, int y, int buttonWidth, int buttonHeight) {
        boolean used = isUsed(color);
        boolean active = isActive(color);
        int rgb = used ? color.getColor() : dimColor(color.getColor());
        int argb = 0xff000000 | rgb;

        ToggleButton button = new ToggleButton(Minecraft.getMinecraft(), gui) {
            @Override
            public void draw(int ox, int oy) {
                if (!isVisible()) {return;}
                Rectangle bounds = getBounds();
                int x = ox + bounds.x;
                int y = oy + bounds.y;
                if (isPressed()) {
                    drawStyledBoxSelected(window, x, y, x + bounds.width - 1, y + bounds.height - 1);
                    Gui.drawRect(x + 2, y + 2, x + bounds.width - 2, y + bounds.height - 2, argb);
                } else if (isHovering()) {
                    drawStyledBoxHovering(window, x, y, x + bounds.width - 1, y + bounds.height - 1);
                    Gui.drawRect(x + 2, y + 2, x + bounds.width - 2, y + bounds.height - 2, argb);
                } else {
                    drawStyledBoxNormal(window, x, y, x + bounds.width - 1, y + bounds.height - 1, argb);
                }
                if (active) {
                    int cx = x + bounds.width / 2;
                    int cy = y + bounds.height / 2;
                    Gui.drawRect(cx - 1, cy - 1, cx + 2, cy + 2, 0xff000000);
                    Gui.drawRect(cx, cy, cx + 1, cy + 1, 0xffffffff);
                }
            }
        }.setCheckMarker(false).setText("").setPressed(selectedColor == color);

        String state = activeMaskSupplier.getAsInt() < 0 ? TextFormatting.GRAY + "Loading" : active ? TextFormatting.YELLOW + "Active" : TextFormatting.GRAY + "Inactive";
        String usage = used ? TextFormatting.WHITE + "Used" : TextFormatting.GRAY + "Unused";
        button.setTooltips(TextFormatting.WHITE + formatColorName(color) + TextFormatting.GRAY + " · " + state + TextFormatting.GRAY + " · " + usage);
        button.setLayoutHint(new PositionalLayout.PositionalHint(x, y, buttonWidth, buttonHeight));
        button.addButtonEvent(parent -> {
            selectedColor = color;
            rebuild();
        });
        return button;
    }

    private void addSourcesSection(int y, int areaHeight) {
        label("Sources", 4, y, Math.max(1, width - 8), 11, 0xffffe3a0);

        List<Source> visible = new ArrayList<>();
        for (Source source : sources) {
            if (produces(source, selectedColor)) {visible.add(source);}
        }

        visible.sort((a, b) -> {
            boolean ac = contributes(a, selectedColor);
            boolean bc = contributes(b, selectedColor);
            if (ac != bc) {return ac ? -1 : 1;}
            if (a.channel != b.channel) {return Integer.compare(a.channel, b.channel);}
            return targetName(a.connector.getPos()).compareToIgnoreCase(targetName(b.connector.getPos()));
        });

        int listY = y + 12;
        int listHeight = Math.max(1, areaHeight - 12);
        int listWidth = Math.max(1, width - 8);
        WidgetList list = PowerToolsRow.createList(gui);
        list.setLayoutHint(new PositionalLayout.PositionalHint(4, listY, listWidth, listHeight));

        for (Source source : visible) {
            list.addChild(createSourceRow(source, listWidth));
        }

        list.addSelectionEvent(new DefaultSelectionEvent() {
            @Override
            public void select(Widget<?> parent, int index) {
                list.setSelected(-1);
                if (index >= 0 && index < visible.size()) {openSource(visible.get(index));}
            }
        });

        panel.addChild(list);

        if (visible.isEmpty()) {
            label("None", 7, listY + 2, Math.max(1, width - 14), 11, StyleConfig.colorTextInListNormal);
        }
    }

    private void addReferencesSection(int y, int areaHeight) {
        label("References", 4, y, Math.max(1, width - 8), 11, 0xffffe3a0);

        List<ReferenceEntry> visible = new ArrayList<>();
        int selectedBit = bit(selectedColor);

        for (LocalReference reference : localReferences) {
            if ((reference.settings.getColorsMask() & selectedBit) != 0) {visible.add(new ReferenceEntry(reference));}
        }

        for (LogicSnapshotNetwork.RoutedReference reference : routedReferences) {
            if ((reference.getColorMask() & selectedBit) != 0) {visible.add(new ReferenceEntry(reference));}
        }

        visible.sort((a, b) -> {
            if (a.getChannel() != b.getChannel()) {return Integer.compare(a.getChannel(), b.getChannel());}
            if (a.isRouted() != b.isRouted()) {return a.isRouted() ? 1 : -1;}
            return targetName(a.getTarget()).compareToIgnoreCase(targetName(b.getTarget()));
        });

        int listY = y + 12;
        int listHeight = Math.max(1, areaHeight - 12);
        int listWidth = Math.max(1, width - 8);
        WidgetList list = PowerToolsRow.createList(gui);
        list.setLayoutHint(new PositionalLayout.PositionalHint(4, listY, listWidth, listHeight));

        for (ReferenceEntry reference : visible) {
            list.addChild(createReferenceRow(reference, listWidth));
        }

        list.addSelectionEvent(new DefaultSelectionEvent() {
            @Override
            public void select(Widget<?> parent, int index) {
                list.setSelected(-1);
                if (index >= 0 && index < visible.size()) {openReference(visible.get(index));}
            }
        });

        panel.addChild(list);

        if (visible.isEmpty()) {
            label("None", 7, listY + 2, Math.max(1, width - 14), 11, StyleConfig.colorTextInListNormal);
        }
    }

    private Panel createSourceRow(Source source, int rowWidth) {
        Minecraft mc = Minecraft.getMinecraft();
        ConnectedBlockClientInfo block = findBlock(source.connector.getPos());
        String target = targetName(source.connector.getPos());
        List<String> expressions = sourceExpressions(source, selectedColor);
        boolean contributing = contributes(source, selectedColor);

        String detail;
        if (!source.channelInfo.isEnabled()) {
            detail = "Disabled - " + expressions.get(0);
        } else if (expressions.size() == 1) {
            detail = expressions.get(0);
        } else {
            detail = "One or more: " + expressions.get(0) + " (+" + (expressions.size() - 1) + ")";
        }

        List<String> tooltips = new ArrayList<>();
        tooltips.add(TextFormatting.GREEN + "Connector: " + TextFormatting.WHITE + target);
        tooltips.add(TextFormatting.GREEN + "Last refresh: " + (!source.channelInfo.isEnabled() ? TextFormatting.RED + "Channel disabled" : contributing ? TextFormatting.YELLOW + "Active" : TextFormatting.GRAY + "Inactive"));
        if (expressions.size() == 1) {
            tooltips.add(TextFormatting.GREEN + "Condition: " + TextFormatting.WHITE + expressions.get(0));
        } else {
            tooltips.add(TextFormatting.GREEN + "Conditions:");
            for (String expression : expressions) {tooltips.add(TextFormatting.WHITE + expression);}
        }

        PowerToolsRow row = new PowerToolsRow(gui, rowWidth, detail,
                source.channelInfo.isEnabled() ? 0xffa8a8a8 : 0xff777777,
                tooltips.toArray(new String[0]));
        row.addBlock(block);
        row.addMetadata(new Label(mc, gui).setText(contributing ? "●" : "○")
                .setColor(contributing ? 0xff80ff80 : 0xff888888).setDesiredWidth(9));
        row.addChannel(source.channel, source.channelInfo);
        row.addMetadata(new Label(mc, gui).setText(target).setDynamic(true)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(2, 0)
                .setColor(StyleConfig.colorTextInListNormal));
        return row;
    }

    private Panel createReferenceRow(ReferenceEntry reference, int rowWidth) {
        Minecraft mc = Minecraft.getMinecraft();
        ChannelClientInfo channel = findChannel(reference.getChannel());
        SidedPos targetPos = reference.getTarget();
        ConnectedBlockClientInfo block = findBlock(targetPos);
        String target = reference.isRouted() && block == null ? "Routed " + BlockPosTools.toString(targetPos.getPos()) : targetName(targetPos);
        String expression = referenceExpression(reference);
        boolean enabled = channel == null || channel.isEnabled();

        List<String> tooltips = new ArrayList<>();
        tooltips.add(TextFormatting.GREEN + "Connector: " + TextFormatting.WHITE + target);
        tooltips.add(TextFormatting.GREEN + "Condition: " + TextFormatting.WHITE + expression);
        if (!enabled) {tooltips.add(TextFormatting.GREEN + "State: " + TextFormatting.RED + "Channel disabled");}
        if (reference.isRouted()) {tooltips.add(TextFormatting.GREEN + "Type: " + TextFormatting.WHITE + "Routed");}

        PowerToolsRow row = new PowerToolsRow(gui, rowWidth, expression,
                enabled ? 0xffa8a8a8 : 0xff777777, tooltips.toArray(new String[0]));
        row.addBlock(block);
        row.addChannel(reference.getChannel(), channel);
        row.addMetadata(new Label(mc, gui).setText(target).setDynamic(true)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setTextOffset(2, 0)
                .setColor(StyleConfig.colorTextInListNormal));
        return row;
    }


    private void openSource(Source source) {
        if (!navigator.xnetadditions$isNavigationReady() || !navigator.xnetadditions$navigate(source.connector.getPos(), source.channel)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player != null) {
                mc.player.sendStatusMessage(new TextComponentString(TextFormatting.YELLOW + "Logic source is no longer available in this Controller"), true);
            }
        }
    }

    private void openReference(ReferenceEntry reference) {
        if (navigator.xnetadditions$isNavigationReady() && navigator.xnetadditions$navigate(reference.getTarget(), reference.getChannel())) {return;}

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {return;}
        String message = reference.isRouted()
                ? "Routed connector is not directly navigable from this Controller"
                : "Logic reference is no longer available in this Controller";
        mc.player.sendStatusMessage(new TextComponentString(TextFormatting.YELLOW + message), true);
    }

    private void navigateUniqueCurrentSource(Color color) {
        Source found = null;
        int count = 0;

        for (Source source : sources) {
            if (!produces(source, color) || !source.channelInfo.isEnabled()) {continue;}
            int mask = serverSourceMasks.getOrDefault(sourceKey(source.channel, source.key.getConsumerId().getId(), source.key.getSide()), 0);
            if ((mask & bit(color)) == 0) {continue;}
            found = source;
            count++;
            if (count > 1) {return;}
        }

        if (count == 1) {openSource(found);}
    }

    private boolean produces(Source source, Color color) {
        for (Sensor sensor : source.settings.getSensors()) {
            if (sensor.getSensorMode() != Sensor.SensorMode.OFF && sensor.getOutputColor() == color) {return true;}
        }
        return false;
    }

    private boolean contributes(Source source, Color color) {
        if (!source.channelInfo.isEnabled()) {return false;}
        int mask = snapshotReady
                ? serverSourceMasks.getOrDefault(sourceKey(source.channel, source.key.getConsumerId().getId(), source.key.getSide()), 0)
                : source.settings.getColorMask();
        return (mask & bit(color)) != 0;
    }

    private List<String> sourceExpressions(Source source, Color color) {
        List<String> result = new ArrayList<>();
        for (Sensor sensor : source.settings.getSensors()) {
            if (sensor.getSensorMode() != Sensor.SensorMode.OFF && sensor.getOutputColor() == color) {
                result.add(formatSensor(sensor));
            }
        }
        return result;
    }

    private String formatSensor(Sensor sensor) {
        String subject;
        ItemStack filterStack = sensor.getFilter();

        switch (sensor.getSensorMode()) {
            case ITEM:
                subject = filterStack == null || filterStack.isEmpty() ? "Items (all)" : "Items " + filterStack.getDisplayName();
                break;
            case FLUID:
                if (filterStack == null || filterStack.isEmpty()) {
                    subject = "Fluid (all)";
                } else {
                    FluidStack fluid = FluidTools.convertBucketToFluid(filterStack);
                    subject = fluid == null ? "Fluid (all)" : "Fluid " + fluid.getFluid().getLocalizedName(fluid);
                }
                break;
            case ENERGY:
                subject = "Energy";
                break;
            case RS:
                subject = "Redstone";
                break;
            default:
                subject = "Off";
                break;
        }

        String operator = sensor.getOperator() == null ? "?" : sensor.getOperator().getCode();
        return subject + " " + operator + " " + sensor.getAmount();
    }

    private String referenceExpression(ReferenceEntry reference) {
        String operator = decodeOperator(reference.local != null ? reference.local.operator : reference.routed.getOperator());
        return formatColorExpression(reference.getMask(), operator);
    }
    private static byte getEffectiveOperator(String typeId, AbstractConnectorSettings settings) {
        if (isDirectMaskChannel(typeId)) {return 0;}
        NBTTagCompound tag = new NBTTagCompound();
        settings.writeToNBT(tag);
        if (!tag.hasKey("colorOperator")) {return 0;}
        int ordinal = tag.getByte("colorOperator");
        return ordinal >= 0 && ordinal <= 3 ? (byte) ordinal : 0;
    }
    private static boolean isDirectMaskChannel(String typeId) {
        return "advanced.energy".equals(typeId)
                || "mekanism.gas".equals(typeId)
                || "botania.mana".equals(typeId)
                || "tc.essentia".equals(typeId)
                || "ic2.eu".equals(typeId);
    }

    private static String decodeOperator(byte operator) {
        switch (operator) {
            case 1:
                return "OR";
            case 2:
                return "!AND";
            case 3:
                return "!OR";
            default:
                return "AND";
        }
    }

    private static String formatColorExpression(int mask, String operator) {
        List<String> colors = new ArrayList<>();
        for (Color color : Color.values()) {
            if (color != Color.OFF && (mask & bit(color)) != 0) {
                colors.add(formatColorName(color));
            }
        }

        if (colors.isEmpty()) {return "Always";}

        boolean negative = "!AND".equals(operator) || "!OR".equals(operator);
        String join = "OR".equals(operator) || "!OR".equals(operator) ? " OR " : " AND ";
        String expression = String.join(join, colors);

        if (!negative) {return expression;}
        return colors.size() == 1 ? "NOT " + expression : "NOT (" + expression + ")";
    }

    private List<Color> getVisibleColors() {
        List<Color> result = new ArrayList<>();
        for (Color color : Color.values()) {
            if (color != Color.OFF && isVisible(color)) {result.add(color);}
        }
        return result;
    }

    private boolean isVisible(Color color) {
        switch (filter) {
            case USED:
                return isUsed(color);
            case UNUSED:
                return !isUsed(color);
            case ALL:
            default:
                return true;
        }
    }

    private boolean isUsed(Color color) {
        return ((producerMask | localReferenceMask | routedReferenceMask) & bit(color)) != 0;
    }

    private boolean isActive(Color color) {
        int mask = activeMaskSupplier.getAsInt();
        return mask >= 0 && (mask & bit(color)) != 0;
    }

    private void ensureSelectedVisible() {
        if (selectedColor != null && isVisible(selectedColor)) {return;}
        selectedColor = null;
        for (Color color : Color.values()) {
            if (color != Color.OFF && isVisible(color)) {
                selectedColor = color;
                return;
            }
        }
    }

    private ChannelClientInfo findChannel(int channel) {
        if (observedChannels == null || channel < 0 || channel >= observedChannels.size()) {return null;}
        return observedChannels.get(channel);
    }

    private ConnectedBlockClientInfo findBlock(SidedPos pos) {
        if (observedBlocks == null) {return null;}
        for (ConnectedBlockClientInfo block : observedBlocks) {
            if (pos.equals(block.getPos())) {return block;}
        }
        return null;
    }

    private String targetName(SidedPos pos) {
        ConnectedBlockClientInfo block = findBlock(pos);
        if (block == null) {return BlockPosTools.toString(pos.getPos());}
        if (!block.getName().isEmpty()) {return block.getName();}
        return I18n.format(block.getBlockUnlocName()).trim();
    }

    private static String channelName(int channelIndex, ChannelClientInfo channel) {
        if (channel == null) {return "Channel " + (channelIndex + 1);}
        if (!channel.getChannelName().isEmpty()) {return "Channel " + (channelIndex + 1) + ": " + channel.getChannelName();}
        return "Channel " + (channelIndex + 1) + ": " + channel.getType().getName();
    }

    private Label label(String text, int x, int y, int width, int height, int color) {
        Label label = new Label(Minecraft.getMinecraft(), gui)
                .setText(text)
                .setColor(color)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT);
        label.setLayoutHint(new PositionalLayout.PositionalHint(x, y, width, height));
        panel.addChild(label);
        return label;
    }

    private static long sourceKey(int channel, int consumerId, net.minecraft.util.EnumFacing side) {
        return ((long) channel & 0xffL) << 40 | ((long) side.ordinal() & 0xffL) << 32 | consumerId & 0xffffffffL;
    }

    private static int bit(Color color) {
        return 1 << color.ordinal();
    }

    private static int dimColor(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return ((r + 64) / 2) << 16 | ((g + 64) / 2) << 8 | (b + 64) / 2;
    }

    private static String formatColorName(Color color) {
        String name = color.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
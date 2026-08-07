package xnet.additions.mixin.batchedit.client;

import mcjty.lib.client.RenderHelper;
import mcjty.lib.gui.Window;
import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xnet.additions.batchedit.BatchEditSupport;
import xnet.additions.batchedit.client.BatchConnectorEditorPanel;
import xnet.additions.batchedit.client.ConnectorPresetStore;
import xnet.additions.batchedit.client.PresetPreviewEditorPanel;
import xnet.additions.batchedit.network.BatchEditNetwork;
import xnet.additions.batchedit.network.PacketBatchConnectorMutation;
import xnet.additions.batchedit.network.PacketBatchConnectorUpdate;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;


import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerBatchEditMixin {

    @Shadow(remap = false) private WidgetList connectorList;
    @Shadow(remap = false) private List<SidedPos> connectorPositions;
    @Shadow(remap = false) private Panel connectorEditPanel;
    @Shadow(remap = false) private SidedPos editingConnector;
    @Shadow(remap = false) private int editingChannel;
    @Shadow(remap = false) private SidedPos showingConnector;
    @Shadow(remap = false) private boolean needsRefresh;

    @Invoker(value = "getSelectedChannel", remap = false)
    protected abstract int xnetadditions$getSelectedChannel();

    @Invoker(value = "selectChannelEditor", remap = false)
    protected abstract void xnetadditions$selectChannelEditor(int channel);

    @Unique private final Set<SidedPos> xnetadditions$selection = new LinkedHashSet<>();
    @Unique private int xnetadditions$batchChannel = -1;
    @Unique private SidedPos xnetadditions$reference;
    @Unique private boolean xnetadditions$editing;
    @Unique private boolean xnetadditions$panelDirty = true;
    @Unique private boolean xnetadditions$listRebuildPending;
    @Unique private static final Gson xnetadditions$PRESET_GSON = new GsonBuilder().setPrettyPrinting().create();

    @Unique private Window xnetadditions$toolbarWindow;
    @Unique private Panel xnetadditions$toolbarPanel;
    @Unique private ToggleButton xnetadditions$presetToggleButton;
    @Unique private final ToggleButton[] xnetadditions$presetButtons = new ToggleButton[ConnectorPresetStore.SLOT_COUNT];
    @Unique private Button xnetadditions$presetSaveButton;
    @Unique private Button xnetadditions$selectButton;
    @Unique private Button xnetadditions$editButton;
    @Unique private boolean xnetadditions$presetSaveMode;
    @Unique private int xnetadditions$previewPresetSlot = -1;
    @Unique private BatchConnectorEditorPanel xnetadditions$batchEditor;
    @Unique private long xnetadditions$lastMouseEventNanos = Long.MIN_VALUE;
    @Unique private int xnetadditions$toolbarState = Integer.MIN_VALUE;
    @Unique private int xnetadditions$configuredCount;
    @Unique private int xnetadditions$emptyCount;

    @Inject(method = "initGui", at = @At("HEAD"), remap = true)
    private void xnetadditions$beforeInit(CallbackInfo ci) {
        // GUI reinitialization replaces every widget. Keep the selected targets,
        // but discard any uncommitted values tied to the old widget tree.
        xnetadditions$editing = false;
        xnetadditions$batchEditor = null;
        xnetadditions$toolbarWindow = null;
        xnetadditions$toolbarPanel = null;
        xnetadditions$presetToggleButton = null;
        xnetadditions$presetSaveButton = null;
        xnetadditions$presetSaveMode = false;
        xnetadditions$previewPresetSlot = -1;
        for (int slot = 0;
             slot < xnetadditions$presetButtons.length;
             slot++) {
            xnetadditions$presetButtons[slot] = null;
        }
        xnetadditions$selectButton = null;
        xnetadditions$editButton = null;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        xnetadditions$panelDirty = true;
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = true)
    private void xnetadditions$afterInit(CallbackInfo ci) {
        if (!xnetadditions$selection.isEmpty() && xnetadditions$batchChannel >= 0) {
            xnetadditions$selectChannelEditor(xnetadditions$batchChannel);
            editingConnector = null;
            showingConnector = null;
            connectorList.setSelected(-1);
            xnetadditions$recalculateCounts();
            xnetadditions$chooseSafeReference();
            xnetadditions$panelDirty = true;
        }
        xnetadditions$updateToolbar();
    }

    @Inject(
            method = "registerWindows",
            at = @At("TAIL"),
            remap = false
    )
    private void xnetadditions$registerToolbar(
            WindowManager manager,
            CallbackInfo ci
    ) {
        GuiController gui = (GuiController) (Object) this;
        Minecraft mc = Minecraft.getMinecraft();
        xnetadditions$toolbarPanel = new Panel(mc, gui).setLayout(new PositionalLayout()).setFilledBackground(0xff3f3f3f, 0xff777777).setFilledRectThickness(1);
        xnetadditions$presetToggleButton = new ToggleButton(mc, gui).setCheckMarker(false).setText("Presets").setTooltips("Show connector presets", "Presets are stored on this client")
                        .addButtonEvent(parent -> xnetadditions$togglePresetBar());
        for (int slot = 0;
             slot < ConnectorPresetStore.SLOT_COUNT;
             slot++) {
            final int selectedSlot = slot;

            xnetadditions$presetButtons[slot] = new ToggleButton(mc, gui).setCheckMarker(false).setText("P" + (slot + 1))
                            .addButtonEvent(parent -> xnetadditions$clickPresetSlot(selectedSlot));}

        xnetadditions$presetSaveButton = new Button(mc, gui).setText("Save").setTooltips("Save the selected connector",
                                "Choose P1-P9 afterwards").addButtonEvent(parent -> xnetadditions$togglePresetSaveMode());
        xnetadditions$selectButton = new Button(mc, gui).setText("Select all visible").addButtonEvent(parent -> xnetadditions$selectVisible());
        xnetadditions$editButton = new Button(mc, gui).setText("Edit (0)").addButtonEvent(parent -> xnetadditions$editOrApply());
        xnetadditions$rebuildToolbarLayout();
        xnetadditions$toolbarWindow = new Window(gui, xnetadditions$toolbarPanel);

        manager.addWindow(xnetadditions$toolbarWindow);
        xnetadditions$toolbarState = Integer.MIN_VALUE;
    }

    @Inject(method = "getSideWindowBounds", at = @At("RETURN"), remap = false)
    private void xnetadditions$includeToolbarBounds(CallbackInfoReturnable<List<Rectangle>> cir) {
        if (xnetadditions$toolbarPanel != null && xnetadditions$toolbarPanel.getBounds() != null) {
            cir.getReturnValue().add(xnetadditions$toolbarPanel.getBounds());
        }
    }

    @Inject(method = "selectConnectorEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$selectConnector(SidedPos sidedPos, int channel, CallbackInfo ci) {
        if (xnetadditions$editing) {
            connectorList.setSelected(-1);
            ci.cancel();
            return;
        }

        long mouseEventNanos = Mouse.getEventNanoseconds();
        boolean freshLeftClick = Mouse.getEventButton() == 0
                && mouseEventNanos != xnetadditions$lastMouseEventNanos;
        if (freshLeftClick) {
            xnetadditions$lastMouseEventNanos = mouseEventNanos;
        }
        boolean shiftClick = freshLeftClick && Keyboard.isKeyDown(Keyboard.KEY_LSHIFT);
        if (!shiftClick) {
            if (freshLeftClick && xnetadditions$previewPresetSlot >= 0) {
                xnetadditions$previewPresetSlot = -1;
                xnetadditions$panelDirty = true;
                xnetadditions$toolbarState = Integer.MIN_VALUE;
            }
            if (freshLeftClick && !xnetadditions$selection.isEmpty()) {
                xnetadditions$clearBatch();
                return;
            }
            if (!xnetadditions$selection.isEmpty()) {
                connectorList.setSelected(-1);
                ci.cancel();
            }
            return;
        }

        if (!xnetadditions$isChannelSupported(channel)) {
            connectorList.setSelected(-1);
            xnetadditions$showUnsupported(channel);
            ci.cancel();
            return;
        }
        if (xnetadditions$batchChannel != -1 && xnetadditions$batchChannel != channel) {
            connectorList.setSelected(-1);
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this,
                    ((GuiController) (Object) this).getWindow().getWindowManager(), 50, 50,
                    TextFormatting.YELLOW + "Batch selection is on channel "
                            + (xnetadditions$batchChannel + 1));
            ci.cancel();
            return;
        }

        xnetadditions$batchChannel = channel;
        if (xnetadditions$selection.contains(sidedPos)) {
            xnetadditions$selection.remove(sidedPos);
        } else {
            if (xnetadditions$selection.size() >= PacketBatchConnectorUpdate.MAX_TARGETS) {
                connectorList.setSelected(-1);
                GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this,
                        ((GuiController) (Object) this).getWindow().getWindowManager(), 50, 50,
                        TextFormatting.YELLOW + "Batch selection is limited to "
                                + PacketBatchConnectorUpdate.MAX_TARGETS + " targets");
                ci.cancel();
                return;
            }
            xnetadditions$selection.add(sidedPos);
        }
        xnetadditions$presetSaveMode = false;
        xnetadditions$previewPresetSlot = -1;
        xnetadditions$recalculateCounts();
        xnetadditions$chooseSafeReference();

        if (xnetadditions$selection.isEmpty()) {
            xnetadditions$clearBatch();
        } else {
            xnetadditions$selectChannelEditor(channel);
            editingConnector = null;
            showingConnector = null;
            connectorList.setSelected(-1);
            xnetadditions$editing = false;
            xnetadditions$panelDirty = true;
            xnetadditions$refreshHighlights();
            xnetadditions$updateToolbar();
        }
        ci.cancel();
    }

    @Inject(method = "selectChannelEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$channelChanged(int channel, CallbackInfo ci) {
        if (xnetadditions$previewPresetSlot >= 0) {
            xnetadditions$previewPresetSlot = -1;
            xnetadditions$panelDirty = true;
            xnetadditions$toolbarState = Integer.MIN_VALUE;
        }
        if (xnetadditions$selection.isEmpty() || channel == xnetadditions$batchChannel) {
            return;
        }
        if (xnetadditions$editing) {
            ci.cancel();
            xnetadditions$selectChannelEditor(xnetadditions$batchChannel);
            return;
        }
        xnetadditions$clearBatch();
    }

    @Inject(method = "populateList", at = @At("HEAD"), remap = false)
    private void xnetadditions$beforePopulateList(CallbackInfo ci) {
        xnetadditions$listRebuildPending = needsRefresh
                && GuiController.fromServer_channels != null
                && GuiController.fromServer_connectedBlocks != null;
    }

    @Inject(method = "populateList", at = @At("RETURN"), remap = false)
    private void xnetadditions$afterPopulateList(CallbackInfo ci) {
        if (!xnetadditions$listRebuildPending) {return;}
        xnetadditions$listRebuildPending = false;
        xnetadditions$pruneSelection();
        xnetadditions$refreshHighlights();
    }

    @Inject(method = "refreshConnectorEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$showBatchPanel(CallbackInfo ci) {
        if (xnetadditions$selection.isEmpty() && xnetadditions$previewPresetSlot < 0) {return;}
        if (xnetadditions$panelDirty) {xnetadditions$rebuildBatchPanel();}
        ci.cancel();
    }

    @Inject(method = "drawGuiContainerBackgroundLayer", at = @At("TAIL"), remap = true)
    private void xnetadditions$drawBatchChannel(float partialTicks, int mouseX, int mouseY, CallbackInfo ci) {
        xnetadditions$updateToolbar();
        if (xnetadditions$selection.isEmpty() || xnetadditions$batchChannel < 0) {
            return;
        }
        GuiController gui = (GuiController) (Object) this;
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        int x = main.x + xnetadditions$batchChannel * 14 + 41;
        RenderHelper.drawVerticalGradientRect(x, main.y + 22, x + 12, main.y + 230,
                0x44ffb000, 0x44ffb000);
    }

    @Inject(method = "handleMouseClick", at = @At("HEAD"), cancellable = true, remap = true)
    private void xnetadditions$batchFilterQuickMove(Slot slotIn, int slotId, int mouseButton, ClickType type, CallbackInfo ci) {
        if (!xnetadditions$editing || xnetadditions$batchEditor == null || !xnetadditions$batchEditor.hasGhostSlots()
                || slotIn == null || type != ClickType.QUICK_MOVE || !slotIn.getHasStack()) return;
        xnetadditions$batchEditor.addToFirstEmptyGhostSlot(slotIn.getStack());
        ci.cancel();
    }

    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true, remap = true)
    private void xnetadditions$escapeBatch(char typedChar, int keyCode, CallbackInfo ci) throws IOException {
        if (keyCode != Keyboard.KEY_ESCAPE) {return;}

        if (xnetadditions$previewPresetSlot >= 0) {
            xnetadditions$closePresetPreview();
            xnetadditions$updateToolbar();
            ci.cancel();
            return;
        }

        if (xnetadditions$selection.isEmpty()) {return;}

        if (xnetadditions$editing) {
            xnetadditions$editing = false;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$updateToolbar();
        } else {
            xnetadditions$clearBatch();
        }
        ci.cancel();
    }

    @Unique
    private void xnetadditions$selectVisible() {
        if (xnetadditions$editing) {
            xnetadditions$editing = false;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$updateToolbar();
            return;
        }

        int channel = xnetadditions$getSelectedChannel();
        if (channel < 0 || xnetadditions$getChannelInfo(channel) == null) {
            return;}

        if (!xnetadditions$isChannelSupported(channel)) {xnetadditions$showUnsupported(channel);
            return;}
        if (xnetadditions$batchChannel != -1 && xnetadditions$batchChannel != channel) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                    50, 50, TextFormatting.YELLOW + "Batch selection is on channel " + (xnetadditions$batchChannel + 1));
            return;
        }

        Set<SidedPos> visible = connectorPositions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(connectorPositions);
        if (visible.isEmpty()) {GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                    50, 50, TextFormatting.YELLOW + "No visible targets on channel " + (channel + 1));
            return;
        }

        Set<SidedPos> combined = new LinkedHashSet<>(xnetadditions$selection);
        combined.addAll(visible);
        if (combined.size() > PacketBatchConnectorUpdate.MAX_TARGETS) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                    50, 50, TextFormatting.YELLOW + "Selection would contain " + combined.size() + " targets; maximum is " + PacketBatchConnectorUpdate.MAX_TARGETS);
            return;
        }
        xnetadditions$selection.addAll(visible);
        xnetadditions$batchChannel = channel;
        xnetadditions$presetSaveMode = false;
        xnetadditions$previewPresetSlot = -1;
        xnetadditions$recalculateCounts();
        xnetadditions$chooseSafeReference();

        xnetadditions$editing = false;
        xnetadditions$batchEditor = null;

        editingConnector = null;
        showingConnector = null;
        connectorList.setSelected(-1);

        xnetadditions$panelDirty = true;
        xnetadditions$refreshHighlights();
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$editOrApply() {
        if (xnetadditions$configuredCount <= 0
                || xnetadditions$reference == null) {
            return;
        }
        if (!xnetadditions$editing) {
            xnetadditions$presetSaveMode = false;
            xnetadditions$previewPresetSlot = -1;
            xnetadditions$editing = true;
            xnetadditions$panelDirty = true;
            xnetadditions$updateToolbar();
            return;
        }

        if (xnetadditions$batchEditor == null) {
            return;
        }
        Map<String, Object> changes = xnetadditions$batchEditor.getChangedValues();
        if (changes.isEmpty()) {xnetadditions$editing = false;xnetadditions$panelDirty = true;xnetadditions$updateToolbar();
            return;
        }

        List<SidedPos> configuredTargets =
                xnetadditions$getConfiguredTargets();

        if (configuredTargets.isEmpty()) {
            xnetadditions$editing = false;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$updateToolbar();
            return;
        }
        TileEntityController controller = (TileEntityController) ((GenericGuiContainerAccessor) this).xnetadditions$getTileEntity();
        BatchEditNetwork.CHANNEL.sendToServer(new PacketBatchConnectorUpdate(controller.getPos(), xnetadditions$batchChannel, configuredTargets, changes));

        xnetadditions$editing = false;
        xnetadditions$batchEditor = null;
        xnetadditions$panelDirty = true;

        ((GuiController) (Object) this).refresh();
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$rebuildBatchPanel() {
        connectorEditPanel.removeChildren();
        xnetadditions$batchEditor = null;
        GuiController gui = (GuiController) (Object) this;
        Minecraft mc = Minecraft.getMinecraft();

        if (xnetadditions$previewPresetSlot >= 0) {
            xnetadditions$rebuildPresetPreview(gui, mc);
            xnetadditions$panelDirty = false;
            return;
        }

        if (!xnetadditions$editing) {
            int presetSlot = xnetadditions$getSelectedPresetSlot();

            String presetJson = xnetadditions$getSelectedPresetJson();

            boolean presetActive = presetSlot >= 0 && presetJson != null;

            connectorEditPanel.addChild(new Label(mc, gui)
                            .setText(xnetadditions$selection.size() + " targets selected")
                            .setLayoutHint(new PositionalLayout.PositionalHint(4, 4, 150, 14)));

            connectorEditPanel.addChild(new Label(mc, gui).setText(xnetadditions$configuredCount + " configured / " + xnetadditions$emptyCount + " empty")
                            .setLayoutHint(new PositionalLayout.PositionalHint(4, 20, 150, 14)));

            connectorEditPanel.addChild(new Label(mc, gui).setText(presetActive ? "Preset P" + (presetSlot + 1) + " | Channel " + (xnetadditions$batchChannel + 1)
                                            : "Channel " + (xnetadditions$batchChannel + 1) + " | LShift add/remove")
                            .setLayoutHint(new PositionalLayout.PositionalHint(4, 36, 150, 14)));

            Button firstAction;
            Button secondAction;
            if (presetActive) {
                firstAction = new Button(mc, gui)
                                .setText("Create P" + (presetSlot + 1) + " (" + xnetadditions$emptyCount + ")")
                                .setEnabled(xnetadditions$emptyCount > 0)
                                .setTooltips("Create empty connector settings", "using complete preset P" + (presetSlot + 1))
                                .setLayoutHint(new PositionalLayout.PositionalHint(4, 56, 72, 14))
                                .addButtonEvent(parent -> xnetadditions$sendMutation(PacketBatchConnectorMutation.Operation.PASTE, presetJson));
                secondAction = new Button(mc, gui)
                                .setText("Apply P" + (presetSlot + 1) + " (" + xnetadditions$configuredCount + ")")
                                .setEnabled(xnetadditions$configuredCount > 0)
                                .setTooltips("Replace complete connector settings", "Mode, filters and limits are included")
                                .setLayoutHint(new PositionalLayout.PositionalHint(80, 56, 72, 14))
                                .addButtonEvent(parent -> xnetadditions$sendMutation(PacketBatchConnectorMutation.Operation.APPLY, presetJson));
            } else {
                firstAction = new Button(mc, gui)
                                .setText("Create (" + xnetadditions$emptyCount + ")")
                                .setEnabled(xnetadditions$emptyCount > 0)
                                .setTooltips("Create default connector settings", "Only empty selected targets are affected")
                                .setLayoutHint(new PositionalLayout.PositionalHint(4, 56, 72, 14))
                                .addButtonEvent(
                                        parent ->
                                                xnetadditions$sendMutation(
                                                        PacketBatchConnectorMutation
                                                                .Operation.CREATE,
                                                        ""
                                                )
                                );

                secondAction = new Button(mc, gui)
                                .setText("Paste (" + xnetadditions$emptyCount + ")")
                                .setEnabled(xnetadditions$emptyCount > 0)
                                .setTooltips("Paste connector settings", "Only empty selected targets are affected")
                                .setLayoutHint(new PositionalLayout.PositionalHint(80, 56, 72, 14)
                                ).addButtonEvent(parent -> xnetadditions$pasteSelected());
            }
            Button delete = new Button(mc, gui)
                            .setText("Delete configured (" + xnetadditions$configuredCount + ")")
                            .setEnabled(xnetadditions$configuredCount > 0)
                            .setTooltips("Delete channel configuration", "Physical connectors and machines remain")
                            .setLayoutHint(new PositionalLayout.PositionalHint(4, 76, 148, 14))
                            .addButtonEvent(parent -> xnetadditions$confirmDelete());

            connectorEditPanel.addChild(firstAction).addChild(secondAction).addChild(delete);
            xnetadditions$panelDirty = false;
            return;
        }

        if (!xnetadditions$isChannelSupported(xnetadditions$batchChannel)) {
            xnetadditions$editing = false;
            xnetadditions$panelDirty = true;
            return;
        }

        ConnectorClientInfo clientInfo = xnetadditions$getClientInfo(
                xnetadditions$batchChannel, xnetadditions$reference);
        if (clientInfo == null) {
            xnetadditions$editing = false;
            xnetadditions$panelDirty = true;
            return;
        }

        boolean advanced = ConnectorBlock.isAdvancedConnector(
                mc.world,
                xnetadditions$reference.getPos().offset(xnetadditions$reference.getSide())
        );
        IConnectorSettings settings = clientInfo.getConnectorSettings();
        xnetadditions$batchEditor = new BatchConnectorEditorPanel(
                connectorEditPanel, mc, gui, advanced);
        settings.createGui(xnetadditions$batchEditor);
        xnetadditions$batchEditor.setState(settings);
        xnetadditions$panelDirty = false;
    }

    @Unique
    private void xnetadditions$rebuildPresetPreview(GuiController gui, Minecraft mc) {
        String typeId = xnetadditions$getActiveTypeId();
        int slot = xnetadditions$previewPresetSlot;
        String json = ConnectorPresetStore.getPresetJson(typeId, slot);
        ChannelClientInfo channel = xnetadditions$getChannelInfo(xnetadditions$getActiveChannel());
        if (typeId == null || json == null || channel == null) {
            xnetadditions$closePresetPreview();
            return;
        }

        try {
            JsonObject root = xnetadditions$PRESET_GSON.fromJson(json, JsonObject.class);
            JsonObject connectorJson = root.getAsJsonObject("connector");
            EnumFacing side = connectorJson.has("side") ? EnumFacing.byName(connectorJson.get("side").getAsString()) : null;
            if (side == null) {
                xnetadditions$closePresetPreview();
                return;
            }

            boolean advanced = root.get("advanced").getAsBoolean();
            IConnectorSettings settings = channel.getType().createConnector(side);
            settings.readFromJson(connectorJson);

            Button remove = new Button(mc, gui).setText("x").setTextOffset(0, -1).setTooltips("Delete preset P" + (slot + 1))
                    .setLayoutHint(new PositionalLayout.PositionalHint(151, 1, 9, 10))
                    .addButtonEvent(parent -> xnetadditions$confirmDeletePreset(typeId, slot));

            Rectangle bounds = connectorEditPanel.getBounds();
            Panel previewPanel = PresetPreviewEditorPanel.createReadOnlyPanel(mc, gui, remove);
            previewPanel.setBounds(new Rectangle(0, 0, bounds.width, bounds.height));
            previewPanel.setLayoutHint(new PositionalLayout.PositionalHint(0, 0, bounds.width, bounds.height));
            connectorEditPanel.addChild(previewPanel);

            PresetPreviewEditorPanel preview = new PresetPreviewEditorPanel(previewPanel, mc, gui, advanced);
            settings.createGui(preview);
            preview.setState(settings);
            previewPanel.addChild(remove);
        } catch (RuntimeException | LinkageError e) {
            xnetadditions$closePresetPreview();
        }
    }

    @Unique
    private void xnetadditions$pasteSelected() {
        if (xnetadditions$emptyCount <= 0) {
            return;
        }

        try {
            Clipboard clipboard =
                    Toolkit.getDefaultToolkit().getSystemClipboard();

            Object contents =
                    clipboard.getData(DataFlavor.stringFlavor);

            if (!(contents instanceof String)) {
                throw new IllegalArgumentException(
                        "Clipboard does not contain text"
                );
            }

            String json = (String) contents;

            if (json.getBytes(StandardCharsets.UTF_8).length
                    > PacketBatchConnectorMutation.MAX_JSON_BYTES) {
                GuiController.showMessage(
                        Minecraft.getMinecraft(),
                        (GuiController) (Object) this,
                        ((GuiController) (Object) this)
                                .getWindow()
                                .getWindowManager(),
                        50,
                        50,
                        TextFormatting.RED + "Clipboard is too large!"
                );
                return;
            }

            xnetadditions$sendMutation(
                    PacketBatchConnectorMutation.Operation.PASTE,
                    json
            );
        } catch (Exception e) {
            GuiController.showMessage(
                    Minecraft.getMinecraft(),
                    (GuiController) (Object) this,
                    ((GuiController) (Object) this)
                            .getWindow()
                            .getWindowManager(),
                    50,
                    50,
                    TextFormatting.RED
                            + "Clipboard does not contain connector settings!"
            );
        }
    }

    @Unique
    private void xnetadditions$confirmDelete() {
        if (xnetadditions$configuredCount <= 0) {
            return;
        }

        GuiController gui = (GuiController) (Object) this;

        GuiController.showMessage(
                Minecraft.getMinecraft(),
                gui,
                gui.getWindow().getWindowManager(),
                50,
                50,
                TextFormatting.RED
                        + "Delete "
                        + xnetadditions$configuredCount
                        + " connector configurations?",
                parent -> xnetadditions$sendMutation(
                        PacketBatchConnectorMutation.Operation.DELETE,
                        ""
                )
        );
    }

    @Unique
    private void xnetadditions$sendMutation(
            PacketBatchConnectorMutation.Operation operation,
            String clipboardJson
    ) {
        if (xnetadditions$selection.isEmpty()
                || xnetadditions$batchChannel < 0) {
            return;
        }

        TileEntityController controller =
                (TileEntityController)
                        ((GenericGuiContainerAccessor) this)
                                .xnetadditions$getTileEntity();

        BatchEditNetwork.CHANNEL.sendToServer(
                new PacketBatchConnectorMutation(
                        controller.getPos(),
                        xnetadditions$batchChannel,
                        operation,
                        new ArrayList<>(xnetadditions$selection),
                        clipboardJson
                )
        );

        xnetadditions$editing = false;
        xnetadditions$batchEditor = null;
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;

        ((GuiController) (Object) this).refresh();
        xnetadditions$updateToolbar();
    }

    @Unique
    private List<SidedPos> xnetadditions$getConfiguredTargets() {
        List<SidedPos> configured =
                new ArrayList<>(xnetadditions$configuredCount);

        for (SidedPos target : xnetadditions$selection) {
            if (xnetadditions$hasConnector(
                    xnetadditions$batchChannel,
                    target
            )) {
                configured.add(target);
            }
        }

        return configured;
    }

    @Unique
    private void xnetadditions$refreshHighlights() {
        if (connectorList == null) {
            return;
        }
        connectorList.clearHilightedRows();
        if (connectorPositions == null || xnetadditions$selection.isEmpty()) {
            return;
        }
        for (int i = 0; i < connectorPositions.size(); i++) {
            if (xnetadditions$selection.contains(connectorPositions.get(i))) {
                connectorList.addHilightedRow(i);
            }
        }
    }

    @Unique
    private void xnetadditions$clearBatch() {
        xnetadditions$selection.clear();
        xnetadditions$batchChannel = -1;
        xnetadditions$reference = null;
        xnetadditions$configuredCount = 0;
        xnetadditions$emptyCount = 0;
        xnetadditions$editing = false;
        xnetadditions$previewPresetSlot = -1;
        xnetadditions$batchEditor = null;
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;

        showingConnector = null;

        if (connectorList != null) {
            connectorList.clearHilightedRows();
        }

        if (connectorEditPanel != null) {
            connectorEditPanel.removeChildren();
        }

        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$pruneSelection() {
        if (xnetadditions$selection.isEmpty()
                || GuiController.fromServer_channels == null
                || GuiController.fromServer_connectedBlocks == null) {
            return;
        }

        if (!xnetadditions$isChannelSupported(
                xnetadditions$batchChannel
        )) {
            xnetadditions$clearBatch();
            return;
        }

        Set<SidedPos> connected = new HashSet<>();

        for (ConnectedBlockClientInfo block
                : GuiController.fromServer_connectedBlocks) {
            connected.add(block.getPos());
        }

        boolean removed =
                xnetadditions$selection.removeIf(
                        pos -> !connected.contains(pos)
                );

        if (xnetadditions$selection.isEmpty()) {
            xnetadditions$clearBatch();
            return;
        }
        xnetadditions$recalculateCounts();

        if (xnetadditions$reference == null
                || !xnetadditions$selection.contains(
                xnetadditions$reference
        )
                || xnetadditions$getClientInfo(
                xnetadditions$batchChannel,
                xnetadditions$reference
        ) == null) {
            xnetadditions$chooseSafeReference();
        }

        /*
         * A server refresh may have changed targets from empty to configured
         * or configured to empty without removing them from the selection.
         */
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;

        if (removed) {
            xnetadditions$refreshHighlights();
        }

        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$chooseSafeReference() {
        xnetadditions$reference = null;

        if (xnetadditions$batchChannel < 0
                || xnetadditions$selection.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        /*
         * Prefer a configured normal connector. Its GUI only exposes
         * values that are safe for both normal and advanced targets.
         */
        if (mc.world != null) {
            for (SidedPos pos : xnetadditions$selection) {
                ConnectorClientInfo info =
                        xnetadditions$getClientInfo(
                                xnetadditions$batchChannel,
                                pos
                        );

                if (info == null) {
                    continue;
                }

                BlockPos connectorPos =
                        pos.getPos().offset(pos.getSide());

                if (!ConnectorBlock.isAdvancedConnector(
                        mc.world,
                        connectorPos
                )) {
                    xnetadditions$reference = pos;
                    return;
                }
            }
        }

        /*
         * If every configured target is advanced, use the first
         * configured advanced connector.
         */
        for (SidedPos pos : xnetadditions$selection) {
            if (xnetadditions$getClientInfo(
                    xnetadditions$batchChannel,
                    pos
            ) != null) {
                xnetadditions$reference = pos;
                return;
            }
        }
    }

    @Unique
    private boolean xnetadditions$hasConnector(int channel, SidedPos pos) {
        return xnetadditions$getClientInfo(channel, pos) != null;
    }

    @Unique
    private ConnectorClientInfo xnetadditions$getClientInfo(int channel, SidedPos pos) {
        if (pos == null) {
            return null;
        }
        ChannelClientInfo channelInfo = xnetadditions$getChannelInfo(channel);
        if (channelInfo == null) {
            return null;
        }
        for (ConnectorClientInfo connector : channelInfo.getConnectors().values()) {
            if (pos.equals(connector.getPos())) {
                return connector;
            }
        }
        return null;
    }


    @Unique
    private ChannelClientInfo xnetadditions$getChannelInfo(int channel) {
        if (GuiController.fromServer_channels == null
                || channel < 0 || channel >= GuiController.fromServer_channels.size()) {
            return null;
        }
        return GuiController.fromServer_channels.get(channel);
    }

    @Unique
    private boolean xnetadditions$isChannelSupported(int channel) {
        ChannelClientInfo channelInfo = xnetadditions$getChannelInfo(channel);
        return channelInfo != null
                && BatchEditSupport.isSupported(channelInfo.getType().getID());
    }

    @Unique
    private void xnetadditions$showUnsupported(int channel) {
        ChannelClientInfo channelInfo = xnetadditions$getChannelInfo(channel);
        String type = channelInfo == null ? "this channel" : channelInfo.getType().getName();
        GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this,
                ((GuiController) (Object) this).getWindow().getWindowManager(), 50, 50,
                TextFormatting.YELLOW + "Batch edit is not supported for " + type);
    }

    @Unique
    private void xnetadditions$recalculateCounts() {
        xnetadditions$configuredCount = 0;

        if (xnetadditions$batchChannel >= 0) {
            for (SidedPos target : xnetadditions$selection) {
                if (xnetadditions$hasConnector(
                        xnetadditions$batchChannel,
                        target
                )) {
                    xnetadditions$configuredCount++;
                }
            }
        }

        xnetadditions$emptyCount =
                xnetadditions$selection.size()
                        - xnetadditions$configuredCount;

        xnetadditions$toolbarState = Integer.MIN_VALUE;
    }

    @Unique
    private void xnetadditions$rebuildToolbarLayout() {
        if (xnetadditions$toolbarPanel == null
                || xnetadditions$presetToggleButton == null) {
            return;
        }

        GuiController gui =
                (GuiController) (Object) this;

        Rectangle main =
                gui.getWindow()
                        .getToplevel()
                        .getBounds();

        boolean expanded =
                ConnectorPresetStore.isExpanded();

        int height = expanded ? 36 : 18;

        boolean placeBelow =
                main.y < height + 2
                        && main.y
                        + main.height
                        + height
                        + 2
                        <= gui.height;

        int toolbarY = placeBelow
                ? main.y + main.height + 2
                : Math.max(0, main.y - height - 2);

        int mainRowY;

        int presetRowY;

        if (!expanded) {
            mainRowY = 2;
            presetRowY = -1;
        } else if (placeBelow) {
            mainRowY = 2;
            presetRowY = 20;
        } else {
            presetRowY = 2;
            mainRowY = 20;
        }

        xnetadditions$toolbarPanel.setBounds(
                new Rectangle(
                        main.x,
                        toolbarY,
                        main.width,
                        height
                )
        );

        xnetadditions$toolbarPanel.removeChildren();

        xnetadditions$presetToggleButton.setLayoutHint(
                new PositionalLayout.PositionalHint(
                        2,
                        mainRowY,
                        54,
                        14
                )
        );

        xnetadditions$selectButton.setLayoutHint(
                new PositionalLayout.PositionalHint(
                        58,
                        mainRowY,
                        120,
                        14
                )
        );

        xnetadditions$editButton.setLayoutHint(
                new PositionalLayout.PositionalHint(
                        180,
                        mainRowY,
                        Math.max(72, main.width - 182),
                        14
                )
        );

        xnetadditions$toolbarPanel
                .addChild(xnetadditions$presetToggleButton)
                .addChild(xnetadditions$selectButton)
                .addChild(xnetadditions$editButton);

        if (expanded) {
            for (int slot = 0;
                 slot < ConnectorPresetStore.SLOT_COUNT;
                 slot++) {
                xnetadditions$presetButtons[slot]
                        .setLayoutHint(
                                new PositionalLayout.PositionalHint(
                                        2 + slot * 22,
                                        presetRowY,
                                        20,
                                        14
                                )
                        );

                xnetadditions$toolbarPanel.addChild(
                        xnetadditions$presetButtons[slot]
                );
            }

            xnetadditions$presetSaveButton.setLayoutHint(
                    new PositionalLayout.PositionalHint(
                            200,
                            presetRowY,
                            Math.max(52, main.width - 202),
                            14
                    )
            );

            xnetadditions$toolbarPanel.addChild(
                    xnetadditions$presetSaveButton
            );
        }

        xnetadditions$toolbarState =
                Integer.MIN_VALUE;
    }

    @Unique
    private void xnetadditions$togglePresetBar() {
        if (xnetadditions$editing) {
            return;
        }

        ConnectorPresetStore.setExpanded(
                !ConnectorPresetStore.isExpanded()
        );

        xnetadditions$presetSaveMode = false;
        xnetadditions$closePresetPreview();
        xnetadditions$panelDirty = true;

        xnetadditions$rebuildToolbarLayout();
        xnetadditions$updateToolbar();
    }

    @Unique
    private int xnetadditions$getActiveChannel() {
        if (!xnetadditions$selection.isEmpty()
                && xnetadditions$batchChannel >= 0) {
            return xnetadditions$batchChannel;
        }

        return xnetadditions$getSelectedChannel();
    }

    @Unique
    private String xnetadditions$getActiveTypeId() {
        ChannelClientInfo channel =
                xnetadditions$getChannelInfo(
                        xnetadditions$getActiveChannel()
                );

        return channel == null
                ? null
                : channel.getType().getID();
    }

    @Unique
    private int xnetadditions$getSelectedPresetSlot() {
        if (!ConnectorPresetStore.isExpanded()) {
            return -1;
        }

        return ConnectorPresetStore.getSelectedSlot(
                xnetadditions$getActiveTypeId()
        );
    }

    @Unique
    private String xnetadditions$getSelectedPresetJson() {
        String typeId =
                xnetadditions$getActiveTypeId();

        int slot =
                xnetadditions$getSelectedPresetSlot();

        if (typeId == null || slot < 0) {
            return null;
        }

        return ConnectorPresetStore.getPresetJson(
                typeId,
                slot
        );
    }

    @Unique
    private SidedPos xnetadditions$getPresetSource() {
        /*
         * A batch is only an unambiguous source when exactly one configured
         * connector is selected.
         */
        if (!xnetadditions$selection.isEmpty()) {
            return xnetadditions$configuredCount == 1
                    ? xnetadditions$reference
                    : null;
        }

        int channel =
                xnetadditions$getSelectedChannel();

        if (editingConnector != null
                && xnetadditions$hasConnector(
                channel,
                editingConnector
        )) {
            return editingConnector;
        }

        return null;
    }

    @Unique
    private int xnetadditions$getPresetSourceChannel() {
        if (!xnetadditions$selection.isEmpty()) {
            return xnetadditions$configuredCount == 1
                    ? xnetadditions$batchChannel
                    : -1;
        }

        int channel =
                xnetadditions$getSelectedChannel();

        return editingConnector != null
                && xnetadditions$hasConnector(
                channel,
                editingConnector
        )
                ? channel
                : -1;
    }

    @Unique
    private void xnetadditions$closePresetPreview() {
        if (xnetadditions$previewPresetSlot < 0) {return;}
        xnetadditions$previewPresetSlot = -1;
        if (xnetadditions$selection.isEmpty()) {showingConnector = null;}
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
    }

    @Unique
    private void xnetadditions$confirmDeletePreset(String typeId, int slot) {
        GuiController gui = (GuiController) (Object) this;
        GuiController.showMessage(Minecraft.getMinecraft(), gui, gui.getWindow().getWindowManager(), 50, 50,
                TextFormatting.RED + "Delete preset P" + (slot + 1) + "?",
                parent -> {
                    if (ConnectorPresetStore.deletePreset(typeId, slot)) {
                        xnetadditions$closePresetPreview();
                        xnetadditions$panelDirty = true;
                        xnetadditions$toolbarState = Integer.MIN_VALUE;
                        xnetadditions$updateToolbar();
                    } else if (Minecraft.getMinecraft().player != null) {
                        Minecraft.getMinecraft().player.sendStatusMessage(new TextComponentString(TextFormatting.RED + "Could not delete preset P" + (slot + 1)), true);
                    }
                });
    }

    @Unique
    private void xnetadditions$togglePresetSaveMode() {
        if (xnetadditions$presetSaveMode) {
            xnetadditions$presetSaveMode = false;
            xnetadditions$toolbarState =
                    Integer.MIN_VALUE;
            xnetadditions$updateToolbar();
            return;
        }

        if (xnetadditions$getPresetSource() == null) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                    50, 50, TextFormatting.YELLOW + "Select exactly one configured connector");
            return;
        }

        xnetadditions$presetSaveMode = true;
        xnetadditions$toolbarState =
                Integer.MIN_VALUE;
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$clickPresetSlot(int slot) {
        String typeId = xnetadditions$getActiveTypeId();
        if (typeId == null) {return;}

        if (xnetadditions$presetSaveMode) {
            xnetadditions$savePresetToSlot(typeId, slot);
            return;
        }

        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
            if (!ConnectorPresetStore.hasPreset(typeId, slot)) {return;}
            ConnectorPresetStore.setSelectedSlot(typeId, slot);
            if (xnetadditions$previewPresetSlot == slot) {xnetadditions$closePresetPreview();}
            else {
                xnetadditions$previewPresetSlot = slot;
                xnetadditions$panelDirty = true;
                xnetadditions$toolbarState = Integer.MIN_VALUE;
            }
            xnetadditions$updateToolbar();
            return;
        }

        xnetadditions$closePresetPreview();
        if (!ConnectorPresetStore.hasPreset(typeId, slot)) {return;}

        int selected = ConnectorPresetStore.getSelectedSlot(typeId);
        ConnectorPresetStore.setSelectedSlot(typeId, selected == slot ? -1 : slot);
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$savePresetToSlot(String typeId, int slot
    ) {
        String json = xnetadditions$buildPresetJson();
        if (json == null) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                    50, 50, TextFormatting.RED + "This connector cannot be saved");
            return;
        }

        if (ConnectorPresetStore.hasPreset(typeId, slot)) {
            GuiController gui = (GuiController) (Object) this;
            GuiController.showMessage(Minecraft.getMinecraft(), gui, gui.getWindow().getWindowManager(), 50, 50, TextFormatting.YELLOW + "Replace preset P"
                            + (slot + 1) + "?", parent -> xnetadditions$commitPreset(typeId, slot, json));
            return;
        }
        xnetadditions$commitPreset(typeId, slot, json);
    }

    @Unique
    private void xnetadditions$commitPreset(String typeId, int slot, String json) {
        boolean saved = ConnectorPresetStore.savePreset(typeId, slot, json);
        if (saved) xnetadditions$presetSaveMode = false;
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            mc.player.sendStatusMessage(new TextComponentString(saved ? TextFormatting.GREEN + "Saved connector preset P"
                                    + (slot + 1) : TextFormatting.RED + "Could not save connector preset"),
                    true
            );
        }

        xnetadditions$updateToolbar();
    }

    @Unique
    private String xnetadditions$buildPresetJson() {
        SidedPos source = xnetadditions$getPresetSource();
        int channelIndex = xnetadditions$getPresetSourceChannel();
        ChannelClientInfo channel = xnetadditions$getChannelInfo(channelIndex);
        ConnectorClientInfo connector = xnetadditions$getClientInfo(channelIndex, source);
        if (source == null || channel == null || connector == null) {
            return null;
        }
        JsonObject connectorJson = connector.getConnectorSettings().writeToJson();
        if (connectorJson == null) {
            return null;}

        JsonObject root = new JsonObject();
        root.addProperty("type", channel.getType().getID());
        root.add("connector", connectorJson);
        boolean advanced = ConnectorBlock.isAdvancedConnector(Minecraft.getMinecraft().world, source.getPos().offset(source.getSide()));
        root.addProperty("advanced", advanced);
        String json = xnetadditions$PRESET_GSON.toJson(root);
        return json.getBytes(StandardCharsets.UTF_8).length <= PacketBatchConnectorMutation.MAX_JSON_BYTES ? json : null;
    }

    @Unique
    private void xnetadditions$updateToolbar() {
        if (xnetadditions$presetToggleButton == null || xnetadditions$selectButton == null || xnetadditions$editButton == null
                || xnetadditions$presetSaveButton == null) {
            return;
        }

        int selectedChannel = xnetadditions$getSelectedChannel();
        boolean supported = xnetadditions$isChannelSupported(selectedChannel);
        boolean hasChanges = xnetadditions$batchEditor != null && xnetadditions$batchEditor.hasChanges();
        String typeId = xnetadditions$getActiveTypeId();
        int selectedPreset = ConnectorPresetStore.getSelectedSlot(typeId);
        int occupiedMask = ConnectorPresetStore.getOccupiedMask(typeId);
        boolean hasPresetSource = xnetadditions$getPresetSource() != null;
        if (xnetadditions$presetSaveMode && !hasPresetSource) {xnetadditions$presetSaveMode = false;}

        int state = selectedChannel + 2;
        state = 31 * state + (supported ? 1 : 0);
        state = 31 * state + (xnetadditions$editing ? 1 : 0);
        state = 31 * state + xnetadditions$selection.size();
        state = 31 * state + xnetadditions$configuredCount;
        state = 31 * state + xnetadditions$emptyCount;
        state = 31 * state + (hasChanges ? 1 : 0);
        state = 31 * state
                + (ConnectorPresetStore.isExpanded() ? 1 : 0);
        state = 31 * state + selectedPreset;
        state = 31 * state + xnetadditions$previewPresetSlot;
        state = 31 * state + occupiedMask;
        state = 31 * state
                + (xnetadditions$presetSaveMode ? 1 : 0);
        state = 31 * state
                + (hasPresetSource ? 1 : 0);
        state = 31 * state
                + (typeId == null ? 0 : typeId.hashCode());

        if (state == xnetadditions$toolbarState) {
            return;
        }
        xnetadditions$toolbarState = state;
        xnetadditions$presetToggleButton.setPressed(ConnectorPresetStore.isExpanded());
        xnetadditions$presetToggleButton.setText(xnetadditions$previewPresetSlot >= 0 ? "P" + (xnetadditions$previewPresetSlot + 1) + " View" : "Presets");
        xnetadditions$presetToggleButton.setEnabled(!xnetadditions$editing
        );

        if (xnetadditions$editing) {
            xnetadditions$selectButton
                    .setText("Cancel")
                    .setEnabled(true)
                    .setTooltips("Discard staged changes", "Keep the target selection"
                    );

            xnetadditions$editButton
                    .setText("Apply (" + xnetadditions$configuredCount + ")")
                    .setEnabled(hasChanges && xnetadditions$configuredCount > 0)
                    .setTooltips("Apply only controls you changed", "Untouched settings remain unchanged"
                    );
        } else {
            xnetadditions$selectButton.setText("Select all visible").setEnabled(supported)
                    .setTooltips(
                            supported ? "Add all visible targets to selection" : "Batch edit is unavailable for this channel type",
                            supported ? "Configured and empty cells are included" : "");

            xnetadditions$editButton
                    .setText("Edit (" + xnetadditions$configuredCount + ")")
                    .setEnabled(xnetadditions$configuredCount > 0 && xnetadditions$reference != null)
                    .setTooltips("Edit configured selected targets", xnetadditions$emptyCount + " empty targets will be untouched");
        }

        for (int slot = 0; slot < ConnectorPresetStore.SLOT_COUNT; slot++) {
            ToggleButton button = xnetadditions$presetButtons[slot];

            if (button == null) {continue;}

            boolean occupied = (occupiedMask & (1 << slot)) != 0;
            button.setPressed(!xnetadditions$presetSaveMode && selectedPreset == slot);
            button.setEnabled(!xnetadditions$editing && typeId != null && (xnetadditions$presetSaveMode ? hasPresetSource : occupied));

            if (xnetadditions$presetSaveMode) {
                button.setTooltips(occupied ? "Replace preset P" + (slot + 1) : "Save as preset P" + (slot + 1), "Channel type: " + typeId);
            } else if (occupied) {
                button.setTooltips("Select preset P" + (slot + 1), "LShift-click to view settings");
            } else {button.setTooltips("Preset P" + (slot + 1) + " is empty", "Press Save to fill this slot");
            }
        }

        xnetadditions$presetSaveButton
                .setText(xnetadditions$presetSaveMode ? "Cancel" : "Save")
                .setEnabled(!xnetadditions$editing && (xnetadditions$presetSaveMode || hasPresetSource))
                .setTooltips(
                        xnetadditions$presetSaveMode ? "Cancel preset saving" : "Save the current connector",
                        xnetadditions$presetSaveMode ? "No changes will be made" : "Exactly one configured connector is required"
                );
    }
}

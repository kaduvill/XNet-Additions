package xnet.additions.mixin.batchedit.client;

import mcjty.lib.client.RenderHelper;
import mcjty.lib.gui.Window;
import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.*;
import mcjty.lib.typed.Key;
import mcjty.lib.typed.Type;
import mcjty.lib.typed.TypedMap;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.XNet;
import mcjty.xnet.apiimpl.fluids.FluidConnectorSettings;
import mcjty.xnet.apiimpl.items.ItemConnectorSettings;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectorClientInfo;
import mcjty.xnet.compat.jei.XNetJeiFluidFilterCollector;
import mcjty.xnet.compat.jei.XNetJeiItemFilterCollector;
import mcjty.xnet.network.XNetMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
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
import xnet.additions.config.client.XNetAdditionsClientConfig;
import xnet.additions.powertools.batchedit.BatchEditSupport;
import xnet.additions.powertools.batchedit.DataCollectorEditorGui;
import xnet.additions.powertools.batchedit.client.BatchConnectorEditorPanel;
import xnet.additions.powertools.batchedit.client.BatchEditMouseHandler;
import xnet.additions.powertools.batchedit.client.ConnectorPresetStore;
import xnet.additions.powertools.batchedit.network.BatchEditNetwork;
import xnet.additions.powertools.batchedit.network.PacketBatchConnectorMutation;
import xnet.additions.powertools.batchedit.network.PacketBatchConnectorUpdate;
import xnet.additions.powertools.batchedit.network.PacketBatchEditResult;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import xnet.additions.compat.jei.XNetCustomRecipeFillTarget;
import xnet.additions.compat.jei.XNetCustomRecipeFilterCollector;
import static mcjty.xnet.logic.ChannelInfo.MAX_CHANNELS;

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
import java.util.Locale;
import java.util.Set;

@Mixin(value = GuiController.class, remap = false)
public abstract class GuiControllerBatchEditMixin implements BatchEditMouseHandler, XNetCustomRecipeFillTarget, PacketBatchEditResult.Receiver {
    @Shadow(remap = false) private WidgetList connectorList;
    @Shadow(remap = false) private List<SidedPos> connectorPositions;
    @Shadow(remap = false) private Panel connectorEditPanel;
    @Shadow(remap = false) private SidedPos editingConnector;
    @Shadow(remap = false) private int editingChannel;
    @Shadow(remap = false) private SidedPos showingConnector;
    @Shadow(remap = false) private int delayedSelectedChannel;
    @Shadow(remap = false) private int delayedSelectedLine;
    @Shadow(remap = false) private SidedPos delayedSelectedConnector;
    @Shadow(remap = false) private boolean needsRefresh;

    @Invoker(value = "selectChannelEditor", remap = false)
    protected abstract void xnetadditions$selectChannelEditor(int channel);

    @Unique private final Set<SidedPos> xnetadditions$selection = new LinkedHashSet<>();
    @Unique private int xnetadditions$batchChannel = -1;
    @Unique private SidedPos xnetadditions$reference;
    @Unique private boolean xnetadditions$editing;
    @Unique private boolean xnetadditions$panelDirty = true;
    @Unique private boolean xnetadditions$listRebuildPending;
    @Unique private static final Gson xnetadditions$PRESET_GSON = new GsonBuilder().setPrettyPrinting().create();
    @Unique private static final String xnetadditions$CUSTOM_FILTER_TAG = "flt";
    @Unique private static final int xnetadditions$CUSTOM_FILTER_LIMIT = 18;
    @Unique private Panel xnetadditions$toolbarPanel;
    @Unique private ToggleButton xnetadditions$presetToggleButton;
    @Unique private final ToggleButton[] xnetadditions$presetButtons = new ToggleButton[ConnectorPresetStore.SLOT_COUNT];
    @Unique private Button xnetadditions$presetSaveButton;
    @Unique private Label xnetadditions$presetSaveHint;
    @Unique private TextField xnetadditions$presetNameField;
    @Unique private Button xnetadditions$selectButton;
    @Unique private Button xnetadditions$editButton;
    @Unique private Button xnetadditions$exactButton;
    @Unique private Button xnetadditions$toolbarVisibilityButton;
    @Unique private boolean xnetadditions$presetSaveMode;
    @Unique private String xnetadditions$presetSaveTypeId;
    @Unique private String xnetadditions$presetSaveJson;
    @Unique private int xnetadditions$previewPresetSlot = -1;
    @Unique private String xnetadditions$editingPresetTypeId;
    @Unique private String xnetadditions$editingPresetJson;
    @Unique private int xnetadditions$editingPresetChannel = -1;
    @Unique private BatchConnectorEditorPanel xnetadditions$batchEditor;
    @Unique private Map<String, Object> xnetadditions$restoreValues;
    @Unique private Map<String, Object> xnetadditions$restoreChanges;
    @Unique private Map<String, Object> xnetadditions$restoreOriginalValues;
    @Unique private String xnetadditions$restoreOriginalMode;
    @Unique private long xnetadditions$lastMouseEventNanos = Long.MIN_VALUE;
    @Unique private int xnetadditions$toolbarState = Integer.MIN_VALUE;
    @Unique private boolean xnetadditions$presetLayoutHasType;
    @Unique private boolean xnetadditions$topPreferenceApplied;
    @Unique private boolean xnetadditions$toolbarVisible;
    @Unique private boolean xnetadditions$presetsExpanded;
    @Unique private int xnetadditions$configuredCount;
    @Unique private int xnetadditions$emptyCount;
    @Unique private String xnetadditions$notice;
    @Unique private long xnetadditions$noticeUntil;
    @Unique private int xnetadditions$noticeColor;

    @Inject(method = "initGui", at = @At("HEAD"), remap = true)
    private void xnetadditions$beforeInit(CallbackInfo ci) {
        if (!xnetadditions$topPreferenceApplied) {
            String topPanel = XNetAdditionsClientConfig.getTopPanelOnOpen();
            xnetadditions$toolbarVisible = !XNetAdditionsClientConfig.TOP_CLOSED.equals(topPanel);
            xnetadditions$presetsExpanded = XNetAdditionsClientConfig.TOP_PRESETS.equals(topPanel);
            xnetadditions$topPreferenceApplied = true;
        }
        boolean restoreEditor = xnetadditions$batchEditor != null && (xnetadditions$editing || xnetadditions$isEditingPreset());
        if (restoreEditor) xnetadditions$captureEditorState();
        else {
            xnetadditions$editing = false;
            xnetadditions$previewPresetSlot = -1;
            xnetadditions$editingPresetTypeId = null;
            xnetadditions$editingPresetJson = null;
            xnetadditions$editingPresetChannel = -1;
            xnetadditions$clearEditorRestore();
        }
        xnetadditions$batchEditor = null;
        xnetadditions$toolbarPanel = null;
        xnetadditions$presetToggleButton = null;
        xnetadditions$presetSaveButton = null;
        xnetadditions$presetSaveHint = null;
        xnetadditions$presetNameField = null;
        xnetadditions$presetSaveMode = false;
        xnetadditions$presetSaveTypeId = null;
        xnetadditions$presetSaveJson = null;
        for (int slot = 0;
             slot < xnetadditions$presetButtons.length;
             slot++) {
            xnetadditions$presetButtons[slot] = null;
        }
        xnetadditions$selectButton = null;
        xnetadditions$editButton = null;
        xnetadditions$exactButton = null;
        xnetadditions$toolbarVisibilityButton = null;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        xnetadditions$panelDirty = true;
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = true)
    private void xnetadditions$afterInit(CallbackInfo ci) {
        int channel = xnetadditions$isEditingPreset() ? xnetadditions$editingPresetChannel
                : !xnetadditions$selection.isEmpty() && xnetadditions$batchChannel >= 0 ? xnetadditions$batchChannel : -1;
        if (channel >= 0) {
            xnetadditions$selectChannelEditor(channel);
            editingConnector = null;
            showingConnector = null;
            connectorList.setSelected(-1);
            if (!xnetadditions$selection.isEmpty()) {
                xnetadditions$recalculateCounts();
                xnetadditions$chooseSafeReference();
            }
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
        xnetadditions$presetToggleButton = new ToggleButton(mc, gui).setCheckMarker(false).setText("Presets").setTooltips("Show connector presets")
                .addButtonEvent(parent -> xnetadditions$togglePresetBar());
        for (int slot = 0;
             slot < ConnectorPresetStore.SLOT_COUNT;
             slot++) {
            final int selectedSlot = slot;

            xnetadditions$presetButtons[slot] = new ToggleButton(mc, gui).setCheckMarker(false).setText("P" + (slot + 1))
                    .setEnabled(false)
                    .addButtonEvent(parent -> xnetadditions$clickPresetSlot(selectedSlot));}

        xnetadditions$presetSaveButton = new Button(mc, gui).setText("Save").setTooltips("Save the selected connector",
                "Choose P1-P9 afterwards").setEnabled(false).addButtonEvent(parent -> xnetadditions$togglePresetSaveMode());
        xnetadditions$presetSaveHint = new Label(mc, gui).setText("Name:").setColor(0xffffe3a0);
        xnetadditions$presetNameField = new TextField(mc, gui).setTooltips("Optional preset name", "Maximum " + ConnectorPresetStore.NAME_MAX_LENGTH + " characters");
        xnetadditions$selectButton = new Button(mc, gui).setText("Select all visible").setEnabled(false)
                .addButtonEvent(parent -> xnetadditions$selectVisible());
        xnetadditions$editButton = new Button(mc, gui).setText("Edit (0)").setEnabled(false)
                .addButtonEvent(parent -> xnetadditions$editOrApply(false));
        xnetadditions$exactButton = new Button(mc, gui).setText("Apply Exact").setEnabled(false)
                .addButtonEvent(parent -> xnetadditions$editOrApply(true));
        xnetadditions$toolbarVisibilityButton = new Button(mc, gui)
                .addButtonEvent(parent -> xnetadditions$toggleToolbarVisibility());
        xnetadditions$rebuildToolbarLayout();
        manager.addWindow(new Window(gui, xnetadditions$toolbarPanel));
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

        boolean freshLeftClick = xnetadditions$consumeControllerLeftClick();
        // A clean preset editor doubles as a preview. Only a real navigation click closes it;
        // XNet also calls selectConnectorEditor programmatically while rebuilding the GUI.
        if (xnetadditions$isEditingPreset()) {
            if (!freshLeftClick
                    || (xnetadditions$batchEditor != null && xnetadditions$batchEditor.hasChanges())) {
                connectorList.setSelected(-1);
                ci.cancel();
                return;
            }
            xnetadditions$closePresetPreview();
        }
        boolean shiftClick = freshLeftClick && Keyboard.isKeyDown(Keyboard.KEY_LSHIFT);
        if (freshLeftClick && xnetadditions$presetSaveMode) {xnetadditions$setPresetSaveMode(false);}
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
        if (!xnetadditions$selection.isEmpty()) {
            connectorList.setSelected(-1);
            ci.cancel();
        }
    }

    @Inject(method = "selectChannelEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$channelChanged(int channel, CallbackInfo ci) {
        if (xnetadditions$isEditingPreset()) {
            if (channel == xnetadditions$editingPresetChannel) return;
            boolean navigationClick = xnetadditions$consumeControllerLeftClick();
            if (!navigationClick
                    || xnetadditions$batchEditor != null && xnetadditions$batchEditor.hasChanges()) {
                ci.cancel();
                xnetadditions$selectChannelEditor(xnetadditions$editingPresetChannel);
                return;
            }
            xnetadditions$closePresetPreview();
        }
        xnetadditions$setPresetSaveMode(false);
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
        if (!xnetadditions$hasStableClientSnapshot()) {
            ci.cancel();
            return;
        }
        if (xnetadditions$panelDirty) {xnetadditions$rebuildBatchPanel();}
        ci.cancel();
    }

    @Inject(method = "drawGuiContainerBackgroundLayer", at = @At("TAIL"), remap = true)
    private void xnetadditions$drawBatchChannel(float partialTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (xnetadditions$batchEditor != null && xnetadditions$batchEditor.consumeEditorRebuild()) {
            xnetadditions$rebuildBatchEditor();
        }
        xnetadditions$updateToolbar();
        if (xnetadditions$batchEditor != null && (xnetadditions$editing || xnetadditions$isEditingPreset())) {
            GuiController gui = (GuiController) (Object) this;
            Rectangle main = gui.getWindow().getToplevel().getBounds();
            Rectangle editor = connectorEditPanel.getBounds();
            xnetadditions$batchEditor.drawArmedFrames(main.x + editor.x, main.y + editor.y);
        }
        if (xnetadditions$notice != null && Minecraft.getSystemTime() >= xnetadditions$noticeUntil) {
            xnetadditions$notice = null;
        }
        boolean showBatchChannel = !xnetadditions$selection.isEmpty() && xnetadditions$batchChannel >= 0;
        if (!showBatchChannel && xnetadditions$notice == null) return;

        GuiController gui = (GuiController) (Object) this;
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        if (showBatchChannel) {
            int x = main.x + xnetadditions$batchChannel * 14 + 41;
            RenderHelper.drawVerticalGradientRect(x, main.y + 22, x + 12, main.y + 230,
                    0x44ffb000, 0x44ffb000);
        }
        if (xnetadditions$notice != null
                && xnetadditions$toolbarPanel != null
                && xnetadditions$toolbarPanel.getBounds() != null) {
            Rectangle toolbar = xnetadditions$toolbarPanel.getBounds();
            int y = Math.max(1, toolbar.y - 10);
            Minecraft.getMinecraft().fontRenderer.drawStringWithShadow(
                    xnetadditions$notice, main.x + 2, y, xnetadditions$noticeColor);
        }
    }
    @Override
    @Unique
    public void xnetadditions$recordControllerMouseClick(int button, long eventNanos) {
        if (button == 0) {xnetadditions$lastMouseEventNanos = eventNanos;}
    }
    @Override
    @Unique
    public boolean xnetadditions$showBatchResult(BlockPos controllerPos, String message) {
        TileEntityController controller = ((GuiController) (Object) this).getTileEntity();
        if (!controller.getPos().equals(controllerPos)) return false;
        xnetadditions$showNotice(message, 0xffffffff);
        return true;
    }
    @Unique
    private boolean xnetadditions$consumeControllerLeftClick() {
        if (Mouse.getEventButton() != 0
                || Mouse.getEventNanoseconds() != xnetadditions$lastMouseEventNanos) {
            return false;
        }
        xnetadditions$lastMouseEventNanos = Long.MIN_VALUE;
        return true;
    }
    @Override
    @Unique
    public boolean xnetadditions$handleBatchLShiftClick(Widget<?> widget) {
        if (xnetadditions$editing) {
            if (xnetadditions$batchEditor == null) return false;
            boolean handled = xnetadditions$batchEditor.toggleArmed(widget);
            if (handled) xnetadditions$toolbarState = Integer.MIN_VALUE;
            return handled;
        }
        if (connectorList == null || connectorPositions == null) return false;

        SidedPos sidedPos = null;
        int channel = -1;
        for (int row = 0; row < connectorList.getChildCount() && row < connectorPositions.size(); row++) {
            Widget<?> rowWidget = connectorList.getChild(row);
            if (!(rowWidget instanceof AbstractContainerWidget)) continue;
            int child = ((AbstractContainerWidget<?>) rowWidget).getChildren().indexOf(widget);
            if (child < 2 || child >= 2 + MAX_CHANNELS) continue;
            sidedPos = connectorPositions.get(row);
            channel = child - 2;
            break;
        }
        if (sidedPos == null) return false;
        // Check this only after identifying a connector row so LShift interactions inside
        // the preset editor (filters, counts, and JEI controls) still reach their widgets.
        if (xnetadditions$isEditingPreset()) {
            if (xnetadditions$batchEditor != null && xnetadditions$batchEditor.hasChanges()) return true;
            xnetadditions$closePresetPreview();
        }

        if (xnetadditions$presetSaveMode) xnetadditions$setPresetSaveMode(false);
        if (!xnetadditions$isChannelSupported(channel)) {
            connectorList.setSelected(-1);
            if (xnetadditions$getChannelInfo(channel) != null) xnetadditions$showUnsupported(channel);
            return true;
        }
        if (xnetadditions$batchChannel != -1 && xnetadditions$batchChannel != channel) {
            connectorList.setSelected(-1);
            xnetadditions$showNotice("Batch is on channel " + (xnetadditions$batchChannel + 1), 0xffffe080);
            return true;
        }

        xnetadditions$batchChannel = channel;
        if (xnetadditions$selection.contains(sidedPos)) {
            xnetadditions$selection.remove(sidedPos);
        } else {
            if (xnetadditions$selection.size() >= PacketBatchConnectorUpdate.MAX_TARGETS) {
                connectorList.setSelected(-1);
                xnetadditions$showNotice("Maximum " + PacketBatchConnectorUpdate.MAX_TARGETS + " targets", 0xffffe080);
                return true;
            }
            xnetadditions$selection.add(sidedPos);
        }
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
        return true;
    }

    @Inject(method = "handleMouseClick", at = @At("HEAD"), cancellable = true, remap = true)
    private void xnetadditions$batchFilterQuickMove(Slot slotIn, int slotId, int mouseButton, ClickType type, CallbackInfo ci) {
        if ((!xnetadditions$editing && !xnetadditions$isEditingPreset()) || xnetadditions$batchEditor == null || !xnetadditions$batchEditor.hasGhostSlots()
                || slotIn == null || type != ClickType.QUICK_MOVE || !slotIn.getHasStack()) return;
        xnetadditions$batchEditor.addToFirstEmptyGhostSlot(slotIn.getStack());
        ci.cancel();
    }

    @Inject(method = "canSetJeiRecipeFilters", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$canSetBatchJeiRecipeFilters(CallbackInfoReturnable<Boolean> cir) {
        if (!xnetadditions$editing && !xnetadditions$isEditingPreset()) return;
        String typeId = xnetadditions$getStagedTypeId();
        cir.setReturnValue("xnet.item".equals(typeId) || "xnet.fluid".equals(typeId));
    }

    @Inject(method = "getJeiRecipeFilterItemMode", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$getBatchJeiItemMode(CallbackInfoReturnable<ItemConnectorSettings.ItemMode> cir) {
        if (!xnetadditions$editing && !xnetadditions$isEditingPreset()) return;
        String typeId = xnetadditions$getStagedTypeId();
        if (!"xnet.item".equals(typeId)) {
            cir.setReturnValue(null);
            return;
        }
        Object mode = xnetadditions$batchEditor.getValue(ItemConnectorSettings.TAG_MODE);
        try {
            cir.setReturnValue(mode instanceof String ? ItemConnectorSettings.ItemMode.valueOf(((String) mode).toUpperCase(Locale.ROOT)) : null);
        } catch (IllegalArgumentException e) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getJeiRecipeFilterFluidMode", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$getBatchJeiFluidMode(CallbackInfoReturnable<FluidConnectorSettings.FluidMode> cir) {
        if (!xnetadditions$editing && !xnetadditions$isEditingPreset()) return;
        String typeId = xnetadditions$getStagedTypeId();
        if (!"xnet.fluid".equals(typeId)) {
            cir.setReturnValue(null);
            return;
        }
        Object mode = xnetadditions$batchEditor.getValue(FluidConnectorSettings.TAG_MODE);
        try {
            cir.setReturnValue(mode instanceof String ? FluidConnectorSettings.FluidMode.valueOf(((String) mode).toUpperCase(Locale.ROOT)) : null);
        } catch (IllegalArgumentException e) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getJeiRecipeFilterLimit", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$getBatchJeiFilterLimit(CallbackInfoReturnable<Integer> cir) {
        if (!xnetadditions$editing && !xnetadditions$isEditingPreset()) return;
        String typeId = xnetadditions$getStagedTypeId();
        if ("xnet.item".equals(typeId)) cir.setReturnValue(ItemConnectorSettings.FILTER_SIZE);
        else if ("xnet.fluid".equals(typeId)) cir.setReturnValue(FluidConnectorSettings.FILTER_SIZE);
        else cir.setReturnValue(0);
    }

    @Inject(method = "setJeiRecipeFilters", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$setBatchJeiItemFilters(XNetJeiItemFilterCollector.Result result, CallbackInfo ci) {
        if (!xnetadditions$editing && !xnetadditions$isEditingPreset()) return;
        String typeId = xnetadditions$getStagedTypeId();
        if ("xnet.item".equals(typeId)) {
            xnetadditions$batchEditor.setJeiRecipeFilters(result);
            xnetadditions$toolbarState = Integer.MIN_VALUE;
        }
        ci.cancel();
    }

    @Inject(method = "setJeiFluidRecipeFilters", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$setBatchJeiFluidFilters(XNetJeiFluidFilterCollector.Result result, CallbackInfo ci) {
        if (!xnetadditions$editing && !xnetadditions$isEditingPreset()) return;
        String typeId = xnetadditions$getStagedTypeId();
        if ("xnet.fluid".equals(typeId)) {
            xnetadditions$batchEditor.setJeiFluidRecipeFilters(result);
            xnetadditions$toolbarState = Integer.MIN_VALUE;
        }
        ci.cancel();
    }

    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true, remap = true)
    private void xnetadditions$escapeBatch(char typedChar, int keyCode, CallbackInfo ci) throws IOException {
        if (xnetadditions$isEditingPreset() && (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN)) {
            if (xnetadditions$batchEditor != null && xnetadditions$batchEditor.hasChanges()) {
                ci.cancel();
            } else {
                xnetadditions$closePresetPreview();
                xnetadditions$updateToolbar();
            }
            return;
        }
        if (keyCode != Keyboard.KEY_ESCAPE) {return;}
        if (xnetadditions$presetSaveMode) {
            GuiController gui = (GuiController) (Object) this;
            WindowManager manager = gui.getWindow().getWindowManager();
            Window modal = manager.getModalWindows().reduce((first, second) -> second).orElse(null);
            if (modal != null) {
                manager.closeWindow(modal);
            } else {
                xnetadditions$setPresetSaveMode(false);
                xnetadditions$updateToolbar();
            }
            ci.cancel();
            return;
        }

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
            xnetadditions$rebuildToolbarLayout();
            xnetadditions$updateToolbar();
        } else {
            xnetadditions$clearBatch();
        }
        ci.cancel();
    }

    @Unique
    private void xnetadditions$selectVisible() {
        if (xnetadditions$isEditingPreset()) {
            xnetadditions$closePresetPreview();
            xnetadditions$updateToolbar();
            return;
        }
        if (xnetadditions$editing) {
            xnetadditions$editing = false;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$rebuildToolbarLayout();
            xnetadditions$updateToolbar();
            return;
        }
        if (!xnetadditions$hasStableClientSnapshot()) {
            return;
        }
        int channel = ((GuiController) (Object) this).getSelectedChannel();
        if (channel < 0 || xnetadditions$getChannelInfo(channel) == null) {
            return;}

        if (!xnetadditions$isChannelSupported(channel)) {xnetadditions$showUnsupported(channel);
            return;}
        if (xnetadditions$batchChannel != -1 && xnetadditions$batchChannel != channel) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                    TextFormatting.YELLOW + "Batch selection is on channel " + (xnetadditions$batchChannel + 1));
            return;
        }

        Set<SidedPos> visible = connectorPositions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(connectorPositions);
        if (visible.isEmpty()) {GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                TextFormatting.YELLOW + "No visible targets on channel " + (channel + 1));
            return;
        }

        Set<SidedPos> combined = new LinkedHashSet<>(xnetadditions$selection);
        combined.addAll(visible);
        if (combined.size() > PacketBatchConnectorUpdate.MAX_TARGETS) {
            xnetadditions$showNotice("Maximum " + PacketBatchConnectorUpdate.MAX_TARGETS + " targets", 0xffffe080);
            return;
        }
        xnetadditions$selection.addAll(visible);
        xnetadditions$batchChannel = channel;
        xnetadditions$setPresetSaveMode(false);
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
    private void xnetadditions$editOrApply(boolean exact) {
        if (xnetadditions$isEditingPreset()) return;
        if (!xnetadditions$hasStableClientSnapshot()) {
            return;
        }
        if (xnetadditions$configuredCount <= 0 || xnetadditions$reference == null) {
            return;
        }
        if (!xnetadditions$editing) {
            if (exact) return;
            xnetadditions$setPresetSaveMode(false);
            xnetadditions$previewPresetSlot = -1;
            xnetadditions$editing = true;
            xnetadditions$panelDirty = true;
            xnetadditions$rebuildToolbarLayout();
            xnetadditions$updateToolbar();
            return;
        }
        if (xnetadditions$batchEditor == null) {
            return;
        }
        Map<String, Object> changes = exact ? xnetadditions$batchEditor.getAllValues() : xnetadditions$batchEditor.getChangedValues();
        if (changes.isEmpty()) {
            xnetadditions$editing = false;
            xnetadditions$panelDirty = true;
            xnetadditions$rebuildToolbarLayout();
            xnetadditions$updateToolbar();
            return;
        }

        List<SidedPos> configuredTargets = xnetadditions$getConfiguredTargets();
        if (configuredTargets.isEmpty()) {
            xnetadditions$editing = false;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$rebuildToolbarLayout();
            xnetadditions$updateToolbar();
            return;
        }
        TileEntityController controller = ((GuiController) (Object) this).getTileEntity();
        BatchEditNetwork.CHANNEL.sendToServer(new PacketBatchConnectorUpdate(controller.getPos(), xnetadditions$batchChannel, configuredTargets, changes));
        xnetadditions$editing = false;
        xnetadditions$batchEditor = null;
        xnetadditions$panelDirty = true;

        ((GuiController) (Object) this).refresh();
        xnetadditions$rebuildToolbarLayout();
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$rebuildBatchEditor() {
        if (xnetadditions$isEditingPreset() && xnetadditions$batchEditor != null) {
            xnetadditions$captureEditorState();
            connectorEditPanel.removeChildren();
            xnetadditions$batchEditor = null;
            xnetadditions$rebuildPresetEditor((GuiController) (Object) this, Minecraft.getMinecraft());
            xnetadditions$toolbarState = Integer.MIN_VALUE;
            return;
        }
        if (!xnetadditions$editing || xnetadditions$batchEditor == null || xnetadditions$reference == null) return;

        ChannelClientInfo channel = xnetadditions$getChannelInfo(xnetadditions$batchChannel);
        ConnectorClientInfo clientInfo = xnetadditions$getClientInfo(xnetadditions$batchChannel, xnetadditions$reference);
        if (channel == null || clientInfo == null || !BatchEditSupport.isSupported(channel.getType().getID())) return;

        Map<String, Object> values = xnetadditions$batchEditor.getAllValues();
        Map<String, Object> changed = xnetadditions$batchEditor.getChangedValues();
        String originalMode = xnetadditions$batchEditor.getOriginalMode();
        boolean advanced = xnetadditions$batchEditor.isAdvanced();

        try {
            JsonObject json = clientInfo.getConnectorSettings().writeToJson();
            EnumFacing side = json != null && json.has("side") ? EnumFacing.byName(json.get("side").getAsString()) : null;
            if (side == null) throw new IllegalStateException();

            IConnectorSettings working = channel.getType().createConnector(side);
            working.readFromJson(json);
            working.update(values);
            working.sanitizeSettings(advanced);

            connectorEditPanel.removeChildren();
            BatchConnectorEditorPanel editor = new BatchConnectorEditorPanel(
                    connectorEditPanel, Minecraft.getMinecraft(), (GuiController) (Object) this, advanced,
                    BatchEditSupport.supportsDirection(channel.getType().getID()));
            editor.setOriginalMode(originalMode);
            working.createGui(editor);
            editor.setState(working);
            editor.restoreChangedValues(changed);
            xnetadditions$batchEditor = editor;
            xnetadditions$toolbarState = Integer.MIN_VALUE;
        } catch (RuntimeException | LinkageError e) {
            xnetadditions$editing = false;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$rebuildBatchPanel();
            xnetadditions$rebuildToolbarLayout();
        }
    }

    @Unique
    private void xnetadditions$rebuildBatchPanel() {
        connectorEditPanel.removeChildren();
        xnetadditions$batchEditor = null;
        GuiController gui = (GuiController) (Object) this;
        Minecraft mc = Minecraft.getMinecraft();

        if (xnetadditions$previewPresetSlot >= 0) {
            xnetadditions$rebuildPresetEditor(gui, mc);
            xnetadditions$panelDirty = xnetadditions$previewPresetSlot < 0;
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
            xnetadditions$rebuildToolbarLayout();
            return;
        }

        ConnectorClientInfo clientInfo = xnetadditions$getClientInfo(
                xnetadditions$batchChannel, xnetadditions$reference);
        if (clientInfo == null) {
            xnetadditions$editing = false;
            xnetadditions$panelDirty = true;
            xnetadditions$rebuildToolbarLayout();
            return;
        }

        boolean advanced = ConnectorBlock.isAdvancedConnector(
                mc.world,
                xnetadditions$reference.getPos().offset(xnetadditions$reference.getSide())
        );
        ChannelClientInfo channel = xnetadditions$getChannelInfo(xnetadditions$batchChannel);
        IConnectorSettings settings = clientInfo.getConnectorSettings();
        boolean allowMode = channel != null && BatchEditSupport.supportsDirection(channel.getType().getID());
        if (xnetadditions$restoreValues != null && channel != null) {
            try {
                JsonObject json = settings.writeToJson();
                EnumFacing side = json != null && json.has("side") ? EnumFacing.byName(json.get("side").getAsString()) : null;
                if (side == null) throw new IllegalStateException();
                IConnectorSettings working = channel.getType().createConnector(side);
                working.readFromJson(json);
                working.update(xnetadditions$restoreValues);
                working.sanitizeSettings(advanced);
                settings = working;
            } catch (RuntimeException | LinkageError e) {
                xnetadditions$editing = false;
                xnetadditions$clearEditorRestore();
                xnetadditions$panelDirty = true;
                xnetadditions$rebuildToolbarLayout();
                return;
            }
        }
        xnetadditions$batchEditor = new BatchConnectorEditorPanel(
                connectorEditPanel, mc, gui, advanced, allowMode);
        settings.createGui(xnetadditions$batchEditor);
        xnetadditions$batchEditor.setState(settings);
        if (xnetadditions$restoreOriginalMode != null) xnetadditions$batchEditor.setOriginalMode(xnetadditions$restoreOriginalMode);
        if (xnetadditions$restoreChanges != null) xnetadditions$batchEditor.restoreChangedValues(xnetadditions$restoreChanges);
        xnetadditions$clearEditorRestore();
        xnetadditions$panelDirty = false;
    }

    @Unique
    private void xnetadditions$rebuildPresetEditor(GuiController gui, Minecraft mc) {
        String typeId = xnetadditions$editingPresetTypeId;
        int slot = xnetadditions$previewPresetSlot;
        String json = xnetadditions$editingPresetJson;
        IChannelType channelType = typeId == null ? null : XNet.xNetApi.findType(typeId);
        if (typeId == null || json == null || channelType == null) {
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
            IConnectorSettings settings = channelType.createConnector(side);
            settings.readFromJson(connectorJson);
            if (xnetadditions$restoreValues != null) settings.update(xnetadditions$restoreValues);
            settings.sanitizeSettings(advanced);

            Button remove = new Button(mc, gui).setText("x").setTextOffset(0, -1).setTooltips("Delete preset P" + (slot + 1))
                    .setLayoutHint(new PositionalLayout.PositionalHint(151, 1, 9, 10))
                    .addButtonEvent(parent -> xnetadditions$confirmDeletePreset(typeId, slot));

            BatchConnectorEditorPanel editor = new BatchConnectorEditorPanel(connectorEditPanel, mc, gui, advanced, true);
            settings.createGui(editor);
            editor.setState(settings);
            root.add("connector", settings.writeToJson());
            xnetadditions$editingPresetJson = xnetadditions$PRESET_GSON.toJson(root);
            if (xnetadditions$restoreOriginalMode != null) editor.setOriginalMode(xnetadditions$restoreOriginalMode);
            if (xnetadditions$restoreOriginalValues == null) editor.beginActualChangeTracking();
            else editor.restoreOriginalValues(xnetadditions$restoreOriginalValues, xnetadditions$restoreChanges);
            xnetadditions$batchEditor = editor;
            connectorEditPanel.addChild(remove);
            xnetadditions$clearEditorRestore();
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
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object contents = clipboard.getData(DataFlavor.stringFlavor);
            if (!(contents instanceof String)) {
                throw new IllegalArgumentException(
                        "Clipboard does not contain text"
                );
            }

            String json = (String) contents;
            if (json.getBytes(StandardCharsets.UTF_8).length > PacketBatchConnectorMutation.MAX_JSON_BYTES) {
                GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this, ((GuiController) (Object) this)
                        .getWindow().getWindowManager(), TextFormatting.RED + "Clipboard is too large!"
                );
                return;
            }

            xnetadditions$sendMutation(
                    PacketBatchConnectorMutation.Operation.PASTE,
                    json
            );
        } catch (Exception e) {GuiController.showMessage(Minecraft.getMinecraft(),
                (GuiController) (Object) this, ((GuiController) (Object) this).getWindow().getWindowManager(),
                TextFormatting.RED + "Clipboard does not contain connector settings!"
        );
        }
    }

    @Unique
    private void xnetadditions$confirmDelete() {
        if (xnetadditions$configuredCount <= 0) {
            return;
        }
        GuiController gui = (GuiController) (Object) this;
        GuiController.showMessage(Minecraft.getMinecraft(), gui, gui.getWindow().getWindowManager(), TextFormatting.RED
                + "Delete " + xnetadditions$configuredCount + " connector configurations?", parent -> xnetadditions$sendMutation(PacketBatchConnectorMutation.Operation.DELETE, "")
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

        TileEntityController controller = ((GuiController) (Object) this).getTileEntity();
        BatchEditNetwork.CHANNEL.sendToServer(new PacketBatchConnectorMutation(controller.getPos(), xnetadditions$batchChannel, operation, new ArrayList<>(xnetadditions$selection), clipboardJson
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
        List<SidedPos> configured = new ArrayList<>(xnetadditions$configuredCount);
        for (SidedPos target : xnetadditions$selection) {
            if (xnetadditions$hasConnector(xnetadditions$batchChannel, target
            )) {configured.add(target);}
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
        boolean keepPresetEditor = xnetadditions$isEditingPreset();
        xnetadditions$setPresetSaveMode(false);
        xnetadditions$selection.clear();
        xnetadditions$batchChannel = -1;
        xnetadditions$reference = null;
        xnetadditions$configuredCount = 0;
        xnetadditions$emptyCount = 0;
        xnetadditions$editing = false;
        if (!keepPresetEditor) {
            xnetadditions$previewPresetSlot = -1;
            xnetadditions$batchEditor = null;
            xnetadditions$clearEditorRestore();
        }
        xnetadditions$panelDirty = !keepPresetEditor || xnetadditions$batchEditor == null;
        xnetadditions$toolbarState = Integer.MIN_VALUE;

        showingConnector = null;

        if (connectorList != null) {
            connectorList.clearHilightedRows();
        }

        if (connectorEditPanel != null && !keepPresetEditor) {
            connectorEditPanel.removeChildren();
        }
        xnetadditions$rebuildToolbarLayout();
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$pruneSelection() {
        if (xnetadditions$isEditingPreset()) {
            ChannelClientInfo channel = xnetadditions$getChannelInfo(xnetadditions$editingPresetChannel);
            if (channel == null || !xnetadditions$editingPresetTypeId.equals(channel.getType().getID())) {
                xnetadditions$closePresetPreview();
                return;
            }
        }
        if (xnetadditions$selection.isEmpty()
                || GuiController.fromServer_channels == null
                || GuiController.fromServer_connectedBlocks == null) {
            return;
        }

        if (!xnetadditions$isChannelSupported(xnetadditions$batchChannel)) {
            xnetadditions$clearBatch();
            return;
        }

        Set<SidedPos> connected = new HashSet<>();
        for (ConnectedBlockClientInfo block : GuiController.fromServer_connectedBlocks) {
            connected.add(block.getPos());
        }

        boolean removed = xnetadditions$selection.removeIf(pos -> !connected.contains(pos));
        if (xnetadditions$selection.isEmpty()) {
            xnetadditions$clearBatch();
            return;
        }
        xnetadditions$recalculateCounts();
        if (xnetadditions$reference == null || !xnetadditions$selection.contains(xnetadditions$reference
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
        if (xnetadditions$batchEditor != null && (xnetadditions$editing || xnetadditions$isEditingPreset())) xnetadditions$captureEditorState();
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
    private void xnetadditions$showNotice(String text, int color) {
        xnetadditions$notice = text;
        xnetadditions$noticeColor = color;
        xnetadditions$noticeUntil = Minecraft.getSystemTime() + 2500L;
    }

    @Unique
    private void xnetadditions$showUnsupported(int channel) {
        xnetadditions$showNotice("Batch Edit unsupported here", 0xffffe080);
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

        if (xnetadditions$toolbarPanel == null || xnetadditions$presetToggleButton == null
                || xnetadditions$presetSaveButton == null || xnetadditions$presetSaveHint == null
                || xnetadditions$presetNameField == null || xnetadditions$selectButton == null || xnetadditions$editButton == null
                || xnetadditions$exactButton == null || xnetadditions$toolbarVisibilityButton == null) {
            return;
        }

        GuiController gui = (GuiController) (Object) this;
        Rectangle main = gui.getWindow().getToplevel().getBounds();
        boolean toolbarVisible = xnetadditions$toolbarVisible;
        boolean saveMode = xnetadditions$presetSaveMode;
        boolean presetEditing = xnetadditions$isEditingPreset();
        boolean expanded = toolbarVisible && (xnetadditions$presetsExpanded || saveMode || presetEditing);
        int height = expanded ? 36 : 18;
        int toolbarY = Math.max(0, main.y - height - 2);
        int presetRowY = expanded ? 2 : -1;
        int mainRowY = expanded ? 20 : 2;
        int panelWidth = toolbarVisible ? main.width : 18;
        int panelX = toolbarVisible ? main.x : main.x + main.width - panelWidth;

        xnetadditions$toolbarPanel.setFilledBackground(saveMode || presetEditing ? 0xff594600 : 0xff3f3f3f);
        xnetadditions$toolbarPanel.setBounds(new Rectangle(panelX, toolbarY, panelWidth, height));
        xnetadditions$toolbarPanel.removeChildren();

        xnetadditions$toolbarVisibilityButton.setText(toolbarVisible ? "-" : "+").setEnabled(!saveMode && !presetEditing)
                .setTooltips(saveMode || presetEditing
                        ? new String[]{presetEditing ? "Finish or cancel preset editing first" : "Finish or cancel preset saving first", "Press Escape to cancel"}
                        : new String[]{toolbarVisible ? "Hide batch and preset toolbar" : "Show batch and preset toolbar"})
                .setLayoutHint(new PositionalLayout.PositionalHint(toolbarVisible ? main.width - 16 : 2,
                        toolbarVisible ? mainRowY : 2, 14, 14));
        if (!presetEditing) xnetadditions$toolbarPanel.addChild(xnetadditions$toolbarVisibilityButton);

        if (!toolbarVisible) {xnetadditions$toolbarState = Integer.MIN_VALUE;
            return;
        }

        if (saveMode) {
            xnetadditions$presetSaveHint.setText("Name:").setLayoutHint(new PositionalLayout.PositionalHint(2, mainRowY, 30, 14));
            xnetadditions$presetNameField.setLayoutHint(new PositionalLayout.PositionalHint(34, mainRowY, Math.max(1, main.width - 54), 14));
            xnetadditions$toolbarPanel.addChild(xnetadditions$presetSaveHint).addChild(xnetadditions$presetNameField);
        } else {
            xnetadditions$presetToggleButton.setLayoutHint(new PositionalLayout.PositionalHint(2, mainRowY, 54, 14));
            if (xnetadditions$editing) {
                int partialWidth = Math.max(1, (main.width - 134) / 2);
                xnetadditions$selectButton.setLayoutHint(new PositionalLayout.PositionalHint(58, mainRowY, 54, 14));
                xnetadditions$editButton.setLayoutHint(new PositionalLayout.PositionalHint(114, mainRowY, partialWidth, 14));
                xnetadditions$exactButton.setLayoutHint(new PositionalLayout.PositionalHint(116 + partialWidth, mainRowY, Math.max(1, main.width - 134 - partialWidth), 14));
                xnetadditions$toolbarPanel.addChild(xnetadditions$presetToggleButton).addChild(xnetadditions$selectButton).addChild(xnetadditions$editButton).addChild(xnetadditions$exactButton);
            } else if (presetEditing) {
                xnetadditions$selectButton.setLayoutHint(new PositionalLayout.PositionalHint(200, mainRowY, Math.max(52, main.width - 202), 14));
                xnetadditions$toolbarPanel.addChild(xnetadditions$presetToggleButton).addChild(xnetadditions$selectButton);
            } else {
                xnetadditions$selectButton.setLayoutHint(new PositionalLayout.PositionalHint(58, mainRowY, 120, 14));
                xnetadditions$editButton.setLayoutHint(new PositionalLayout.PositionalHint(180, mainRowY, Math.max(1, main.width - 198), 14));
                xnetadditions$toolbarPanel.addChild(xnetadditions$presetToggleButton).addChild(xnetadditions$selectButton).addChild(xnetadditions$editButton);
            }
        }

        if (expanded) {
            String typeId = presetEditing ? xnetadditions$editingPresetTypeId : xnetadditions$getActiveTypeId();
            xnetadditions$presetLayoutHasType = typeId != null;
            if (typeId == null && !saveMode) {
                xnetadditions$presetSaveHint.setText("Select a channel to view presets")
                        .setLayoutHint(new PositionalLayout.PositionalHint(2, presetRowY, Math.max(1, main.width - 4), 14));
                xnetadditions$toolbarPanel.addChild(xnetadditions$presetSaveHint);
            } else {
                for (int slot = 0; slot < ConnectorPresetStore.SLOT_COUNT; slot++) {
                    xnetadditions$presetButtons[slot].setLayoutHint(new PositionalLayout.PositionalHint(2 + slot * 22, presetRowY, 20, 14));
                    xnetadditions$toolbarPanel.addChild(xnetadditions$presetButtons[slot]);
                }
                xnetadditions$presetSaveButton.setLayoutHint(new PositionalLayout.PositionalHint(200, presetRowY, Math.max(52, main.width - 202), 14));
                xnetadditions$toolbarPanel.addChild(xnetadditions$presetSaveButton);
            }
        }
        xnetadditions$toolbarState = Integer.MIN_VALUE;
    }

    @Unique
    private void xnetadditions$toggleToolbarVisibility() {
        if (xnetadditions$presetSaveMode || xnetadditions$isEditingPreset()) {return;}
        xnetadditions$toolbarVisible = !xnetadditions$toolbarVisible;
        xnetadditions$rebuildToolbarLayout();
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$togglePresetBar() {
        if (xnetadditions$editing) return;
        if (xnetadditions$isEditingPreset() && xnetadditions$batchEditor != null
                && xnetadditions$batchEditor.hasChanges()) return;
        xnetadditions$presetsExpanded = !xnetadditions$presetsExpanded;
        xnetadditions$setPresetSaveMode(false);
        xnetadditions$closePresetPreview();
        xnetadditions$panelDirty = true;
        xnetadditions$rebuildToolbarLayout();
        xnetadditions$updateToolbar();
    }

    @Unique
    private int xnetadditions$getActiveChannel() {
        if (!xnetadditions$selection.isEmpty() && xnetadditions$batchChannel >= 0) {
            return xnetadditions$batchChannel;
        }
        return ((GuiController) (Object) this).getSelectedChannel();
    }

    @Unique
    private String xnetadditions$getActiveTypeId() {
        ChannelClientInfo channel = xnetadditions$getChannelInfo(xnetadditions$getActiveChannel());
        return channel == null ? null : channel.getType().getID();
    }

    @Unique
    private int xnetadditions$getSelectedPresetSlot() {
        if (!xnetadditions$presetsExpanded) {
            return -1;
        }

        return ConnectorPresetStore.getSelectedSlot(xnetadditions$getActiveTypeId());
    }

    @Unique
    private String xnetadditions$getSelectedPresetJson() {
        String typeId = xnetadditions$getActiveTypeId();
        int slot = xnetadditions$getSelectedPresetSlot();

        if (typeId == null || slot < 0) {
            return null;
        }
        return ConnectorPresetStore.getPresetJson(typeId, slot);
    }

    @Unique
    private SidedPos xnetadditions$getPresetSource() {
        /*
         * A batch is only an unambiguous source when exactly one configured
         * connector is selected.
         */
        if (!xnetadditions$selection.isEmpty()) {return xnetadditions$configuredCount == 1 ? xnetadditions$reference : null;
        }
        int channel = ((GuiController) (Object) this).getSelectedChannel();
        if (editingConnector != null && xnetadditions$hasConnector(channel, editingConnector
        )) {return editingConnector;
        }
        return null;
    }

    @Unique
    private int xnetadditions$getPresetSourceChannel() {
        if (!xnetadditions$selection.isEmpty()) {
            return xnetadditions$configuredCount == 1 ? xnetadditions$batchChannel : -1;
        }

        int channel = ((GuiController) (Object) this).getSelectedChannel();

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
        xnetadditions$editingPresetTypeId = null;
        xnetadditions$editingPresetJson = null;
        xnetadditions$editingPresetChannel = -1;
        xnetadditions$batchEditor = null;
        xnetadditions$clearEditorRestore();
        if (xnetadditions$selection.isEmpty()) {showingConnector = null;}
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        xnetadditions$rebuildToolbarLayout();
    }

    @Unique
    private void xnetadditions$confirmDeletePreset(String typeId, int slot) {
        GuiController gui = (GuiController) (Object) this;
        GuiController.showMessage(Minecraft.getMinecraft(), gui, gui.getWindow().getWindowManager(),
                TextFormatting.RED + "Delete preset P" + (slot + 1) + "?",
                parent -> {
                    if (ConnectorPresetStore.deletePreset(typeId, slot)) {
                        xnetadditions$closePresetPreview();
                        xnetadditions$panelDirty = true;
                        xnetadditions$toolbarState = Integer.MIN_VALUE;
                        xnetadditions$updateToolbar();
                    } else {
                        xnetadditions$showNotice("Could not delete preset P" + (slot + 1), 0xffff8080);
                    }
                });
    }

    @Unique
    private void xnetadditions$setPresetSaveMode(boolean enabled) {
        boolean changed = xnetadditions$presetSaveMode != enabled;
        xnetadditions$presetSaveMode = enabled;
        if (!enabled) {
            xnetadditions$presetSaveTypeId = null;
            xnetadditions$presetSaveJson = null;
        }
        if (!changed) {return;}
        if (xnetadditions$presetNameField != null) {xnetadditions$presetNameField.setText("");}
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        xnetadditions$rebuildToolbarLayout();
    }

    @Unique
    private void xnetadditions$togglePresetSaveMode() {
        if (xnetadditions$isEditingPreset()) {
            xnetadditions$savePresetChanges();
            return;
        }
        if (xnetadditions$presetSaveMode) {
            xnetadditions$setPresetSaveMode(false);
            xnetadditions$updateToolbar();
            return;
        }
        if (!xnetadditions$hasStableClientSnapshot()) {return;}
        if (xnetadditions$getPresetSource() == null) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this,
                    ((GuiController) (Object) this).getWindow().getWindowManager(),
                    TextFormatting.YELLOW + "Select exactly one configured connector");
            return;
        }

        String typeId = xnetadditions$getActiveTypeId();
        String json = xnetadditions$buildPresetJson();
        if (typeId == null || json == null) {
            GuiController.showMessage(Minecraft.getMinecraft(), (GuiController) (Object) this,
                    ((GuiController) (Object) this).getWindow().getWindowManager(),
                    TextFormatting.RED + "This connector cannot be saved");
            return;
        }

        xnetadditions$closePresetPreview();
        xnetadditions$presetSaveTypeId = typeId;
        xnetadditions$presetSaveJson = json;
        xnetadditions$setPresetSaveMode(true);
        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$clickPresetSlot(int slot) {
        if (xnetadditions$presetSaveMode) {
            if (xnetadditions$presetSaveTypeId != null && xnetadditions$presetSaveJson != null) {
                xnetadditions$savePresetToSlot(xnetadditions$presetSaveTypeId, slot, xnetadditions$presetSaveJson, xnetadditions$presetNameField.getText());
            }
            return;
        }
        if (!xnetadditions$hasStableClientSnapshot()) return;
        if (xnetadditions$isEditingPreset()) {
            if (xnetadditions$batchEditor != null && xnetadditions$batchEditor.hasChanges()) return;
            xnetadditions$closePresetPreview();
        }
        String typeId = xnetadditions$getActiveTypeId();
        if (typeId == null) {return;}

        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
            if (!ConnectorPresetStore.hasPreset(typeId, slot)) {return;}
            String json = ConnectorPresetStore.getPresetJson(typeId, slot);
            int channel = xnetadditions$getActiveChannel();
            if (json == null || channel < 0) {return;}
            xnetadditions$closePresetPreview();
            ConnectorPresetStore.setSelectedSlot(typeId, slot);
            xnetadditions$previewPresetSlot = slot;
            xnetadditions$editingPresetTypeId = typeId;
            xnetadditions$editingPresetJson = json;
            xnetadditions$editingPresetChannel = channel;
            xnetadditions$batchEditor = null;
            xnetadditions$panelDirty = true;
            xnetadditions$toolbarState = Integer.MIN_VALUE;
            xnetadditions$rebuildToolbarLayout();
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
    private void xnetadditions$savePresetToSlot(String typeId, int slot, String json, String name) {
        boolean occupied = ConnectorPresetStore.hasPreset(typeId, slot);
        ToggleButton button = xnetadditions$presetButtons[slot];
        if (button != null) {button.setPressed(occupied);}
        if (occupied) {
            GuiController gui = (GuiController) (Object) this;
            GuiController.showMessage(Minecraft.getMinecraft(), gui, gui.getWindow().getWindowManager(),
                    TextFormatting.YELLOW + "Replace preset P" + (slot + 1) + "?",
                    parent -> xnetadditions$commitPreset(typeId, slot, json, name));
            return;
        }
        xnetadditions$commitPreset(typeId, slot, json, name);
    }

    @Unique
    private void xnetadditions$commitPreset(String typeId, int slot, String json, String name) {
        boolean saved = ConnectorPresetStore.savePreset(typeId, slot, json, name);
        if (saved) {xnetadditions$setPresetSaveMode(false);}
        xnetadditions$panelDirty = true;
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            xnetadditions$showNotice(saved ? "Saved preset P" + (slot + 1) : "Preset save failed", saved ? 0xff80ff80 : 0xffff8080);
        }

        xnetadditions$updateToolbar();
    }

    @Unique
    private void xnetadditions$savePresetChanges() {
        if (!xnetadditions$isEditingPreset() || xnetadditions$batchEditor == null || !xnetadditions$batchEditor.hasChanges()) return;
        String typeId = xnetadditions$editingPresetTypeId;
        int slot = xnetadditions$previewPresetSlot;
        String json = xnetadditions$buildEditedPresetJson();
        boolean saved = json != null && ConnectorPresetStore.savePreset(typeId, slot, json, ConnectorPresetStore.getPresetName(typeId, slot));
        if (saved) xnetadditions$closePresetPreview();
        xnetadditions$showNotice(saved ? "Saved changes to P" + (slot + 1) : "Preset save failed", saved ? 0xff80ff80 : 0xffff8080);
        xnetadditions$toolbarState = Integer.MIN_VALUE;
        xnetadditions$updateToolbar();
    }

    @Unique
    private String xnetadditions$buildEditedPresetJson() {
        if (!xnetadditions$isEditingPreset() || xnetadditions$batchEditor == null) return null;
        try {
            JsonObject original = xnetadditions$PRESET_GSON.fromJson(xnetadditions$editingPresetJson, JsonObject.class);
            JsonObject connectorJson = original.getAsJsonObject("connector");
            EnumFacing side = connectorJson != null && connectorJson.has("side") ? EnumFacing.byName(connectorJson.get("side").getAsString()) : null;
            IChannelType type = XNet.xNetApi.findType(xnetadditions$editingPresetTypeId);
            if (side == null || type == null) return null;
            boolean advanced = original.get("advanced").getAsBoolean();
            IConnectorSettings settings = type.createConnector(side);
            settings.readFromJson(connectorJson);
            settings.update(xnetadditions$batchEditor.getAllValues());
            settings.sanitizeSettings(advanced);
            JsonObject root = new JsonObject();
            root.addProperty("type", xnetadditions$editingPresetTypeId);
            root.add("connector", settings.writeToJson());
            root.addProperty("advanced", advanced);
            String json = xnetadditions$PRESET_GSON.toJson(root);
            return json.getBytes(StandardCharsets.UTF_8).length <= PacketBatchConnectorMutation.MAX_JSON_BYTES ? json : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
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
                || xnetadditions$presetSaveButton == null || xnetadditions$presetSaveHint == null || xnetadditions$exactButton == null) {
            return;
        }
        if (!xnetadditions$toolbarVisible || !xnetadditions$hasStableClientSnapshot()) {return;}

        int selectedChannel = ((GuiController) (Object) this).getSelectedChannel();
        boolean supported = xnetadditions$isChannelSupported(selectedChannel);
        boolean hasEditor = xnetadditions$batchEditor != null;
        boolean hasChanges = hasEditor && xnetadditions$batchEditor.hasChanges();
        int changeCount = hasEditor ? xnetadditions$batchEditor.getChangeCount() : 0;
        boolean presetEditing = xnetadditions$isEditingPreset();
        boolean presetDirty = presetEditing && hasChanges;
        boolean hasPresetSource = xnetadditions$getPresetSource() != null;
        boolean hasPresetPayload = xnetadditions$presetSaveTypeId != null && xnetadditions$presetSaveJson != null;
        if (xnetadditions$presetSaveMode && !hasPresetPayload) {xnetadditions$setPresetSaveMode(false);}

        String typeId = xnetadditions$presetSaveMode ? xnetadditions$presetSaveTypeId : presetEditing ? xnetadditions$editingPresetTypeId : xnetadditions$getActiveTypeId();
        if (xnetadditions$presetsExpanded && !xnetadditions$presetSaveMode && (typeId != null) != xnetadditions$presetLayoutHasType) {
            xnetadditions$rebuildToolbarLayout();
        }
        int selectedPreset = ConnectorPresetStore.getSelectedSlot(typeId);
        int occupiedMask = ConnectorPresetStore.getOccupiedMask(typeId);

        int state = selectedChannel + 2;
        state = 31 * state + (supported ? 1 : 0);
        state = 31 * state + (xnetadditions$editing ? 1 : 0);
        state = 31 * state + xnetadditions$selection.size();
        state = 31 * state + xnetadditions$configuredCount;
        state = 31 * state + xnetadditions$emptyCount;
        state = 31 * state + (hasEditor ? 1 : 0);
        state = 31 * state + (hasChanges ? 1 : 0);
        state = 31 * state + changeCount;
        state = 31 * state + (presetEditing ? 1 : 0);
        state = 31 * state + (xnetadditions$presetsExpanded ? 1 : 0);
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
        xnetadditions$presetToggleButton.setPressed(xnetadditions$presetsExpanded);
        xnetadditions$presetToggleButton.setText(presetEditing ? "P" + (xnetadditions$previewPresetSlot + 1) + " Edit" : "Presets");
        xnetadditions$presetToggleButton.setEnabled(!xnetadditions$editing && !presetDirty);

        if (xnetadditions$editing) {
            xnetadditions$selectButton
                    .setText("Cancel")
                    .setEnabled(true)
                    .setTooltips("Discard staged changes", "Keep the target selection"
                    );
            xnetadditions$editButton
                    .setText("Apply Partial")
                    .setEnabled(hasChanges && xnetadditions$configuredCount > 0)
                    .setTooltips("Apply Partial to " + xnetadditions$configuredCount + " configured targets",
                            "Only armed controls are included", "LShift-click a control to arm/unarm");
            xnetadditions$exactButton
                    .setText("Apply Exact")
                    .setEnabled(hasEditor && xnetadditions$configuredCount > 0)
                    .setTooltips("Apply Exact to " + xnetadditions$configuredCount + " configured targets",
                            "Every control shown in Batch Edit is included", "Armed state is ignored");
        } else if (presetEditing) {
            xnetadditions$selectButton.setText(hasChanges ? "Cancel" : "Close").setEnabled(true)
                    .setTooltips(hasChanges ? "Discard staged preset changes" : "Close preset settings");
            xnetadditions$editButton.setEnabled(false);
            xnetadditions$exactButton.setEnabled(false);
        } else {
            xnetadditions$selectButton.setText("Select all visible").setEnabled(supported)
                    .setTooltips(
                            supported ? "Add all visible targets to selection" : "Batch edit is unavailable for this channel type",
                            supported ? "Configured and empty cells are included" : "");
            xnetadditions$editButton
                    .setText("Edit (" + xnetadditions$configuredCount + ")")
                    .setEnabled(xnetadditions$configuredCount > 0 && xnetadditions$reference != null);

            if (xnetadditions$emptyCount > 0) {
                xnetadditions$editButton.setTooltips("Edit selected connectors",
                        xnetadditions$emptyCount + " empty targets will be untouched");
            } else {
                xnetadditions$editButton.setTooltips("Edit selected connectors");
            }
            xnetadditions$exactButton.setEnabled(false);
        }

        for (int slot = 0; slot < ConnectorPresetStore.SLOT_COUNT; slot++) {
            ToggleButton button = xnetadditions$presetButtons[slot];

            if (button == null) {continue;}

            boolean occupied = (occupiedMask & (1 << slot)) != 0;
            String presetName = occupied ? ConnectorPresetStore.getPresetName(typeId, slot) : "";
            button.setPressed(xnetadditions$presetSaveMode ? occupied : selectedPreset == slot);
            button.setEnabled(!xnetadditions$editing && !presetDirty && typeId != null && (xnetadditions$presetSaveMode ? hasPresetPayload : occupied));
            if (xnetadditions$presetSaveMode) {
                if (occupied && !presetName.isEmpty()) {
                    button.setTooltips("Replace preset", presetName);
                } else {
                    button.setTooltips(occupied ? "Replace preset" : "Save preset");
                }
            } else if (occupied) {
                button.setTooltips(presetName.isEmpty() ? "Select preset" : presetName, "LShift-click to edit settings");
            } else {
                button.setTooltips("Empty preset", "Press Save to fill this slot");
            }
        }

        if (presetEditing) {
            xnetadditions$presetSaveButton
                    .setText(changeCount == 0 ? "Save" : "Save (" + changeCount + ")")
                    .setEnabled(hasChanges)
                    .setTooltips(hasChanges ? "Save " + changeCount + " changed setting" + (changeCount == 1 ? "" : "s") + " to P" + (xnetadditions$previewPresetSlot + 1) : "No staged changes");
        } else {
            xnetadditions$presetSaveButton
                    .setText(xnetadditions$presetSaveMode ? "Cancel save" : "Save")
                    .setEnabled(!xnetadditions$editing && (xnetadditions$presetSaveMode || hasPresetSource))
                    .setTooltips(xnetadditions$presetSaveMode ? "Cancel preset saving" : "Save current connector");
        }
    }
    @Unique
    private boolean xnetadditions$isEditingPreset() {
        return xnetadditions$previewPresetSlot >= 0 && xnetadditions$editingPresetTypeId != null
                && xnetadditions$editingPresetJson != null && xnetadditions$editingPresetChannel >= 0;
    }

    @Unique
    private String xnetadditions$getStagedTypeId() {
        if (xnetadditions$batchEditor == null) return null;
        if (xnetadditions$isEditingPreset()) return xnetadditions$editingPresetTypeId;
        return xnetadditions$editing ? xnetadditions$getActiveTypeId() : null;
    }
    @Override
    @Unique
    public XNetCustomRecipeFillTarget.Context xnetadditions$getCustomRecipeFillContext() {
        String stagedType = xnetadditions$getStagedTypeId();
        if (xnetadditions$isCustomRecipeType(stagedType)) {
            return new XNetCustomRecipeFillTarget.Context(
                    stagedType,
                    true,
                    xnetadditions$isExtractMode(xnetadditions$batchEditor.getValue("mode")),
                    xnetadditions$CUSTOM_FILTER_LIMIT,
                    xnetadditions$batchEditor.getRecipeFilters(
                            xnetadditions$CUSTOM_FILTER_TAG, xnetadditions$CUSTOM_FILTER_LIMIT));
        }
        if (xnetadditions$editing || xnetadditions$isEditingPreset()) return null;

        String normalType = xnetadditions$getNormalCustomRecipeType();
        Map<String, Object> values = xnetadditions$collectNormalConnectorValues(normalType);
        if (values == null) return null;
        return new XNetCustomRecipeFillTarget.Context(
                normalType,
                false,
                xnetadditions$isExtractMode(values.get("mode")),
                xnetadditions$CUSTOM_FILTER_LIMIT,
                xnetadditions$getFilters(values, xnetadditions$CUSTOM_FILTER_TAG,
                        xnetadditions$CUSTOM_FILTER_LIMIT));
    }

    @Override
    @Unique
    public boolean xnetadditions$applyCustomRecipeFill(
            XNetCustomRecipeFillTarget.Context context, List<ItemStack> filters) {
        if (context == null || filters == null || filters.size() > context.getLimit()) return false;

        String stagedType = xnetadditions$getStagedTypeId();
        if (context.isStaged()) {
            if (!context.getTypeId().equals(stagedType) || xnetadditions$batchEditor == null) return false;
            xnetadditions$batchEditor.replaceRecipeFilters(
                    xnetadditions$CUSTOM_FILTER_TAG, context.getLimit(), filters);
            xnetadditions$captureEditorState();
            xnetadditions$toolbarState = Integer.MIN_VALUE;
            return true;
        }

        String normalType = xnetadditions$getNormalCustomRecipeType();
        if (!context.getTypeId().equals(normalType) || editingConnector == null || editingChannel < 0) return false;
        Map<String, Object> values = xnetadditions$collectNormalConnectorValues(normalType);
        if (values == null) return false;
        for (int i = 0; i < context.getLimit(); i++) {
            values.put(xnetadditions$CUSTOM_FILTER_TAG + i,
                    i < filters.size() ? filters.get(i).copy() : ItemStack.EMPTY);
        }

        SidedPos connector = editingConnector;
        int channel = editingChannel;
        TypedMap.Builder builder = TypedMap.builder()
                .put(TileEntityController.PARAM_CHANNEL, channel)
                .put(TileEntityController.PARAM_POS, connector.getPos())
                .put(TileEntityController.PARAM_SIDE, connector.getSide().ordinal());
        xnetadditions$putEditorValues(builder, values);

        delayedSelectedChannel = channel;
        delayedSelectedConnector = connector;
        delayedSelectedLine = connectorPositions == null ? -1 : connectorPositions.indexOf(connector);

        GuiController gui = (GuiController) (Object) this;
        gui.sendServerCommand(XNetMessages.INSTANCE, TileEntityController.CMD_UPDATECONNECTOR, builder.build());
        gui.refresh();
        return true;
    }

    @Unique
    private boolean xnetadditions$isCustomRecipeType(String typeId) {
        return XNetCustomRecipeFilterCollector.GAS_TYPE.equals(typeId)
                || XNetCustomRecipeFilterCollector.ESSENTIA_TYPE.equals(typeId);
    }

    @Unique
    private boolean xnetadditions$isExtractMode(Object mode) {
        return mode != null && "EXT".equalsIgnoreCase(String.valueOf(mode));
    }

    @Unique
    private String xnetadditions$getNormalCustomRecipeType() {
        if (editingConnector == null || editingChannel < 0
                || xnetadditions$getClientInfo(editingChannel, editingConnector) == null) return null;
        ChannelClientInfo channel = xnetadditions$getChannelInfo(editingChannel);
        if (channel == null || channel.getType() == null) return null;
        String typeId = channel.getType().getID();
        return xnetadditions$isCustomRecipeType(typeId) ? typeId : null;
    }

    @Unique
    private Map<String, Object> xnetadditions$collectNormalConnectorValues(String expectedType) {
        if (expectedType == null || !expectedType.equals(xnetadditions$getNormalCustomRecipeType())) return null;
        ConnectorClientInfo connector = xnetadditions$getClientInfo(editingChannel, editingConnector);
        if (connector == null || connector.getConnectorSettings() == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        boolean advanced = mc.world != null && ConnectorBlock.isAdvancedConnector(
                mc.world, editingConnector.getPos().offset(editingConnector.getSide()));
        try {
            DataCollectorEditorGui collector = new DataCollectorEditorGui(advanced);
            connector.getConnectorSettings().createGui(collector);
            return collector.copyValues();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Unique
    private List<ItemStack> xnetadditions$getFilters(Map<String, Object> values, String tag, int count) {
        List<ItemStack> filters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object value = values.get(tag + i);
            filters.add(value instanceof ItemStack ? ((ItemStack) value).copy() : ItemStack.EMPTY);
        }
        return filters;
    }

    @Unique
    private void xnetadditions$putEditorValues(TypedMap.Builder builder, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                builder.put(new Key<>(entry.getKey(), Type.STRING), (String) value);
            } else if (value instanceof Integer) {
                builder.put(new Key<>(entry.getKey(), Type.INTEGER), (Integer) value);
            } else if (value instanceof Boolean) {
                builder.put(new Key<>(entry.getKey(), Type.BOOLEAN), (Boolean) value);
            } else if (value instanceof Double) {
                builder.put(new Key<>(entry.getKey(), Type.DOUBLE), (Double) value);
            } else if (value instanceof ItemStack) {
                builder.put(new Key<>(entry.getKey(), Type.ITEMSTACK), (ItemStack) value);
            } else {
                builder.put(new Key<>(entry.getKey(), Type.STRING), value == null ? null : value.toString());
            }
        }
    }
    @Unique
    private void xnetadditions$captureEditorState() {
        if (xnetadditions$batchEditor == null) return;
        xnetadditions$restoreValues = xnetadditions$batchEditor.getAllValues();
        xnetadditions$restoreChanges = xnetadditions$batchEditor.getChangedValues();
        xnetadditions$restoreOriginalValues = xnetadditions$batchEditor.getOriginalValues();
        xnetadditions$restoreOriginalMode = xnetadditions$batchEditor.getOriginalMode();
    }

    @Unique
    private void xnetadditions$clearEditorRestore() {
        xnetadditions$restoreValues = null;
        xnetadditions$restoreChanges = null;
        xnetadditions$restoreOriginalValues = null;
        xnetadditions$restoreOriginalMode = null;
    }

    @Unique
    private boolean xnetadditions$hasStableClientSnapshot() {
        return !needsRefresh
                && GuiController.fromServer_channels != null
                && GuiController.fromServer_connectedBlocks != null;
    }
}
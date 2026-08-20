package xnet.additions.powertools.batchedit.client;

import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.varia.ItemStackList;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.apiimpl.fluids.FluidConnectorSettings;
import mcjty.xnet.apiimpl.items.ItemConnectorSettings;
import mcjty.xnet.blocks.controller.gui.AbstractEditorPanel;
import mcjty.xnet.blocks.controller.gui.BlockRenderFilter;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.compat.jei.XNetJeiFluidFilterCollector;
import mcjty.xnet.compat.jei.XNetJeiItemFilterCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normal XNet connector controls with staged, changes-only semantics. */
public final class BatchConnectorEditorPanel extends AbstractEditorPanel {

    private final boolean advanced;
    private final boolean allowMode;
    private final Map<String, Object> changedValues = new LinkedHashMap<>();
    @Nullable private Map<String, Object> originalValues;
    private final List<String> ghostTags = new ArrayList<>();
    private String originalMode;
    private boolean modeRebuildPending;
    private static final int ARMED_COLOR = 0xffffb000;
    public BatchConnectorEditorPanel(Panel panel, Minecraft mc, GuiController gui, boolean advanced, boolean allowMode) {
        super(panel, mc, gui);
        this.advanced = advanced;
        this.allowMode = allowMode;
    }

    @Override
    protected void update(String tag, Object value) {
        Object copy = copyValue(value);
        data.put(tag, copy);
        if (originalValues != null && originalValues.containsKey(tag) && sameValue(originalValues.get(tag), copy)) changedValues.remove(tag);
        else changedValues.put(tag, copyValue(copy));
        if (allowMode && "mode".equals(tag)) modeRebuildPending = true;
    }

    @Override
    public boolean isAdvanced() {
        return advanced;
    }

    @Override
    public IEditorGui choices(String tag, String tooltip, String current, String... values) {
        if ("mode".equals(tag)) {
            if (!allowMode) return this;
            if (originalMode == null) originalMode = current;
        }
        return super.choices(tag, tooltip, current, values);
    }

    @Override
    public <T extends Enum<T>> IEditorGui choices(String tag, String tooltip, T current, T... values) {
        return "mode".equals(tag) && !allowMode ? this : super.choices(tag, tooltip, current, values);
    }

    @Override
    public IEditorGui ghostSlot(String tag, ItemStack slot) {
        super.ghostSlot(tag, slot == null || slot.isEmpty() ? ItemStack.EMPTY : slot.copy());
        ghostTags.add(tag);
        return this;
    }
    public boolean hasGhostSlots() {
        return !ghostTags.isEmpty();
    }

    public boolean addToFirstEmptyGhostSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (String tag : ghostTags) {
            Widget<?> component = components.get(tag);
            if (!(component instanceof BlockRenderFilter)) continue;
            BlockRenderFilter filter = (BlockRenderFilter) component;
            Object current = filter.getRenderItem();
            if (current == null || current instanceof ItemStack && ((ItemStack) current).isEmpty()) {
                ItemStack copy = stack.copy();
                filter.setRenderItem(copy);
                update(tag, copy);
                return true;
            }
        }
        return false;
    }

    public void setState(IConnectorSettings settings) {
        for (Map.Entry<String, Widget<?>> entry : components.entrySet()) {
            entry.getValue().setEnabled(settings.isEnabled(entry.getKey()));
        }
    }

    public boolean hasChanges() {
        return !changedValues.isEmpty();
    }
    public int getChangeCount() {
        return changedValues.size();
    }
    public Object getValue(String tag) {
        return data.get(tag);
    }
    public void beginActualChangeTracking() {
        originalValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) originalValues.put(entry.getKey(), copyValue(entry.getValue()));
        changedValues.clear();
    }
    @Nullable
    public Map<String, Object> getOriginalValues() {
        if (originalValues == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : originalValues.entrySet()) copy.put(entry.getKey(), copyValue(entry.getValue()));
        return copy;
    }
    public void restoreOriginalValues(Map<String, Object> originals, Map<String, Object> previousChanges) {
        originalValues = new LinkedHashMap<>();
        if (originals != null) {
            for (Map.Entry<String, Object> entry : originals.entrySet()) originalValues.put(entry.getKey(), copyValue(entry.getValue()));
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!originalValues.containsKey(entry.getKey())) originalValues.put(entry.getKey(), copyValue(entry.getValue()));
        }
        changedValues.clear();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!sameValue(originalValues.get(entry.getKey()), entry.getValue())) changedValues.put(entry.getKey(), copyValue(entry.getValue()));
        }
        if (previousChanges != null) {
            for (Map.Entry<String, Object> entry : previousChanges.entrySet()) {
                if (!data.containsKey(entry.getKey()) && originalValues.containsKey(entry.getKey())
                        && !sameValue(originalValues.get(entry.getKey()), entry.getValue())) changedValues.put(entry.getKey(), copyValue(entry.getValue()));
            }
        }
    }
    public boolean consumeModeRebuild() {
        boolean pending = modeRebuildPending;
        modeRebuildPending = false;
        return pending;
    }

    public String getOriginalMode() {
        return originalMode;
    }

    public void setOriginalMode(String originalMode) {
        this.originalMode = originalMode;
    }

    public void restoreChangedValues(Map<String, Object> changes) {
        changedValues.clear();
        for (String tag : changes.keySet()) {
            if (components.containsKey(tag) && data.containsKey(tag)) {
                changedValues.put(tag, copyValue(data.get(tag)));
            }
        }
    }

    public boolean toggleArmed(Widget<?> widget) {
        if (widget == null || !widget.isEnabledAndVisible()) return false;
        for (Map.Entry<String, Widget<?>> entry : components.entrySet()) {
            if (entry.getValue() != widget || !data.containsKey(entry.getKey())) continue;
            String tag = entry.getKey();
            if ("mode".equals(tag) && allowMode && changedValues.containsKey(tag)
                    && originalMode != null && !originalMode.equals(data.get(tag))) {
                data.put(tag, originalMode);
                changedValues.remove(tag);
                modeRebuildPending = true;
                return true;
            }
            if (changedValues.containsKey(tag)) changedValues.remove(tag);
            else changedValues.put(tag, copyValue(data.get(tag)));
            return true;
        }
        return false;
    }
    public Map<String, Object> getChangedValues() {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changedValues.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }
    public Map<String, Object> getAllValues() {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String tag : components.keySet()) {
            if (data.containsKey(tag)) copy.put(tag, copyValue(data.get(tag)));
        }
        return copy;
    }
    public List<ItemStack> getRecipeFilters(String tag, int count) {
        List<ItemStack> filters = new ArrayList<>(count);
        for (ItemStack filter : getGhostFilters(tag, count)) {
            filters.add(filter.isEmpty() ? ItemStack.EMPTY : filter.copy());
        }
        return filters;
    }
    public void replaceRecipeFilters(String tag, int count, List<ItemStack> filters) {
        for (int i = 0; i < count; i++) {
            setGhostValue(tag + i, i < filters.size() ? filters.get(i) : ItemStack.EMPTY);
        }
    }
    public void setJeiRecipeFilters(XNetJeiItemFilterCollector.Result result) {
        List<ItemStack> addedFilters = result.getFilters();
        List<ItemStack> filters = mergeRecipeFilters(getGhostFilters(ItemConnectorSettings.TAG_FILTER, ItemConnectorSettings.FILTER_SIZE), addedFilters);
        if (addedFilters.isEmpty()) {
            GuiController.showError(result.isOutputs() ? "Recipe has no item outputs!" : "Recipe has no item inputs!");
            return;
        }
        if (filters.size() > ItemConnectorSettings.FILTER_SIZE) {
            GuiController.showError("Recipe needs " + filters.size() + " filters after merging, but this connector supports " + ItemConnectorSettings.FILTER_SIZE + "!");
            return;
        }
        for (int i = 0; i < ItemConnectorSettings.FILTER_SIZE; i++) setGhostValue(ItemConnectorSettings.TAG_FILTER + i, i < filters.size() ? filters.get(i) : ItemStack.EMPTY);
        if (result.isAdvanced()) {
            setBooleanValue(ItemConnectorSettings.TAG_COUNTMODE, "Ins".equalsIgnoreCase(String.valueOf(data.get(ItemConnectorSettings.TAG_MODE))) || Boolean.TRUE.equals(data.get(ItemConnectorSettings.TAG_COUNTMODE)));
            setBooleanValue(ItemConnectorSettings.TAG_META, Boolean.TRUE.equals(data.get(ItemConnectorSettings.TAG_META)) || result.needsMeta());
            setBooleanValue(ItemConnectorSettings.TAG_NBT, Boolean.TRUE.equals(data.get(ItemConnectorSettings.TAG_NBT)) || result.needsNbt());
        }
    }
    public void setJeiFluidRecipeFilters(XNetJeiFluidFilterCollector.Result result) {
        List<ItemStack> addedFilters = result.getFilters();
        List<ItemStack> filters = mergeRecipeFilters(getGhostFilters(FluidConnectorSettings.TAG_FILTER, FluidConnectorSettings.FILTER_SIZE), addedFilters);
        if (addedFilters.isEmpty()) {
            GuiController.showError(result.isOutputs() ? "Recipe has no fluid outputs!" : "Recipe has no fluid inputs!");
            return;
        }
        if (filters.size() > FluidConnectorSettings.FILTER_SIZE) {
            GuiController.showError("Recipe needs " + filters.size() + " filters after merging, but this connector supports " + FluidConnectorSettings.FILTER_SIZE + "!");
            return;
        }
        for (int i = 0; i < FluidConnectorSettings.FILTER_SIZE; i++) setGhostValue(FluidConnectorSettings.TAG_FILTER + i, i < filters.size() ? filters.get(i) : ItemStack.EMPTY);
    }
    public void drawArmedFrames(int parentX, int parentY) {
        for (String tag : changedValues.keySet()) {
            Widget<?> component = components.get(tag);
            if (component == null || !component.isVisible()) continue;
            Rectangle bounds = component.getBounds();
            if (bounds == null) continue;
            int x = parentX + bounds.x;
            int y = parentY + bounds.y;
            Gui.drawRect(x, y, x + bounds.width, y + 1, ARMED_COLOR);
            Gui.drawRect(x, y + bounds.height - 1, x + bounds.width, y + bounds.height, ARMED_COLOR);
            Gui.drawRect(x, y + 1, x + 1, y + bounds.height - 1, ARMED_COLOR);
            Gui.drawRect(x + bounds.width - 1, y + 1, x + bounds.width, y + bounds.height - 1, ARMED_COLOR);
        }
    }
    private static Object copyValue(Object value) {
        return value instanceof ItemStack ? ((ItemStack) value).copy() : value;
    }
    private static List<ItemStack> mergeRecipeFilters(ItemStackList existingFilters, List<ItemStack> addedFilters) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : existingFilters) {
            if (!stack.isEmpty()) merged.add(stack.copy());
        }
        for (ItemStack stack : addedFilters) {
            if (stack.isEmpty()) continue;
            boolean found = false;
            for (ItemStack existing : merged) {
                if (ItemStack.areItemsEqual(existing, stack) && ItemStack.areItemStackTagsEqual(existing, stack)) {
                    existing.setCount(Math.max(existing.getCount(), stack.getCount()));
                    found = true;
                    break;
                }
            }
            if (!found) merged.add(stack.copy());
        }
        return merged;
    }
    private ItemStackList getGhostFilters(String tag, int count) {
        ItemStackList filters = ItemStackList.create(count);
        for (int i = 0; i < count; i++) {
            Object value = data.get(tag + i);
            filters.set(i, value instanceof ItemStack ? ((ItemStack) value).copy() : ItemStack.EMPTY);
        }
        return filters;
    }
    private void setGhostValue(String tag, ItemStack value) {
        ItemStack copy = value == null || value.isEmpty() ? ItemStack.EMPTY : value.copy();
        Widget<?> component = components.get(tag);
        if (!(component instanceof BlockRenderFilter)) return;
        ((BlockRenderFilter) component).setRenderItem(copy.isEmpty() ? null : copy);
        update(tag, copy);
    }
    private void setBooleanValue(String tag, boolean value) {
        Widget<?> component = components.get(tag);
        if (!(component instanceof ToggleButton)) return;
        ((ToggleButton) component).setPressed(value);
        update(tag, value);
    }
    private static boolean sameValue(Object first, Object second) {
        if (first instanceof ItemStack && second instanceof ItemStack) {
            ItemStack a = (ItemStack) first;
            ItemStack b = (ItemStack) second;
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
            return a.getCount() == b.getCount() && ItemStack.areItemsEqual(a, b) && ItemStack.areItemStackTagsEqual(a, b);
        }
        if (first instanceof String && second instanceof String) return ((String) first).equalsIgnoreCase((String) second);
        return java.util.Objects.equals(first, second);
    }
}
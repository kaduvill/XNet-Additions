package xnet.additions.powertools.batchedit.client;

import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.Widget;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.blocks.controller.gui.AbstractEditorPanel;
import mcjty.xnet.blocks.controller.gui.BlockRenderFilter;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;

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
        data.put(tag, copyValue(value));
        changedValues.put(tag, copyValue(value));
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
        ((BlockRenderFilter) components.get(tag)).setOnGhostClick(null);
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
}

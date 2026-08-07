package xnet.additions.batchedit.client;

import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.Widget;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.blocks.controller.gui.AbstractEditorPanel;
import mcjty.xnet.blocks.controller.gui.BlockRenderFilter;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normal XNet connector controls with staged, changes-only semantics. */
public final class BatchConnectorEditorPanel extends AbstractEditorPanel {

    private final boolean advanced;
    private final Map<String, Object> changedValues = new LinkedHashMap<>();
    private final List<String> ghostTags = new ArrayList<>();
    public BatchConnectorEditorPanel(Panel panel, Minecraft mc, GuiController gui, boolean advanced) {
        super(panel, mc, gui);
        this.advanced = advanced;
    }

    @Override
    protected void update(String tag, Object value) {
        data.put(tag, copyValue(value));
        changedValues.put(tag, copyValue(value));
    }

    @Override
    public boolean isAdvanced() {
        return advanced;
    }


    /**
     * Top-level connector mode is intentionally excluded. Changing modes can
     * expose/hide type-specific state; Phase 1 only patches settings that are
     * valid in the connector's current mode.
     */
    @Override
    public IEditorGui choices(String tag, String tooltip, String current, String... values) {
        return "mode".equals(tag) ? this : super.choices(tag, tooltip, current, values);
    }

    @Override
    public <T extends Enum<T>> IEditorGui choices(String tag, String tooltip, T current, T... values) {
        return "mode".equals(tag) ? this : super.choices(tag, tooltip, current, values);
    }

    @Override
    public IEditorGui ghostSlot(String tag, ItemStack slot) {
        super.ghostSlot(tag, slot == null || slot.isEmpty() ? ItemStack.EMPTY : slot.copy());
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

    public Map<String, Object> getChangedValues() {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changedValues.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    private static Object copyValue(Object value) {
        return value instanceof ItemStack ? ((ItemStack) value).copy() : value;
    }
}

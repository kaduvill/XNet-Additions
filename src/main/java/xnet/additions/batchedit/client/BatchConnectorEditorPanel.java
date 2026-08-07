package xnet.additions.batchedit.client;

import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.Widget;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.blocks.controller.gui.AbstractEditorPanel;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Normal XNet connector controls with staged, changes-only semantics. */
public final class BatchConnectorEditorPanel extends AbstractEditorPanel {

    private final boolean advanced;
    private final Map<String, Object> changedValues = new LinkedHashMap<>();

    public BatchConnectorEditorPanel(Panel panel, Minecraft mc, GuiController gui, boolean advanced) {
        super(panel, mc, gui);
        this.advanced = advanced;
    }

    @Override
    protected void update(String tag, Object value) {
        data.put(tag, value);
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

    /** Ghost filter contents are intentionally excluded from the first batch-edit version. */
    @Override
    public IEditorGui ghostSlot(String tag, ItemStack slot) {
        return this;
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

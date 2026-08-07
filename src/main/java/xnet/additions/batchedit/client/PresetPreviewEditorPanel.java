package xnet.additions.batchedit.client;

import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.Widget;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.blocks.controller.gui.AbstractEditorPanel;
import mcjty.xnet.blocks.controller.gui.BlockRenderFilter;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import java.util.Map;

public final class PresetPreviewEditorPanel extends AbstractEditorPanel {

    private final boolean advanced;

    public PresetPreviewEditorPanel(Panel panel, Minecraft mc, GuiController gui, boolean advanced) {
        super(panel, mc, gui);
        this.advanced = advanced;
    }

    @Override
    protected void update(String tag, Object value) {}

    @Override
    public boolean isAdvanced() {
        return advanced;
    }

    public void setState(IConnectorSettings settings) {
        for (Map.Entry<String, Widget<?>> entry : components.entrySet()) entry.getValue().setEnabled(settings.isEnabled(entry.getKey()));
    }

    public static Panel createReadOnlyPanel(Minecraft mc, GuiController gui, Button activeButton) {
        return new Panel(mc, gui) {
            private boolean activePressed;

            @Override
            public Widget<Panel> mouseClick(int x, int y, int button) {
                int localX = x - getBounds().x;
                int localY = y - getBounds().y;
                if (activeButton.in(localX, localY)) {
                    activePressed = activeButton.mouseClick(localX, localY, button) != null;
                    return this;
                }
                return null;
            }

            @Override
            public void mouseRelease(int x, int y, int button) {
                if (!activePressed) return;
                activeButton.mouseRelease(x - getBounds().x, y - getBounds().y, button);
                activePressed = false;
            }

            @Override
            public boolean mouseWheel(int amount, int x, int y) {
                return true;
            }
        }.setLayout(new PositionalLayout());
    }
}
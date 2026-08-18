package xnet.additions.powertools.client;

import mcjty.lib.gui.layout.HorizontalAlignment;
import mcjty.lib.gui.layout.HorizontalLayout;
import mcjty.lib.gui.layout.PositionalLayout;
import mcjty.lib.gui.widgets.BlockRender;
import mcjty.lib.gui.widgets.Button;
import mcjty.lib.gui.widgets.Label;
import mcjty.lib.gui.widgets.Panel;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.gui.widgets.WidgetList;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.clientinfo.ConnectedBlockClientInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

import javax.annotation.Nullable;

public final class PowerToolsRow extends Panel {
    public static final int HEIGHT = 28;
    private final int width;
    private final Panel metadata;
    @Nullable private Button actionButton;
    @Nullable private Runnable rowAction;
    private boolean rowPressed;

    public PowerToolsRow(Gui gui, int width, String text, int color, String... tooltips) {
        super(Minecraft.getMinecraft(), gui);
        this.width = width;
        setDesiredHeight(HEIGHT);
        setLayout(new PositionalLayout());
        setTooltips(tooltips);

        metadata = new Panel(mc, gui).setLayout(new HorizontalLayout().setHorizontalMargin(0).setSpacing(2));
        metadata.setLayoutHint(new PositionalLayout.PositionalHint(2, 0, Math.max(1, width - 6), 16));
        addChild(metadata);

        addChild(new Label(mc, gui).setText(text).setDynamic(true)
                .setHorizontalAlignment(HorizontalAlignment.ALIGN_LEFT).setColor(color)
                .setLayoutHint(new PositionalLayout.PositionalHint(2, 16, Math.max(1, width - 6), 11)));
    }

    public PowerToolsRow addMetadata(Widget<?> widget) {
        metadata.addChild(widget);
        return this;
    }

    public PowerToolsRow addBlock(@Nullable ConnectedBlockClientInfo block) {
        if (block != null) {
            metadata.addChild(new BlockRender(mc, gui).setRenderItem(block.getConnectedBlock()));
        }
        return this;
    }

    public PowerToolsRow addChannel(int channelIndex, @Nullable ChannelClientInfo channel) {
        if (channelIndex < 0) {return this;}

        String number = String.valueOf(channelIndex + 1);
        ToggleButton button = new ToggleButton(mc, gui).setCheckMarker(false).setText(number).setDesiredWidth(14);
        if (channel != null) {
            IndicatorIcon icon = channel.getChannelSettings().getIndicatorIcon();
            if (icon != null) {
                button.setImage(icon.getImage(), icon.getU(), icon.getV(), icon.getIw(), icon.getIh());
            }

            String indicator = channel.getChannelSettings().getIndicator();
            if (indicator != null) {button.setText(indicator + number);}
        }
        metadata.addChild(button);
        return this;
    }

    public PowerToolsRow setRowAction(Runnable rowAction) {
        this.rowAction = rowAction;
        return this;
    }

    public PowerToolsRow addAction(String text, Runnable action, String... tooltips) {
        metadata.setLayoutHint(new PositionalLayout.PositionalHint(2, 0, Math.max(1, width - 25), 16));
        actionButton = new Button(mc, gui).setText(text).setTooltips(tooltips)
                .setLayoutHint(new PositionalLayout.PositionalHint(Math.max(2, width - 19), 1, 16, 14))
                .addButtonEvent(parent -> action.run());
        addChild(actionButton);
        return this;
    }

    @Override
    public Widget<?> getWidgetAtPosition(int x, int y) {
        if (actionButton != null) {
            Widget<?> widget = super.getWidgetAtPosition(x, y);
            if (actionButton.containsWidget(widget)) {return widget;}
        }
        return this;
    }

    @Override
    public Widget<Panel> mouseClick(int x, int y, int button) {
        rowPressed = false;
        if (actionButton != null) {
            Widget<?> widget = super.getWidgetAtPosition(x, y);
            if (actionButton.containsWidget(widget)) {return super.mouseClick(x, y, button);}
        }
        if (rowAction != null && button == 0 && isEnabledAndVisible()) {
            rowPressed = true;
            return this;
        }
        return super.mouseClick(x, y, button);
    }

    @Override
    public void mouseRelease(int x, int y, int button) {
        if (rowPressed) {
            rowPressed = false;
            if (rowAction != null && isEnabledAndVisible()) {rowAction.run();}
            return;
        }
        super.mouseRelease(x, y, button);
    }

    public static WidgetList createList(Gui gui) {
        return new WidgetList(Minecraft.getMinecraft(), gui)
                .setPropagateEventsToChildren(false)
                .setRowheight(HEIGHT);
    }
}
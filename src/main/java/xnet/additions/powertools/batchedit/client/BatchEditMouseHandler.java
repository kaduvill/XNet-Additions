package xnet.additions.powertools.batchedit.client;

import mcjty.lib.gui.widgets.Widget;

public interface BatchEditMouseHandler {
    default void xnetadditions$recordControllerMouseClick(int button, long eventNanos) {
    }

    boolean xnetadditions$handleBatchLShiftClick(Widget<?> widget);
}
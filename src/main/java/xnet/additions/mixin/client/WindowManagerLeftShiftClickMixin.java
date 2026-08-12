package xnet.additions.mixin.client;

import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.widgets.Widget;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xnet.additions.powertools.batchedit.client.BatchEditMouseHandler;
import xnet.additions.powertools.pinning.client.ConnectorPinMouseHandler;

@Mixin(value = WindowManager.class, remap = false)
public abstract class WindowManagerLeftShiftClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$leftShiftClick(int x, int y, int button, CallbackInfo ci) {
        if (button != 0 || !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) return;

        WindowManager manager = (WindowManager) (Object) this;
        if (manager.getIconManager().isDragging()
                || manager.getModalWindows().findAny().isPresent()) return;

        Widget<?> widget = manager.findWidgetAtPosition(x, y).orElse(null);
        if (widget == null) return;

        if (manager.getGui() instanceof ConnectorPinMouseHandler
                && ((ConnectorPinMouseHandler) manager.getGui()).xnetadditions$handleConnectorPinClick(widget)) {
            ci.cancel();
            return;
        }

        if (manager.getGui() instanceof BatchEditMouseHandler
                && ((BatchEditMouseHandler) manager.getGui()).xnetadditions$handleBatchLShiftClick(widget)) {
            ci.cancel();
        }
    }
}
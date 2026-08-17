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
import xnet.additions.powertools.remoteconnector.client.RemoteConnectorMouseHandler;

@Mixin(value = WindowManager.class, remap = false)
public abstract class WindowManagerMouseClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$mouseClick(int x, int y, int button, CallbackInfo ci) {
        boolean remote = button == 1;
        if (!remote && (button != 0 || !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT))) return;

        WindowManager manager = (WindowManager) (Object) this;
        if (manager.getIconManager().isDragging()
                || manager.getModalWindows().findAny().isPresent()) return;

        Widget<?> widget = manager.findWidgetAtPosition(x, y).orElse(null);
        if (widget == null) return;

        if (remote) {
            if (manager.getGui() instanceof RemoteConnectorMouseHandler
                    && ((RemoteConnectorMouseHandler) manager.getGui()).xnetadditions$handleRemoteConnectorRightClick(widget)) {
                ci.cancel();
            }
            return;
        }

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
package xnet.additions.mixin.batchedit.client;

import mcjty.lib.gui.WindowManager;
import mcjty.lib.gui.widgets.Widget;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xnet.additions.batchedit.client.BatchEditMouseHandler;

@Mixin(value = WindowManager.class, remap = false)
public abstract class WindowManagerBatchEditMouseMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void xnetadditions$batchArmClick(int x, int y, int button, CallbackInfo ci) {
        if (button != 0 || !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) return;
        WindowManager manager = (WindowManager) (Object) this;
        if (!(manager.getGui() instanceof BatchEditMouseHandler) || manager.getIconManager().isDragging()
                || manager.getModalWindows().findAny().isPresent()) return;
        Widget<?> widget = manager.findWidgetAtPosition(x, y).orElse(null);
        if (widget != null && ((BatchEditMouseHandler) manager.getGui()).xnetadditions$handleBatchLShiftClick(widget)) ci.cancel();
    }
}
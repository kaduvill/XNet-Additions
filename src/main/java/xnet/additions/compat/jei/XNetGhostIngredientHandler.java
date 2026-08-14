package xnet.additions.compat.jei;

import mcjty.lib.gui.GenericGuiContainer;
import mcjty.lib.gui.Window;
import mcjty.lib.gui.widgets.AbstractContainerWidget;
import mcjty.lib.gui.widgets.TextField;
import mcjty.lib.gui.widgets.Widget;
import mcjty.lib.typed.TypedMap;
import mcjty.lib.varia.FluidTools;
import mcjty.xnet.blocks.cables.GuiConnector;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.clientinfo.ChannelClientInfo;
import mcjty.xnet.compat.jei.GhostSlotHandler;
import mezz.jei.api.gui.IGhostIngredientHandler;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRegistry;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class XNetGhostIngredientHandler<T extends GenericGuiContainer<?>> implements IGhostIngredientHandler<T> {

    private final IIngredientRegistry ingredients;
    private final GhostSlotHandler nativeHandler = new GhostSlotHandler();

    public XNetGhostIngredientHandler(IIngredientRegistry ingredients) {
        this.ingredients = ingredients;
    }

    @Override
    public <I> List<Target<I>> getTargets(T gui, I ingredient, boolean doStart) {
        if (gui instanceof GuiConnector) {
            List<Target<I>> targets = new ArrayList<>(nativeHandler.getTargets(gui, ingredient, doStart));
            TextField nameField = gui.getWindow().findChild("name");
            Rectangle area = findAbsoluteBounds(gui.getWindow().getToplevel(), nameField, new Point());
            if (area != null) {
                targets.add(new Target<I>() {
                    @Override
                    public Rectangle getArea() {
                        return area;
                    }

                    @Override
                    public void accept(I ingredient) {
                        IIngredientHelper<I> helper = ingredients.getIngredientHelper(ingredient);
                        String displayName = helper.getDisplayName(ingredient);
                        if (displayName == null || displayName.isEmpty()) {
                            return;
                        }
                        nameField.setText(displayName);
                        nameField.clearSelection();
                        gui.getWindow().fireChannelEvents(nameField.getName(), nameField, TypedMap.builder().put(Window.PARAM_ID, "text").put(TextField.PARAM_TEXT, displayName).build());
                    }
                });
            }
            return targets;
        }

        GuiController controller = (GuiController) gui;
        String channelType = getSelectedChannelType(controller);
        ItemStack filter = ItemStack.EMPTY;
        if ("mekanism.gas".equals(channelType) && Loader.isModLoaded("mekanism")) {
            filter = MekanismJeiIngredientBridge.toFilter(ingredient);
        } else if ("tc.essentia".equals(channelType) && Loader.isModLoaded("thaumcraft")) {
            filter = ThaumcraftJeiIngredientBridge.toFilter(ingredient);
        } else if ("xnet.fluid".equals(channelType) && ingredient instanceof ItemStack && FluidTools.convertBucketToFluid((ItemStack) ingredient) == null) {
            return Collections.emptyList();
        } else {
            return nativeHandler.getTargets(gui, ingredient, doStart);
        }
        if (filter.isEmpty()) {
            return Collections.emptyList();
        }

        ItemStack converted = filter;
        List<Target<ItemStack>> nativeTargets = nativeHandler.getTargets(gui, converted, doStart);
        List<Target<I>> targets = new ArrayList<>(nativeTargets.size());
        for (Target<ItemStack> target : nativeTargets) {
            targets.add(new Target<I>() {
                @Override
                public Rectangle getArea() {
                    return target.getArea();
                }

                @Override
                public void accept(I ingredient) {
                    target.accept(converted.copy());
                }
            });
        }
        return targets;
    }

    private static String getSelectedChannelType(GuiController gui) {
        int channel = gui.getSelectedChannel();
        List<ChannelClientInfo> channels = GuiController.fromServer_channels;
        if (channels == null || channel < 0 || channel >= channels.size()) {
            return "";
        }
        ChannelClientInfo info = channels.get(channel);
        return info == null ? "" : info.getType().getID();
    }

    private static Rectangle findAbsoluteBounds(Widget<?> widget, Widget<?> target, Point offset) {
        if (widget == target && widget.getBounds() != null) {
            Rectangle bounds = new Rectangle(widget.getBounds());
            bounds.translate(offset.x, offset.y);
            return bounds;
        }
        if (!(widget instanceof AbstractContainerWidget)) {
            return null;
        }
        Point childOffset = new Point(offset);
        if (widget.getBounds() != null) {
            childOffset.translate(widget.getBounds().x, widget.getBounds().y);
        }
        for (Widget<?> child : ((AbstractContainerWidget<?>) widget).getChildren()) {
            Rectangle bounds = findAbsoluteBounds(child, target, childOffset);
            if (bounds != null) {
                return bounds;
            }
        }
        return null;
    }

    @Override
    public void onComplete() {
        nativeHandler.onComplete();
    }
}
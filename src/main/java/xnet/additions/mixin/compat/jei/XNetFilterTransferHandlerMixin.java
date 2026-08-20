package xnet.additions.mixin.compat.jei;

import mcjty.lib.container.GenericContainer;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.compat.jei.XNetFilterTransferHandler;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xnet.additions.compat.jei.XNetCustomRecipeFillTarget;
import xnet.additions.compat.jei.XNetCustomRecipeFilterCollector;

import java.util.List;

/** Extends XNet's universal transfer handler only for Additions ingredient types. */
@Mixin(value = XNetFilterTransferHandler.class, remap = false)
public abstract class XNetFilterTransferHandlerMixin {

    @Shadow(remap = false) @Final private IRecipeTransferHandlerHelper helper;

    @Inject(
            method = "transferRecipe(Lmcjty/lib/container/GenericContainer;Lmezz/jei/api/gui/IRecipeLayout;Lnet/minecraft/entity/player/EntityPlayer;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void xnetadditions$transferCustomRecipe(
            GenericContainer container, IRecipeLayout recipeLayout, EntityPlayer player,
            boolean maxTransfer, boolean doTransfer,
            CallbackInfoReturnable<IRecipeTransferError> cir) {
        GuiController gui = xnetadditions$findParentController();
        if (!(gui instanceof XNetCustomRecipeFillTarget)) {
            return;
        }

        XNetCustomRecipeFillTarget target = (XNetCustomRecipeFillTarget) gui;
        XNetCustomRecipeFillTarget.Context context = target.xnetadditions$getCustomRecipeFillContext();
        if (context == null) {
            return;
        }

        String ingredientName = XNetCustomRecipeFilterCollector.GAS_TYPE.equals(context.getTypeId())
                ? "gas" : "essentia";
        List<ItemStack> addedFilters = XNetCustomRecipeFilterCollector.collect(
                context.getTypeId(), recipeLayout, context.isOutputs());
        if (addedFilters.isEmpty()) {
            cir.setReturnValue(helper.createUserErrorWithTooltip(
                    "Recipe has no " + ingredientName + (context.isOutputs() ? " outputs" : " inputs")));
            return;
        }

        List<ItemStack> mergedFilters = XNetCustomRecipeFilterCollector.merge(
                context.getTypeId(), context.getExistingFilters(), addedFilters);
        if (mergedFilters.size() > context.getLimit()) {
            cir.setReturnValue(helper.createUserErrorWithTooltip(
                    "Recipe needs " + mergedFilters.size() + " filters after merging, but this connector supports "
                            + context.getLimit()));
            return;
        }

        if (doTransfer && !target.xnetadditions$applyCustomRecipeFill(context, mergedFilters)) {
            cir.setReturnValue(helper.createUserErrorWithTooltip("The selected connector changed; try again"));
            return;
        }
        cir.setReturnValue(null);
    }

    private static GuiController xnetadditions$findParentController() {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof GuiController) {
            return (GuiController) screen;
        }
        if (screen instanceof RecipesGui) {
            GuiScreen parent = ((RecipesGui) screen).getParentScreen();
            if (parent instanceof GuiController) {
                return (GuiController) parent;
            }
        }
        return null;
    }
}
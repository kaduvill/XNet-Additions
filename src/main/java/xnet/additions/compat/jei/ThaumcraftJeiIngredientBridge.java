package xnet.additions.compat.jei;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.aspects.IEssentiaContainerItem;

final class ThaumcraftJeiIngredientBridge {

    private static final Item PHIAL = Item.REGISTRY.getObject(new ResourceLocation("thaumcraft", "phial"));

    private ThaumcraftJeiIngredientBridge() {
    }

    static ItemStack toFilter(Object ingredient) {
        Aspect aspect;
        if (ingredient instanceof ItemStack) {
            ItemStack stack = (ItemStack) ingredient;
            if (stack.isEmpty() || !(stack.getItem() instanceof IEssentiaContainerItem)) {
                return ItemStack.EMPTY;
            }
            AspectList aspectList = ((IEssentiaContainerItem) stack.getItem()).getAspects(stack);
            if (aspectList == null || aspectList.size() == 0) {
                return ItemStack.EMPTY;
            }
            Aspect[] aspects = aspectList.getAspects();
            if (aspects == null || aspects.length == 0) {
                return ItemStack.EMPTY;
            }
            if (aspects.length != 1) {
                ItemStack copy = stack.copy();
                copy.setCount(1);
                return copy;
            }
            aspect = aspects[0];
        } else if (ingredient instanceof Aspect) {
            aspect = (Aspect) ingredient;
        } else if (ingredient instanceof AspectList) {
            Aspect[] aspects = ((AspectList) ingredient).getAspects();
            if (aspects == null || aspects.length != 1) {
                return ItemStack.EMPTY;
            }
            aspect = aspects[0];
        } else {
            return ItemStack.EMPTY;
        }
        if (aspect == null || !(PHIAL instanceof IEssentiaContainerItem)) {
            return ItemStack.EMPTY;
        }

        ItemStack filter = new ItemStack(PHIAL, 1, 1);
        IEssentiaContainerItem container = (IEssentiaContainerItem) PHIAL;
        container.setAspects(filter, new AspectList().add(aspect, 10));
        AspectList stored = container.getAspects(filter);
        return stored != null && stored.getAmount(aspect) > 0 ? filter : ItemStack.EMPTY;
    }
}
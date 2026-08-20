package xnet.additions.compat.jei;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IRecipeLayout;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.aspects.IEssentiaContainerItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @SuppressWarnings("deprecation")
    static List<ItemStack> collect(IRecipeLayout recipeLayout, boolean outputs) {
        List<ItemStack> filters = new ArrayList<>();
        Set<String> seenAspects = new HashSet<>();
        boolean wantInputs = !outputs;

        try {
            IGuiIngredientGroup<AspectList> aspectGroup = recipeLayout.getIngredientsGroup(AspectList.class);
            collectAspectIngredients(aspectGroup.getGuiIngredients(), wantInputs, filters, seenAspects);
        } catch (RuntimeException | LinkageError ignored) {
            // Thaumic JEI is optional; item-backed essentia ingredients still work below.
        }
        collectItemIngredients(recipeLayout.getItemStacks().getGuiIngredients(), wantInputs, filters, seenAspects);
        return filters;
    }

    static boolean sameFilter(ItemStack first, ItemStack second) {
        Set<String> firstAspects = getAspectTags(first);
        if (firstAspects.isEmpty()) {
            return false;
        }
        for (String aspect : getAspectTags(second)) {
            if (firstAspects.contains(aspect)) {
                return true;
            }
        }
        return false;
    }

    private static void collectAspectIngredients(
            Map<Integer, ? extends IGuiIngredient<AspectList>> ingredients,
            boolean wantInputs, List<ItemStack> filters, Set<String> seenAspects) {
        for (IGuiIngredient<AspectList> ingredient : ingredients.values()) {
            if (ingredient.isInput() != wantInputs) {
                continue;
            }
            try {
                addAspectList(filters, seenAspects, getDisplayedOrFirstAspectList(ingredient));
            } catch (RuntimeException | LinkageError ignored) {
                // Skip one malformed optional ingredient without discarding the recipe.
            }
        }
    }

    private static void collectItemIngredients(
            Map<Integer, ? extends IGuiIngredient<ItemStack>> ingredients,
            boolean wantInputs, List<ItemStack> filters, Set<String> seenAspects) {
        for (IGuiIngredient<ItemStack> ingredient : ingredients.values()) {
            if (ingredient.isInput() != wantInputs) {
                continue;
            }
            try {
                ItemStack stack = getDisplayedOrFirstItem(ingredient);
                if (!stack.isEmpty() && stack.getItem() instanceof IEssentiaContainerItem) {
                    addAspectList(filters, seenAspects,
                            ((IEssentiaContainerItem) stack.getItem()).getAspects(stack));
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Skip one malformed optional ingredient without discarding the recipe.
            }
        }
    }

    private static AspectList getDisplayedOrFirstAspectList(IGuiIngredient<AspectList> ingredient) {
        AspectList displayed = ingredient.getDisplayedIngredient();
        if (displayed != null && displayed.size() > 0) {
            return displayed;
        }
        for (AspectList candidate : ingredient.getAllIngredients()) {
            if (candidate != null && candidate.size() > 0) {
                return candidate;
            }
        }
        return null;
    }

    private static ItemStack getDisplayedOrFirstItem(IGuiIngredient<ItemStack> ingredient) {
        ItemStack displayed = ingredient.getDisplayedIngredient();
        if (displayed != null && !displayed.isEmpty()) {
            return displayed.copy();
        }
        for (ItemStack candidate : ingredient.getAllIngredients()) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    private static void addAspectList(List<ItemStack> filters, Set<String> seenAspects,
                                      AspectList aspects) {
        if (aspects == null) {
            return;
        }
        Aspect[] values = aspects.getAspects();
        if (values == null) {
            return;
        }
        for (Aspect aspect : values) {
            if (aspect == null || !seenAspects.add(aspect.getTag())) {
                continue;
            }
            ItemStack filter = toFilter(aspect);
            if (!filter.isEmpty()) {
                filter.setCount(1);
                filters.add(filter);
            }
        }
    }

    private static Set<String> getAspectTags(ItemStack stack) {
        Set<String> tags = new HashSet<>();
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof IEssentiaContainerItem)) {
            return tags;
        }
        AspectList aspects = ((IEssentiaContainerItem) stack.getItem()).getAspects(stack);
        if (aspects == null || aspects.getAspects() == null) {
            return tags;
        }
        for (Aspect aspect : aspects.getAspects()) {
            if (aspect != null) {
                tags.add(aspect.getTag());
            }
        }
        return tags;
    }
}
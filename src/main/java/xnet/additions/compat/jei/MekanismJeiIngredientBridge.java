package xnet.additions.compat.jei;

import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasItem;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IRecipeLayout;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class MekanismJeiIngredientBridge {

    private static final Item GAS_TANK = Item.REGISTRY.getObject(new ResourceLocation("mekanism", "GasTank"));

    private MekanismJeiIngredientBridge() {
    }

    static ItemStack toFilter(Object ingredient) {
        if (ingredient instanceof ItemStack) {
            ItemStack stack = (ItemStack) ingredient;
            if (stack.isEmpty() || !(stack.getItem() instanceof IGasItem)) {
                return ItemStack.EMPTY;
            }
            GasStack gas = ((IGasItem) stack.getItem()).getGas(stack);
            if (gas == null || gas.getGas() == null) {
                return ItemStack.EMPTY;
            }
            ItemStack copy = stack.copy();
            copy.setCount(1);
            return copy;
        }
        if (!(ingredient instanceof GasStack) || ((GasStack) ingredient).getGas() == null) {
            return ItemStack.EMPTY;
        }

        if (!(GAS_TANK instanceof IGasItem)) {
            return ItemStack.EMPTY;
        }
        GasStack gas = ((GasStack) ingredient).copy();
        gas.amount = Math.max(1, gas.amount);
        ItemStack filter = new ItemStack(GAS_TANK);
        IGasItem gasItem = (IGasItem) GAS_TANK;
        gasItem.setGas(filter, gas);
        GasStack stored = gasItem.getGas(filter);
        return stored != null && stored.isGasEqual(gas) ? filter : ItemStack.EMPTY;
    }

    @SuppressWarnings("deprecation")
    static List<ItemStack> collect(IRecipeLayout recipeLayout, boolean outputs) {
        List<ItemStack> filters = new ArrayList<>();
        boolean wantInputs = !outputs;

        try {
            IGuiIngredientGroup<GasStack> gasGroup = recipeLayout.getIngredientsGroup(GasStack.class);
            collectGasIngredients(gasGroup.getGuiIngredients(), wantInputs, filters);
        } catch (RuntimeException | LinkageError ignored) {
            // A recipe may still expose a filled gas item even without a custom gas group.
        }
        collectItemIngredients(recipeLayout.getItemStacks().getGuiIngredients(), wantInputs, filters);
        return filters;
    }

    static boolean sameFilter(ItemStack first, ItemStack second) {
        GasStack firstGas = getGas(first);
        GasStack secondGas = getGas(second);
        return firstGas != null && secondGas != null && firstGas.isGasEqual(secondGas);
    }

    private static void collectGasIngredients(
            Map<Integer, ? extends IGuiIngredient<GasStack>> ingredients,
            boolean wantInputs, List<ItemStack> filters) {
        for (IGuiIngredient<GasStack> ingredient : ingredients.values()) {
            if (ingredient.isInput() != wantInputs) {
                continue;
            }
            try {
                GasStack gas = getDisplayedOrFirstGas(ingredient);
                addFilter(filters, toRecipeFilter(gas));
            } catch (RuntimeException | LinkageError ignored) {
                // Skip one malformed optional ingredient without discarding the recipe.
            }
        }
    }

    private static void collectItemIngredients(
            Map<Integer, ? extends IGuiIngredient<ItemStack>> ingredients,
            boolean wantInputs, List<ItemStack> filters) {
        for (IGuiIngredient<ItemStack> ingredient : ingredients.values()) {
            if (ingredient.isInput() != wantInputs) {
                continue;
            }
            try {
                ItemStack stack = getDisplayedOrFirstItem(ingredient);
                addFilter(filters, toRecipeFilter(stack));
            } catch (RuntimeException | LinkageError ignored) {
                // Skip one malformed optional ingredient without discarding the recipe.
            }
        }
    }

    private static GasStack getDisplayedOrFirstGas(IGuiIngredient<GasStack> ingredient) {
        GasStack displayed = ingredient.getDisplayedIngredient();
        if (displayed != null && displayed.getGas() != null) {
            return displayed.copy();
        }
        for (GasStack candidate : ingredient.getAllIngredients()) {
            if (candidate != null && candidate.getGas() != null) {
                return candidate.copy();
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

    private static ItemStack toRecipeFilter(Object ingredient) {
        GasStack gas = ingredient instanceof GasStack
                ? (GasStack) ingredient
                : ingredient instanceof ItemStack ? getGas((ItemStack) ingredient) : null;
        if (gas == null || gas.getGas() == null || !(GAS_TANK instanceof IGasItem)) {
            return ItemStack.EMPTY;
        }

        GasStack identity = gas.copy();
        identity.amount = 1;
        ItemStack filter = new ItemStack(GAS_TANK);
        IGasItem gasItem = (IGasItem) GAS_TANK;
        gasItem.setGas(filter, identity);
        GasStack stored = gasItem.getGas(filter);
        return stored != null && stored.isGasEqual(identity) ? filter : ItemStack.EMPTY;
    }

    private static GasStack getGas(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof IGasItem)) {
            return null;
        }
        GasStack gas = ((IGasItem) stack.getItem()).getGas(stack);
        return gas == null || gas.getGas() == null ? null : gas;
    }

    private static void addFilter(List<ItemStack> filters, ItemStack candidate) {
        if (candidate.isEmpty()) {
            return;
        }
        for (ItemStack existing : filters) {
            if (sameFilter(existing, candidate)) {
                return;
            }
        }
        candidate.setCount(1);
        filters.add(candidate);
    }
}
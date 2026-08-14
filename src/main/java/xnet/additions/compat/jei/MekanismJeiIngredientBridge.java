package xnet.additions.compat.jei;

import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

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
}
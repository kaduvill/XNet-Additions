package xnet.additions.compat.jei;

import mezz.jei.api.gui.IRecipeLayout;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects the custom JEI ingredient types used by Additions channels. */
public final class XNetCustomRecipeFilterCollector {

    public static final String GAS_TYPE = "mekanism.gas";
    public static final String ESSENTIA_TYPE = "tc.essentia";

    private XNetCustomRecipeFilterCollector() {
    }

    public static List<ItemStack> collect(String typeId, IRecipeLayout recipeLayout, boolean outputs) {
        try {
            if (GAS_TYPE.equals(typeId) && Loader.isModLoaded("mekanism")) {
                return MekanismJeiIngredientBridge.collect(recipeLayout, outputs);
            }
            if (ESSENTIA_TYPE.equals(typeId) && Loader.isModLoaded("thaumcraft")) {
                return ThaumcraftJeiIngredientBridge.collect(recipeLayout, outputs);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An optional ingredient provider may be absent even though its base mod is loaded.
        }
        return Collections.emptyList();
    }

    public static List<ItemStack> merge(String typeId, List<ItemStack> existingFilters,
                                        List<ItemStack> addedFilters) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack filter : existingFilters) {
            if (filter != null && !filter.isEmpty()) {
                merged.add(filter.copy());
            }
        }
        for (ItemStack filter : addedFilters) {
            if (filter == null || filter.isEmpty()) {
                continue;
            }
            ItemStack duplicate = findEquivalent(typeId, merged, filter);
            if (duplicate == null) {
                merged.add(filter.copy());
            } else {
                duplicate.setCount(Math.max(duplicate.getCount(), filter.getCount()));
            }
        }
        return merged;
    }

    private static ItemStack findEquivalent(String typeId, List<ItemStack> filters, ItemStack candidate) {
        for (ItemStack existing : filters) {
            try {
                if (GAS_TYPE.equals(typeId) && Loader.isModLoaded("mekanism")
                        && MekanismJeiIngredientBridge.sameFilter(existing, candidate)) {
                    return existing;
                }
                if (ESSENTIA_TYPE.equals(typeId) && Loader.isModLoaded("thaumcraft")
                        && ThaumcraftJeiIngredientBridge.sameFilter(existing, candidate)) {
                    return existing;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Fall through to exact ItemStack identity below.
            }
            if (ItemStack.areItemsEqual(existing, candidate)
                    && ItemStack.areItemStackTagsEqual(existing, candidate)) {
                return existing;
            }
        }
        return null;
    }
}
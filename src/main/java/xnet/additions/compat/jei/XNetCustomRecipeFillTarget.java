package xnet.additions.compat.jei;

import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Additions-owned recipe-fill state exposed by the controller GUI mixin. */
public interface XNetCustomRecipeFillTarget {

    @Nullable
    Context xnetadditions$getCustomRecipeFillContext();

    boolean xnetadditions$applyCustomRecipeFill(Context context, List<ItemStack> filters);

    final class Context {
        private final String typeId;
        private final boolean staged;
        private final boolean outputs;
        private final int limit;
        private final List<ItemStack> existingFilters;

        public Context(String typeId, boolean staged, boolean outputs, int limit,
                       List<ItemStack> existingFilters) {
            this.typeId = typeId;
            this.staged = staged;
            this.outputs = outputs;
            this.limit = limit;
            List<ItemStack> copy = new ArrayList<>(existingFilters.size());
            for (ItemStack filter : existingFilters) {
                copy.add(filter == null || filter.isEmpty() ? ItemStack.EMPTY : filter.copy());
            }
            this.existingFilters = Collections.unmodifiableList(copy);
        }

        public String getTypeId() {
            return typeId;
        }

        public boolean isStaged() {
            return staged;
        }

        public boolean isOutputs() {
            return outputs;
        }

        public int getLimit() {
            return limit;
        }

        public List<ItemStack> getExistingFilters() {
            return existingFilters;
        }
    }
}
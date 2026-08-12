package xnet.additions.powertools.batchedit;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact, bounded codec for scalar values changed by the batch editor. */
public final class BatchValueCodec {

    private static final byte TYPE_NULL = 0;
    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_INTEGER = 2;
    private static final byte TYPE_BOOLEAN = 3;
    private static final byte TYPE_DOUBLE = 4;
    private static final byte TYPE_ITEMSTACK = 5;
    public static final int MAX_VALUES = 64;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_STRING_LENGTH = 256;

    private BatchValueCodec() {
    }

    public static NBTTagCompound write(Map<String, Object> values) {
        if (values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("Too many batch-edit values: " + values.size());
        }
        NBTTagCompound root = new NBTTagCompound();
        int written = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
                continue;
            }

            NBTTagCompound encoded = new NBTTagCompound();
            Object value = entry.getValue();
            if (value == null) {
                encoded.setByte("t", TYPE_NULL);
            } else if (value instanceof String) {
                String string = (String) value;
                if (string.length() > MAX_STRING_LENGTH) {
                    continue;
                }
                encoded.setByte("t", TYPE_STRING);
                encoded.setString("v", string);
            } else if (value instanceof Integer) {
                encoded.setByte("t", TYPE_INTEGER);
                encoded.setInteger("v", (Integer) value);
            } else if (value instanceof Boolean) {
                encoded.setByte("t", TYPE_BOOLEAN);
                encoded.setBoolean("v", (Boolean) value);
            } else if (value instanceof Double) {
                encoded.setByte("t", TYPE_DOUBLE);
                encoded.setDouble("v", (Double) value);
            } else if (value instanceof ItemStack) {
                ItemStack stack = (ItemStack) value;
                encoded.setByte("t", TYPE_ITEMSTACK);
                if (stack.isEmpty()) {
                    encoded.setBoolean("e", true);
                } else {
                    NBTTagCompound stackTag = new NBTTagCompound();
                    stack.writeToNBT(stackTag);
                    stackTag.setInteger("countInt", stack.getCount());
                    encoded.setTag("v", stackTag);
                }
            } else {
                continue;
            }
            root.setTag(key, encoded);
            written++;
        }
        return root;
    }

    public static Map<String, Object> read(NBTTagCompound root, int maximumValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (root == null || root.getKeySet().size() > maximumValues) {
            return values;
        }

        for (String key : root.getKeySet()) {
            if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH
                    || !root.hasKey(key, 10)) {
                continue;
            }
            NBTTagCompound encoded = root.getCompoundTag(key);
            if (!encoded.hasKey("t", 1)) {
                continue;
            }
            Object value = decode(encoded);
            if (value != InvalidValue.INSTANCE) {
                values.put(key, value);
            }
        }
        return values;
    }

    @Nullable
    private static Object decode(NBTTagCompound encoded) {
        switch (encoded.getByte("t")) {
            case TYPE_NULL:
                return null;
            case TYPE_STRING:
                String value = encoded.getString("v");
                return value.length() <= MAX_STRING_LENGTH ? value : InvalidValue.INSTANCE;
            case TYPE_INTEGER:
                return encoded.getInteger("v");
            case TYPE_BOOLEAN:
                return encoded.getBoolean("v");
            case TYPE_DOUBLE:
                return encoded.getDouble("v");
            case TYPE_ITEMSTACK:
                if (encoded.getBoolean("e")) return ItemStack.EMPTY;
                if (!encoded.hasKey("v", 10)) return InvalidValue.INSTANCE;
                NBTTagCompound stackTag = encoded.getCompoundTag("v");
                ItemStack stack = new ItemStack(stackTag);
                if (stack.isEmpty()) return InvalidValue.INSTANCE;
                int count = stackTag.hasKey("countInt", 3) ? stackTag.getInteger("countInt") : stack.getCount();
                if (count <= 0) return InvalidValue.INSTANCE;
                stack.setCount(count);
                return stack;
            default:
                return InvalidValue.INSTANCE;
        }
    }

    private enum InvalidValue {
        INSTANCE
    }
}

package xnet.additions.powertools.remoteconnector;

import mcjty.xnet.blocks.cables.ConnectorBlock;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class ConnectorDirectionNames {
    private ConnectorDirectionNames() {}

    public static String[] snapshot(World world, BlockPos connectorPos) {
        String[] names = new String[EnumFacing.VALUES.length];
        for (EnumFacing facing : EnumFacing.VALUES) {
            names[facing.ordinal()] = getName(world, connectorPos.offset(facing));
        }
        return names;
    }
    public static int connectedMask(World world, BlockPos connectorPos) {
        int mask = 0;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (world.isBlockLoaded(connectorPos.offset(facing), false)
                    && ConnectorBlock.isConnectable(world, connectorPos, facing)) {
                mask |= 1 << facing.ordinal();
            }
        }
        return mask;
    }
    private static String getName(World world, BlockPos pos) {
        if (!world.isBlockLoaded(pos, false)) {return null;}
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock().isAir(state, world, pos)) {return "";}
        ItemStack stack = state.getBlock().getItem(world, pos, state);
        if (stack != null && !stack.isEmpty()) {return getStackName(stack);}
        String name = state.getBlock().getTranslationKey();
        return name.endsWith(".name") ? name : name + ".name";
    }

    private static String getStackName(ItemStack stack) {
        NBTTagCompound display = getSubCompound(stack, "display");
        if (display != null) {
            if (display.hasKey("Name", 8)) {return display.getString("Name");}
            if (display.hasKey("LocName", 8)) {return display.getString("LocName");}
        }
        String name = stack.getItem().getTranslationKey(stack);
        return name.endsWith(".name") ? name : name + ".name";
    }

    private static NBTTagCompound getSubCompound(ItemStack stack, String key) {
        if (stack.getTagCompound() != null && stack.getTagCompound().hasKey(key, 10)) {
            return stack.getTagCompound().getCompoundTag(key);
        }
        return null;
    }
}
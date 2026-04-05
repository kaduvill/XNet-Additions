package xnet.additions.thaumcraft;

import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.channels.IConnectorSettings;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EssentiaChannelType implements IChannelType {

    @Override
    public String getID() {
        return "tc.essentia";
    }

    @Override
    public String getName() {
        return "Essentia (Thaumcraft)";
    }

    @Override
    public boolean supportsBlock(@Nonnull World world, @Nonnull BlockPos pos, @Nullable EnumFacing side) {
        TileEntity te = world.getTileEntity(pos);
        return EssentiaChannelSettings.getEssentiaNode(te, side) != null;
    }

    @Nonnull
    @Override
    public IConnectorSettings createConnector(@Nonnull EnumFacing side) {
        return new EssentiaConnectorSettings(side);
    }

    @Nonnull
    @Override
    public IChannelSettings createChannel() {
        return new EssentiaChannelSettings();
    }
}
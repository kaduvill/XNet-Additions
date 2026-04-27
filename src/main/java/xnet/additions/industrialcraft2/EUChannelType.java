package xnet.additions.industrialcraft2;

import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.channels.IConnectorSettings;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EUChannelType implements IChannelType {

    @Override
    public String getID() {
        return "ic2.eu";
    }

    @Override
    public String getName() {
        return "IC2 EU";
    }

    @Override
    public boolean supportsBlock(@Nonnull World world, @Nonnull BlockPos pos, @Nullable EnumFacing side) {
        return EUChannelSettings.isEUTE(world, pos);
    }

    @Override
    @Nonnull
    public IConnectorSettings createConnector(@Nonnull EnumFacing side) {
        return new EUConnectorSettings(side);
    }

    @Override
    @Nonnull
    public IChannelSettings createChannel() {
        return new EUChannelSettings();
    }
}
package xnet.additions.botania;

import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.channels.IConnectorSettings;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ManaChannelType implements IChannelType {

    @Override
    public String getID() {
        return "botania.mana";
    }

    @Override
    public String getName() {
        return "Mana (Botania)";
    }

    @Override
    public boolean supportsBlock(@Nonnull World world, @Nonnull BlockPos pos, @Nullable EnumFacing side) {
        return ManaChannelSettings.getManaNode(world.getTileEntity(pos), side) != null;
    }

    @Nonnull
    @Override
    public IConnectorSettings createConnector(@Nonnull EnumFacing side) {
        return new ManaConnectorSettings(side);
    }

    @Nonnull
    @Override
    public IChannelSettings createChannel() {
        return new ManaChannelSettings();
    }
}
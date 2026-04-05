package xnet.additions.mekanism;

import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.channels.IConnectorSettings;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class GasChannelType implements IChannelType {
	@Override
	public String getID() {
		return "mekanism.gas";
	}

	@Override
	public String getName() {
		return "Gas (Mekanism)";
	}

	@Override
	public boolean supportsBlock(@Nonnull World world, @Nonnull BlockPos pos, @Nullable EnumFacing side) {
		return GasChannelSettings.getGasHandler(world.getTileEntity(pos), side) != null;
	}

	@Nonnull
	@Override
	public IConnectorSettings createConnector(@Nonnull EnumFacing side) {
		return new GasConnectorSettings(side);
	}

	@Nonnull
	@Override
	public IChannelSettings createChannel() {
		return new GasChannelSettings();
	}
}

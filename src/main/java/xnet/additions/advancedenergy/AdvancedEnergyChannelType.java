package xnet.additions.advancedenergy;

import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.channels.IConnectorSettings;
import xnet.additions.advancedenergy.compat.FluxNetworksCompat;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.energy.CapabilityEnergy;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class AdvancedEnergyChannelType implements IChannelType {

    @Override
    public String getID() {
        return "advanced.energy";
    }

    @Override
    public String getName() {
        return "Advanced Energy";
    }

    @Override
    public boolean supportsBlock(@Nonnull World world, @Nonnull BlockPos pos, @Nullable EnumFacing side) {
        TileEntity te = world.getTileEntity(pos);
        return te != null && (
                te.hasCapability(CapabilityEnergy.ENERGY, side)
                        || FluxNetworksCompat.isFluxPoint(te)
                        || FluxNetworksCompat.isFluxPlug(te)
        );
    }

    @Override
    @Nonnull
    public IConnectorSettings createConnector(@Nonnull EnumFacing side) {
        return new AdvancedEnergyConnectorSettings(side);
    }

    @Nonnull
    @Override
    public IChannelSettings createChannel() {
        return new AdvancedEnergyChannelSettings();
    }
}
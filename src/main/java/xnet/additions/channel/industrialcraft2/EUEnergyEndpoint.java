package xnet.additions.channel.industrialcraft2;

import ic2.api.energy.EnergyNet;
import ic2.api.energy.IEnergyNet;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergyConductor;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import ic2.api.info.ILocatable;
import mcjty.lib.varia.WorldTools;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Objects;

/** A reusable, tick-scoped IC2 endpoint. It never joins the native EnergyNet. */
final class EUEnergyEndpoint {
    private final TransportPeer peer = new TransportPeer();
    private World world;
    private BlockPos position;
    private BlockPos connectorPosition;
    private EnumFacing face;
    private TileEntity physicalTile;
    private IEnergyTile ioTile;
    private IEnergyEmitter emitter;
    private IEnergyAcceptor acceptor;

    void resolve(World world, BlockPos position, BlockPos connectorPosition, EnumFacing face) {
        clear();
        if (!WorldTools.chunkLoaded(world, position)) {
            return;
        }
        this.world = world;
        this.position = position;
        this.connectorPosition = connectorPosition;
        this.face = face;
        physicalTile = world.getTileEntity(position);
        if (physicalTile != null && physicalTile.isInvalid()) {
            return;
        }
        IEnergyNet energyNet = EnergyNet.instance;
        IEnergyTile part = null;
        if (energyNet != null) {
            ioTile = energyNet.getTile(world, position);
            part = energyNet.getSubTile(world, position);
        }
        if (ioTile == null && physicalTile instanceof IEnergyTile) {
            ioTile = (IEnergyTile) physicalTile;
        }
        if (ioTile instanceof TileEntity && ((TileEntity) ioTile).isInvalid()) {
            ioTile = null;
        }
        // Check the contacted part's connectivity, but perform IO on the main delegate.
        emitter = part instanceof IEnergyEmitter ? (IEnergyEmitter) part
                : ioTile instanceof IEnergyEmitter ? (IEnergyEmitter) ioTile : null;
        acceptor = part instanceof IEnergyAcceptor ? (IEnergyAcceptor) part
                : ioTile instanceof IEnergyAcceptor ? (IEnergyAcceptor) ioTile : null;
    }

    @Nullable
    static IEnergyTile getIOTile(World world, BlockPos position) {
        if (!WorldTools.chunkLoaded(world, position)) {
            return null;
        }
        TileEntity physical = world.getTileEntity(position);
        if (physical != null && physical.isInvalid()) {
            return null;
        }
        IEnergyNet energyNet = EnergyNet.instance;
        if (energyNet != null) {
            IEnergyTile main = energyNet.getTile(world, position);
            if (main != null) {
                return main instanceof TileEntity && ((TileEntity) main).isInvalid() ? null : main;
            }
        }
        return physical instanceof IEnergyTile ? (IEnergyTile) physical : null;
    }

    boolean isLive() {
        return world != null && WorldTools.chunkLoaded(world, position)
                && (physicalTile == null || !physicalTile.isInvalid())
                && (!(ioTile instanceof TileEntity) || !((TileEntity) ioTile).isInvalid())
                && world.getTileEntity(position) == physicalTile;
    }

    boolean canExtract() {
        return ioTile instanceof IEnergySource && emitter != null && emitter.emitsEnergyTo(peer, face);
    }

    boolean canInsert() {
        return ioTile instanceof IEnergySink && acceptor != null && acceptor.acceptsEnergyFrom(peer, face);
    }

    @Nullable
    IEnergySource getSource() {
        return ioTile instanceof IEnergySource ? (IEnergySource) ioTile : null;
    }

    @Nullable
    IEnergySink getSink() {
        return ioTile instanceof IEnergySink ? (IEnergySink) ioTile : null;
    }

    EnumFacing getTravelDirection() {
        return face.getOpposite();
    }

    void clear() {
        world = null;
        position = null;
        connectorPosition = null;
        face = null;
        physicalTile = null;
        ioTile = null;
        emitter = null;
        acceptor = null;
    }

    // A plain, local API peer: no connector tile changes and no EnergyNet registration.
    // Mekanism sources require a conductor; ILocatable supports neighbour-aware predicates.
    private final class TransportPeer implements IEnergyConductor, ILocatable {
        @Override
        public BlockPos getPosition() {
            // An advanced face override does not move the physical connector.
            return Objects.requireNonNull(connectorPosition, "Inactive EU peer");
        }

        @Override
        public World getWorldObj() {
            return Objects.requireNonNull(world, "Inactive EU peer");
        }

        @Override
        public boolean acceptsEnergyFrom(IEnergyEmitter emitter, EnumFacing side) {
            return true;
        }

        @Override
        public boolean emitsEnergyTo(IEnergyAcceptor receiver, EnumFacing side) {
            return true;
        }

        @Override
        public double getConductionLoss() {
            return 0.0D;
        }

        @Override
        public double getInsulationEnergyAbsorption() {
            return Double.MAX_VALUE;
        }

        @Override
        public double getInsulationBreakdownEnergy() {
            return Double.MAX_VALUE;
        }

        @Override
        public double getConductorBreakdownEnergy() {
            return Double.MAX_VALUE;
        }

        @Override
        public void removeInsulation() {
        }

        @Override
        public void removeConductor() {
        }
    }
}

package xnet.additions.advancedenergy.compat;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sonar.fluxnetworks.api.network.FluxLogicType;
import sonar.fluxnetworks.api.network.IFluxNetwork;
import sonar.fluxnetworks.api.network.ITransferHandler;
import sonar.fluxnetworks.api.tiles.IFluxConnector;
import sonar.fluxnetworks.api.tiles.IFluxPlug;
import sonar.fluxnetworks.api.tiles.IFluxPoint;

import java.util.List;
import java.util.function.BooleanSupplier;

public class FluxNetworksHooksImpl implements FluxNetworksCompat.Hooks {

    private static final Logger LOGGER = LogManager.getLogger(FluxNetworksHooksImpl.class);

    @Override
    public boolean isFluxPoint(TileEntity tile) {
        return tile instanceof IFluxPoint;
    }

    @Override
    public boolean isFluxPlug(TileEntity tile) {
        return tile instanceof IFluxPlug;
    }

    @Override
    public long getFluxTransferBuffer(TileEntity tile) {
        if (!(tile instanceof IFluxConnector)) {
            return 0L;
        }

        return positive(((IFluxConnector) tile).getTransferBuffer());
    }

    @Override
    public long receiveToFluxPlug(TileEntity plugTile,
                                  EnumFacing side,
                                  long maxReceive,
                                  boolean simulate) {
        if (!(plugTile instanceof IFluxPlug) || side == null || maxReceive <= 0) {
            return 0L;
        }

        IFluxPlug plug = (IFluxPlug) plugTile;
        if (!isUsable(plug)) {
            return 0L;
        }

        return positive(plug.getTransferHandler().receiveFromSupplier(maxReceive, side, simulate));
    }

    @Override
    public long transferFromFluxPointNetwork(TileEntity pointTile,
                                             FluxNetworksCompat.LongEnergyReceiver target,
                                             long remainingDemand,
                                             BooleanSupplier operationCostPayer,
                                             long worldTime,
                                             Object extractConsumerId,
                                             Object insertConsumerId,
                                             BlockPos fluxPointPos,
                                             BlockPos targetPos) {
        if (!(pointTile instanceof IFluxPoint) || remainingDemand <= 0) {
            return 0L;
        }

        IFluxPoint point = (IFluxPoint) pointTile;
        if (!isUsable(point)) {
            return 0L;
        }

        IFluxNetwork network = point.getNetwork();
        if (network == null || network.isInvalid()) {
            return 0L;
        }

        long targetDemand = target.receive(remainingDemand, true);
        if (targetDemand <= 0) {
            return 0L;
        }

        long remaining = Math.min(remainingDemand, targetDemand);
        long moved = 0L;
        boolean paid = false;

        List<IFluxPlug> plugs = network.getConnections(FluxLogicType.PLUG);
        for (IFluxPlug plug : plugs) {
            if (remaining <= 0) {
                break;
            }

            if (plug == null || plug == point || !isUsable(plug) || plug.getNetwork() != network) {
                continue;
            }

            ITransferHandler sourceHandler = plug.getTransferHandler();
            long sourceBuffer = positive(sourceHandler.getBuffer());
            if (sourceBuffer <= 0) {
                continue;
            }

            long toRemove = Math.min(remaining, sourceBuffer);
            if (toRemove <= 0) {
                continue;
            }

            if (!paid) {
                if (!operationCostPayer.getAsBoolean()) {
                    return moved;
                }
                paid = true;
            }

            long removed = positive(sourceHandler.removeFromBuffer(toRemove));
            if (removed <= 0) {
                continue;
            }

            long inserted = positive(target.receive(removed, false));
            moved += inserted;
            remaining -= inserted;

            long leftover = removed - inserted;
            if (leftover > 0) {
                sourceHandler.addToBuffer(leftover);

                LOGGER.warn("Flux point transfer insert mismatch: removed={}, inserted={}, returnedToSource={}, sourcePoint={}, target={}",
                        removed, inserted, leftover, fluxPointPos, targetPos);
                break;
            }
        }

        return moved;
    }

    private static boolean isUsable(IFluxConnector connector) {
        if (connector == null || !connector.isActive() || !connector.isChunkLoaded()) {
            return false;
        }

        IFluxNetwork network = connector.getNetwork();
        return network != null && !network.isInvalid() && connector.getTransferHandler() != null;
    }

    private static long positive(long value) {
        return value > 0 ? value : 0L;
    }
}
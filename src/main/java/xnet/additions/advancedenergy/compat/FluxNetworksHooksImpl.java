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
import sonar.fluxnetworks.api.tiles.IFluxStorage;

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
    public boolean isFluxStorage(TileEntity tile) {
        return tile instanceof IFluxStorage;
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

        long targetDemand = positive(target.receive(remainingDemand, true));
        if (targetDemand <= 0) {
            return 0L;
        }

        long remaining = Math.min(remainingDemand, targetDemand);
        long moved = 0L;
        boolean paid = false;

        // Important:
        // Only Flux Storage is rollback-capable. Ordinary Flux Plugs support
        // removeFromBuffer(), but do not support addToBuffer(), so they are unsafe
        // as arbitrary XNet extraction sources.
        List<IFluxStorage> storages = network.getConnections(FluxLogicType.STORAGE);

        for (IFluxStorage storage : storages) {
            if (remaining <= 0) {
                break;
            }

            if (storage == null || !isUsable(storage) || storage.getNetwork() != network) {
                continue;
            }

            ITransferHandler sourceHandler = storage.getTransferHandler();
            long sourceBuffer = positive(sourceHandler.getBuffer());
            if (sourceBuffer <= 0) {
                continue;
            }

            // Re-check target demand before removing from storage.
            long targetNow = positive(target.receive(Math.min(remaining, sourceBuffer), true));
            if (targetNow <= 0) {
                break;
            }

            long toRemove = Math.min(Math.min(remaining, sourceBuffer), targetNow);
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

            // Final safety check after removal. If target demand changed, roll the
            // whole amount back into the same Flux Storage handler.
            long targetAfterRemove;
            try {
                targetAfterRemove = positive(target.receive(removed, true));
            } catch (RuntimeException e) {
                returnToStorage(sourceHandler, removed, fluxPointPos, targetPos, "target-simulation-exception");

                LOGGER.warn("Flux storage transfer target simulation failed after removal. Rolled back: removed={}, sourcePoint={}, target={}",
                        removed,
                        fluxPointPos,
                        targetPos,
                        e);
                return moved;
            }

            if (targetAfterRemove < removed) {
                returnToStorage(sourceHandler, removed, fluxPointPos, targetPos, "target-demand-shrank");

                LOGGER.warn("Flux storage transfer target demand shrank after removal. Rolled back: removed={}, targetCanAccept={}, sourcePoint={}, target={}",
                        removed,
                        targetAfterRemove,
                        fluxPointPos,
                        targetPos);
                return moved;
            }

            long inserted;
            try {
                inserted = positive(target.receive(removed, false));
            } catch (RuntimeException e) {
                returnToStorage(sourceHandler, removed, fluxPointPos, targetPos, "target-insert-exception");

                LOGGER.warn("Flux storage transfer target insert failed after removal. Rolled back: removed={}, sourcePoint={}, target={}",
                        removed,
                        fluxPointPos,
                        targetPos,
                        e);
                return moved;
            }

            moved += inserted;
            remaining -= inserted;

            long leftover = removed - inserted;
            if (leftover > 0) {
                returnToStorage(sourceHandler, leftover, fluxPointPos, targetPos, "insert-mismatch");

                LOGGER.warn("Flux storage transfer insert mismatch. Rolled back leftover: removed={}, inserted={}, returnedToStorage={}, sourcePoint={}, target={}",
                        removed,
                        inserted,
                        leftover,
                        fluxPointPos,
                        targetPos);
                return moved;
            }
        }

        return moved;
    }

    private static void returnToStorage(ITransferHandler sourceHandler,
                                        long amount,
                                        BlockPos fluxPointPos,
                                        BlockPos targetPos,
                                        String reason) {
        if (amount <= 0) {
            return;
        }

        try {
            long before = positive(sourceHandler.getBuffer());
            sourceHandler.addToBuffer(amount);
            long after = positive(sourceHandler.getBuffer());

            long returned = Math.max(0L, after - before);
            if (returned < amount) {
                LOGGER.error("Flux storage rollback may be incomplete: reason={}, amount={}, observedReturned={}, sourcePoint={}, target={}",
                        reason, amount, returned, fluxPointPos, targetPos);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Flux storage rollback failed: reason={}, amount={}, sourcePoint={}, target={}",
                    reason, amount, fluxPointPos, targetPos, e);
        }
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
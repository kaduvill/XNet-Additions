package xnet.additions.channel.advancedenergy.compat;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BooleanSupplier;

public final class FluxNetworksCompat {

    private static final Logger LOGGER = LogManager.getLogger(FluxNetworksCompat.class);
    private static final String MODID = "fluxnetworks";

    private static final Hooks HOOKS = createHooks();

    private FluxNetworksCompat() {
    }

    @FunctionalInterface
    public interface LongEnergyReceiver {
        long receive(long maxReceive, boolean simulate);
    }

    public interface Hooks {
        boolean isFluxPoint(TileEntity tile);

        boolean isFluxPlug(TileEntity tile);

        boolean isFluxStorage(TileEntity tile);

        long getFluxTransferBuffer(TileEntity tile);

        long receiveToFluxPlug(TileEntity plugTile,
                               EnumFacing side,
                               long maxReceive,
                               boolean simulate);

        long transferFromFluxPointNetwork(TileEntity pointTile,
                                          LongEnergyReceiver target,
                                          long remainingDemand,
                                          BooleanSupplier operationCostPayer,
                                          long worldTime,
                                          Object extractConsumerId,
                                          Object insertConsumerId,
                                          BlockPos fluxPointPos,
                                          BlockPos targetPos);
    }

    private static Hooks createHooks() {
        if (!Loader.isModLoaded(MODID)) {
            return NoopHooks.INSTANCE;
        }

        try {
            Class<?> clazz = Class.forName("xnet.additions.channel.advancedenergy.compat.FluxNetworksHooksImpl");
            return (Hooks) clazz.newInstance();
        } catch (Throwable e) {
            LOGGER.warn("Flux Networks detected, but Advanced Energy Flux integration could not be loaded. Falling back to Forge energy behavior.", e);
            return NoopHooks.INSTANCE;
        }
    }

    public static boolean isFluxPoint(TileEntity tile) {
        return HOOKS.isFluxPoint(tile);
    }

    public static boolean isFluxPlug(TileEntity tile) {
        return HOOKS.isFluxPlug(tile);
    }

    public static boolean isFluxStorage(TileEntity tile) {return HOOKS.isFluxStorage(tile);}

    public static long getFluxTransferBuffer(TileEntity tile) {
        return HOOKS.getFluxTransferBuffer(tile);
    }

    public static long receiveToFluxPlug(TileEntity plugTile,
                                         EnumFacing side,
                                         long maxReceive,
                                         boolean simulate) {
        return HOOKS.receiveToFluxPlug(plugTile, side, maxReceive, simulate);
    }

    public static long transferFromFluxPointNetwork(TileEntity pointTile,
                                                    LongEnergyReceiver target,
                                                    long remainingDemand,
                                                    BooleanSupplier operationCostPayer,
                                                    long worldTime,
                                                    Object extractConsumerId,
                                                    Object insertConsumerId,
                                                    BlockPos fluxPointPos,
                                                    BlockPos targetPos) {
        try {
            return HOOKS.transferFromFluxPointNetwork(
                    pointTile,
                    target,
                    remainingDemand,
                    operationCostPayer,
                    worldTime,
                    extractConsumerId,
                    insertConsumerId,
                    fluxPointPos,
                    targetPos
            );
        } catch (RuntimeException e) {
            LOGGER.warn("Flux Networks transfer failed. Skipping transfer: point={}, target={}, extractor={}, inserter={}",
                    fluxPointPos,
                    targetPos,
                    extractConsumerId,
                    insertConsumerId,
                    e);
            return 0L;
        }
    }

    private enum NoopHooks implements Hooks {
        INSTANCE;

        @Override
        public boolean isFluxPoint(TileEntity tile) {
            return false;
        }

        @Override
        public boolean isFluxPlug(TileEntity tile) {
            return false;
        }

        @Override
        public boolean isFluxStorage(TileEntity tile) {return false;}

        @Override
        public long getFluxTransferBuffer(TileEntity tile) {
            return 0L;
        }

        @Override
        public long receiveToFluxPlug(TileEntity plugTile,
                                      EnumFacing side,
                                      long maxReceive,
                                      boolean simulate) {
            return 0L;
        }

        @Override
        public long transferFromFluxPointNetwork(TileEntity pointTile,
                                                 LongEnergyReceiver target,
                                                 long remainingDemand,
                                                 BooleanSupplier operationCostPayer,
                                                 long worldTime,
                                                 Object extractConsumerId,
                                                 Object insertConsumerId,
                                                 BlockPos fluxPointPos,
                                                 BlockPos targetPos) {
            return 0L;
        }
    }
}
package xnet.additions.advancedenergy;

import com.google.gson.JsonObject;
import mcjty.lib.varia.WorldTools;
import mcjty.xnet.XNet;
import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IControllerContext;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.DefaultChannelSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.config.ConfigSetup;
import xnet.additions.advancedenergy.compat.FluxNetworksCompat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import org.apache.commons.lang3.tuple.Pair;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;


public class AdvancedEnergyChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    private static final Logger LOGGER = LogManager.getLogger(AdvancedEnergyChannelSettings.class);

    public static final ResourceLocation iconGuiElements = new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");
    // Cache data
    private List<Pair<SidedConsumer, AdvancedEnergyConnectorSettings>> energyExtractors = null;
    private List<Pair<SidedConsumer, AdvancedEnergyConnectorSettings>> energyConsumers = null;
    private Map<SidedConsumer, CachedEnergyEndpoint> insertEndpointCache = null;
    private Map<SidedConsumer, CachedEnergyEndpoint> extractEndpointCache = null;
    private long lastHandledWorldTick = Long.MIN_VALUE;

    // Adaptive no-demand cooldown for insert connectors only.
    // This avoids repeatedly simulating full/idle sinks every tick in huge bases.
    private Map<SidedConsumer, NoDemandState> noDemandDelays = null;

    // Stepwise cooldown after repeated no-demand results.
    // Keep this small and simple: no provider tracking, no averages, no source history.
    private static final int[] NO_DEMAND_DELAYS = { 0, 4, 20, 80, 200 };
    private static final int ADAPTIVE_MAX_MANUAL_SPEED = 20;

    private enum EnergyEndpointType {
        FORGE,
        FLUX_POINT,
        FLUX_PLUG
    }

    @Override
    public JsonObject writeToJson() {
        return new JsonObject();
    }

    @Override
    public void readFromJson(JsonObject data) {
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
    }

    @Override
    public int getColors() {
        return 0;
    }

    private static class TransferCost {
        private boolean paid;
    }

    private static class NoDemandState {
        private long nextCheckTick;
        private byte level;
    }

    private static boolean usesAdaptiveNoDemand(AdvancedEnergyConnectorSettings settings) {
        return settings.getSpeed() <= ADAPTIVE_MAX_MANUAL_SPEED;
    }

    private boolean payOperationCost(IControllerContext context, TransferCost cost) {
        if (cost.paid) {
            return true;
        }

        if (!context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
            return false;
        }

        cost.paid = true;
        return true;
    }

    private static class CachedEnergyEndpoint {
        private final BlockPos connectorPos;
        private final BlockPos energyPos;

        // Connector side: direction from the XNet connector to the adjacent energy tile.
        private final EnumFacing connectorSide;

        // Configured Forge capability side.
        private final EnumFacing facing;

        private final TileEntity tile;

        @Nullable
        private final IEnergyStorage handler;

        private final EnergyEndpointType endpointType;

        private CachedEnergyEndpoint(BlockPos connectorPos,
                                     BlockPos energyPos,
                                     EnumFacing connectorSide,
                                     EnumFacing facing,
                                     TileEntity tile,
                                     @Nullable IEnergyStorage handler,
                                     EnergyEndpointType endpointType) {
            this.connectorPos = connectorPos;
            this.energyPos = energyPos;
            this.connectorSide = connectorSide;
            this.facing = facing;
            this.tile = tile;
            this.handler = handler;
            this.endpointType = endpointType;
        }
    }

    private static EnergyEndpointType getEndpointType(@Nullable TileEntity te) {
        if (te != null && FluxNetworksCompat.isFluxPoint(te)) {
            return EnergyEndpointType.FLUX_POINT;
        }

        if (te != null && FluxNetworksCompat.isFluxPlug(te)) {
            return EnergyEndpointType.FLUX_PLUG;
        }

        return EnergyEndpointType.FORGE;
    }

    @Nullable
    private CachedEnergyEndpoint resolveInsertEndpoint(IControllerContext context, World world,
                                                       SidedConsumer consumer,
                                                       AdvancedEnergyConnectorSettings settings) {
        if (insertEndpointCache == null) {
            insertEndpointCache = new HashMap<>();
        }

        CachedEnergyEndpoint cached = insertEndpointCache.get(consumer);

        if (cached != null) {
            if (cached.facing == settings.getFacing()
                    && cached.tile != null
                    && !cached.tile.isInvalid()
                    && WorldTools.chunkLoaded(world, cached.energyPos)) {

                // Keep redstone/color dynamic. Do not invalidate the cache for these.
                if (checkRedstone(world, settings, cached.connectorPos)) {
                    return null;
                }
                if (!context.matchColor(settings.getColorsMask())) {
                    return null;
                }

                return cached;
            }

            insertEndpointCache.remove(consumer);
        }

        BlockPos connectorPos = context.findConsumerPosition(consumer.getConsumerId());
        if (connectorPos == null) {
            return null;
        }

        // Keep these before capability lookup.
        if (checkRedstone(world, settings, connectorPos)) {
            return null;
        }
        if (!context.matchColor(settings.getColorsMask())) {
            return null;
        }

        EnumFacing side = consumer.getSide();
        BlockPos energyPos = connectorPos.offset(side);

        if (!WorldTools.chunkLoaded(world, energyPos)) {
            return null;
        }

        TileEntity te = world.getTileEntity(energyPos);
        if (te == null) {
            return null;
        }

        EnergyEndpointType endpointType = getEndpointType(te);

// Flux Point is an output, not an input.
        if (endpointType == EnergyEndpointType.FLUX_POINT) {
            return null;
        }

        IEnergyStorage handler = endpointType == EnergyEndpointType.FORGE
                ? getEnergyHandlerAt(te, settings.getFacing())
                : null;

        if (endpointType == EnergyEndpointType.FORGE && handler == null) {
            return null;
        }

        CachedEnergyEndpoint endpoint = new CachedEnergyEndpoint(
                connectorPos,
                energyPos,
                side,
                settings.getFacing(),
                te,
                handler,
                endpointType
        );

        insertEndpointCache.put(consumer, endpoint);
        return endpoint;
    }

    @Nullable
    private CachedEnergyEndpoint resolveExtractEndpoint(IControllerContext context, World world,
                                                        SidedConsumer consumer,
                                                        AdvancedEnergyConnectorSettings settings) {
        if (extractEndpointCache == null) {
            extractEndpointCache = new HashMap<>();
        }

        CachedEnergyEndpoint cached = extractEndpointCache.get(consumer);

        if (cached != null) {
            if (cached.facing == settings.getFacing()
                    && cached.tile != null
                    && !cached.tile.isInvalid()
                    && WorldTools.chunkLoaded(world, cached.energyPos)) {

                // Keep redstone/color dynamic. Do not invalidate the cache for these.
                if (checkRedstone(world, settings, cached.connectorPos)) {
                    return null;
                }
                if (!context.matchColor(settings.getColorsMask())) {
                    return null;
                }

                return cached;
            }
            extractEndpointCache.remove(consumer);
        }

        BlockPos connectorPos = context.findConsumerPosition(consumer.getConsumerId());
        if (connectorPos == null) {
            return null;
        }

        // Keep these before capability lookup.
        if (checkRedstone(world, settings, connectorPos)) {
            return null;
        }
        if (!context.matchColor(settings.getColorsMask())) {
            return null;
        }

        EnumFacing side = consumer.getSide();
        BlockPos energyPos = connectorPos.offset(side);

        if (!WorldTools.chunkLoaded(world, energyPos)) {
            return null;
        }
        TileEntity te = world.getTileEntity(energyPos);
        if (te == null) {
            return null;
        }

        EnergyEndpointType endpointType = getEndpointType(te);

// Flux Plug is an input, not an output.
        if (endpointType == EnergyEndpointType.FLUX_PLUG) {
            return null;
        }

        IEnergyStorage handler = endpointType == EnergyEndpointType.FORGE
                ? getEnergyHandlerAt(te, settings.getFacing())
                : null;

        // Flux Point is allowed without Forge extraction.
        // Forge endpoints still need a Forge handler.
        if (endpointType == EnergyEndpointType.FORGE && handler == null) {
            return null;
        }

        CachedEnergyEndpoint endpoint = new CachedEnergyEndpoint(
                connectorPos,
                energyPos,
                side,
                settings.getFacing(),
                te,
                handler,
                endpointType
        );
        extractEndpointCache.put(consumer, endpoint);
        return endpoint;
    }

    private boolean isInserterDue(SidedConsumer consumer, AdvancedEnergyConnectorSettings settings, long worldTime) {
        int speed = settings.getSpeed();
        if (speed <= 1) {
            return true;
        }

        int phase = consumer.getConsumerId().getId() % speed;
        return (worldTime % speed) == phase;
    }

    private boolean isNoDemandDelayed(SidedConsumer consumer, long worldTime) {
        if (noDemandDelays == null) {
            return false;
        }

        NoDemandState state = noDemandDelays.get(consumer);
        return state != null && worldTime < state.nextCheckTick;
    }

    private void recordNoDemand(SidedConsumer consumer, long worldTime) {
        if (noDemandDelays == null) {
            noDemandDelays = new HashMap<>();
        }

        NoDemandState state = noDemandDelays.get(consumer);
        if (state == null) {
            state = new NoDemandState();
            noDemandDelays.put(consumer, state);
        }

        int level = state.level;
        if (level < NO_DEMAND_DELAYS.length - 1) {
            level++;
        }

        state.level = (byte) level;
        state.nextCheckTick = worldTime + NO_DEMAND_DELAYS[level];
    }

    private void clearNoDemand(SidedConsumer consumer) {
        if (noDemandDelays == null || noDemandDelays.isEmpty()) {
            return;
        }
        noDemandDelays.remove(consumer);

        if (noDemandDelays.isEmpty()) {
            noDemandDelays = null;
        }
    }

    @Override
    public void tick(int channel, IControllerContext context) {
        World world = context.getControllerWorld();
        long worldTime = world.getTotalWorldTime();

        // Defensive guard: this channel must not do multiple full transfer passes in the same world tick.
        if (lastHandledWorldTick == worldTime) {
            return;
        }
        lastHandledWorldTick = worldTime;

        updateCache(channel, context);

        if (energyConsumers.isEmpty() || energyExtractors.isEmpty()) {
            return;
        }

        // Demand-driven:
        // Inserters decide when the channel does work.
        // Extractors are only pulled from when a due inserter has real simulated demand.
        for (Pair<SidedConsumer, AdvancedEnergyConnectorSettings> entry : energyConsumers) {
            SidedConsumer insertConsumer = entry.getKey();
            AdvancedEnergyConnectorSettings insertSettings = entry.getValue();

            if (!isInserterDue(insertConsumer, insertSettings, worldTime)) {
                continue;
            }

            // Manual timing is the base cadence.
            // Adaptive no-demand delay only applies to fast/manual-low insert connectors.
            if (usesAdaptiveNoDemand(insertSettings) && isNoDemandDelayed(insertConsumer, worldTime)) {
                continue;
            }

            CachedEnergyEndpoint insertEndpoint = resolveInsertEndpoint(context, world, insertConsumer, insertSettings);
            if (insertEndpoint == null) {
                clearNoDemand(insertConsumer);
                continue;
            }

            tickInsertDemand(context, world, insertConsumer, insertSettings, insertEndpoint, worldTime);
        }
    }

    private void tickInsertDemand(IControllerContext context, World world,
                                  SidedConsumer insertConsumer,
                                  AdvancedEnergyConnectorSettings insertSettings,
                                  CachedEnergyEndpoint insertEndpoint,
                                  long worldTime) {
        long demand = getInsertDemand(insertEndpoint, insertSettings);
        if (demand <= 0) {
            if (usesAdaptiveNoDemand(insertSettings)) {
                recordNoDemand(insertConsumer, worldTime);
            }
            return;
        }

        if (usesAdaptiveNoDemand(insertSettings)) {
            clearNoDemand(insertConsumer);
        }
        transferIntoDemandingTarget(context, world, insertConsumer, insertEndpoint, demand, worldTime);
    }

    private long getInsertDemand(@Nonnull CachedEnergyEndpoint targetEndpoint,
                                 AdvancedEnergyConnectorSettings insertSettings) {
        long maxInsert = getRateLimit(insertSettings);

        Integer insertMax = insertSettings.getMinmax();
        if (insertMax != null) {
            long roomUntilMax = (long) insertMax - getEndpointStored(targetEndpoint);
            if (roomUntilMax <= 0) {
                return 0L;
            }

            if (maxInsert > roomUntilMax) {
                maxInsert = roomUntilMax;
            }
        }

        return receiveEndpoint(targetEndpoint, maxInsert, true);
    }

    private void transferIntoDemandingTarget(IControllerContext context, World world,
                                             SidedConsumer insertConsumer,
                                             CachedEnergyEndpoint insertEndpoint,
                                             long demand,
                                             long worldTime) {
        long remainingDemand = demand;
        TransferCost transferCost = new TransferCost();

        for (Pair<SidedConsumer, AdvancedEnergyConnectorSettings> entry : energyExtractors) {
            if (remainingDemand <= 0) {
                return;
            }

            SidedConsumer extractConsumer = entry.getKey();
            AdvancedEnergyConnectorSettings extractSettings = entry.getValue();

            CachedEnergyEndpoint extractEndpoint = resolveExtractEndpoint(context, world, extractConsumer, extractSettings);
            if (extractEndpoint == null) {
                continue;
            }

            // Re-check target demand before touching any source.
            // This matters especially for Flux Plug targets because their accepted amount
            // can change after earlier sources filled the plug buffer.
            long targetNow = receiveEndpoint(insertEndpoint, remainingDemand, true);
            if (targetNow <= 0) {
                return;
            }

            long wantedByTarget = Math.min(remainingDemand, targetNow);

            if (extractEndpoint.endpointType == EnergyEndpointType.FLUX_POINT) {
                long moved = FluxNetworksCompat.transferFromFluxPointNetwork(
                        extractEndpoint.tile,
                        (maxReceive, simulate) -> receiveEndpoint(insertEndpoint, maxReceive, simulate),
                        wantedByTarget,
                        () -> payOperationCost(context, transferCost),
                        worldTime,
                        extractConsumer.getConsumerId(),
                        insertConsumer.getConsumerId(),
                        extractEndpoint.energyPos,
                        insertEndpoint.energyPos
                );

                if (moved > 0) {
                    remainingDemand -= moved;
                }

                continue;
            }

            IEnergyStorage source = extractEndpoint.handler;
            if (source == null) {
                continue;
            }

            long maxExtract = getExtractAmountWanted(source, extractSettings, wantedByTarget);
            if (maxExtract <= 0) {
                continue;
            }

            long available = extractEnergy(source, maxExtract, true);
            if (available <= 0) {
                continue;
            }

            long toMove = Math.min(wantedByTarget, available);
            if (toMove <= 0) {
                continue;
            }

            if (!payOperationCost(context, transferCost)) {
                return;
            }

            long extracted = extractEnergy(source, toMove, false);
            if (extracted <= 0) {
                continue;
            }

            // Re-check one final time after extraction. This cannot make Forge fully transactional,
            // but it prevents knowingly inserting into a target that already reports zero demand.
            long targetAfterExtract = receiveEndpoint(insertEndpoint, extracted, true);
            if (targetAfterExtract <= 0) {
                LOGGER.warn("Energy target stopped accepting after extraction: extracted={}, source={}, target={}",
                        extracted, extractEndpoint.energyPos, insertEndpoint.energyPos);
                return;
            }

            long inserted = receiveEndpoint(insertEndpoint, Math.min(extracted, targetAfterExtract), false);

            if (inserted != extracted) {
                LOGGER.warn("Energy insert mismatch: extracted={}, inserted={}, source={}, target={}",
                        extracted, inserted, extractEndpoint.energyPos, insertEndpoint.energyPos);

                // Important: do not continue extracting from more sources after a mismatch.
                // Continuing is what caused the repeated extracted>0 inserted=0 spam.
                return;
            }
            remainingDemand -= inserted;
        }
    }

    private long getExtractAmountWanted(@Nonnull IEnergyStorage source,
                                        AdvancedEnergyConnectorSettings extractSettings,
                                        long remainingDemand) {
        if (!source.canExtract()) {
            return 0L;
        }

        long maxExtract = Math.min(remainingDemand, getRateLimit(extractSettings));

        Integer extractMin = extractSettings.getMinmax();
        if (extractMin != null) {
            long canExtractAboveMin = (long) source.getEnergyStored() - (long) extractMin;
            if (canExtractAboveMin <= 0) {
                return 0L;
            }

            if (maxExtract > canExtractAboveMin) {
                maxExtract = canExtractAboveMin;
            }
        }

        return maxExtract;
    }

    private static long getRateLimit(AdvancedEnergyConnectorSettings settings) {
        Integer rate = settings.getRate();

        if (rate == null || rate <= 0) {
            return Long.MAX_VALUE;
        }

        return rate;
    }

    private static long getEndpointStored(CachedEnergyEndpoint endpoint) {
        switch (endpoint.endpointType) {
            case FLUX_PLUG:
            case FLUX_POINT:
                return FluxNetworksCompat.getFluxTransferBuffer(endpoint.tile);

            case FORGE:
            default:
                return endpoint.handler == null ? 0L : Math.max(0L, (long) endpoint.handler.getEnergyStored());
        }
    }

    private static long receiveEndpoint(CachedEnergyEndpoint endpoint, long maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0L;
        }

        switch (endpoint.endpointType) {
            case FLUX_PLUG:
                return FluxNetworksCompat.receiveToFluxPlug(
                        endpoint.tile,
                        getFluxSupplierSide(endpoint),
                        maxReceive,
                        simulate
                );

            case FORGE:
                return endpoint.handler == null ? 0L : receiveEnergy(endpoint.handler, maxReceive, simulate);

            case FLUX_POINT:
            default:
                return 0L;
        }
    }

    private static EnumFacing getFluxSupplierSide(CachedEnergyEndpoint endpoint) {
        // Flux Plug expects the side where the supplier is adjacent.
        // XNet connectorSide is connector -> flux tile, so invert it.
        return endpoint.connectorSide.getOpposite();
    }

    @Nullable
    public static IEnergyStorage getEnergyHandlerAt(@Nullable TileEntity te, EnumFacing facing)
    {
        if (te != null && te.hasCapability(CapabilityEnergy.ENERGY, facing))
        {
            return te.getCapability(CapabilityEnergy.ENERGY, facing);
        }
        return null;
    }

    @Override
    public void cleanCache() {
        energyExtractors = null;
        energyConsumers = null;
        insertEndpointCache = null;
        extractEndpointCache = null;
        noDemandDelays = null;
    }

    private void updateCache(int channel, IControllerContext context) {
        if (energyExtractors == null) {
            energyExtractors = new ArrayList<>();
            energyConsumers = new ArrayList<>();
            insertEndpointCache = new HashMap<>();
            extractEndpointCache = new HashMap<>();

            Set<String> seenExtractors = new HashSet<>();
            Set<String> seenConsumers = new HashSet<>();

            Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                AdvancedEnergyConnectorSettings con = (AdvancedEnergyConnectorSettings) entry.getValue();

                if (con.getEnergyMode() == AdvancedEnergyConnectorSettings.EnergyMode.EXT) {
                    if (seenExtractors.add(consumerCacheKey(entry.getKey()))) {
                        energyExtractors.add(Pair.of(entry.getKey(), con));
                    }
                } else {
                    if (seenConsumers.add(consumerCacheKey(entry.getKey()))) {
                        energyConsumers.add(Pair.of(entry.getKey(), con));
                    }
                }
            }

            connectors = context.getRoutedConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                AdvancedEnergyConnectorSettings con = (AdvancedEnergyConnectorSettings) entry.getValue();

                if (con.getEnergyMode() == AdvancedEnergyConnectorSettings.EnergyMode.INS) {
                    if (seenConsumers.add(consumerCacheKey(entry.getKey()))) {
                        energyConsumers.add(Pair.of(entry.getKey(), con));
                    }
                }
            }

            energyExtractors.sort((o1, o2) -> o2.getRight().getPriority().compareTo(o1.getRight().getPriority()));
            energyConsumers.sort((o1, o2) -> o2.getRight().getPriority().compareTo(o1.getRight().getPriority()));
        }
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(iconGuiElements, 11, 80, 11, 10);
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
    }

    @Override
    public void update(Map<String, Object> data) {
    }

    private static int clampToInt(long amount) {
        if (amount <= 0) {
            return 0;
        }
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    private static long receiveEnergy(IEnergyStorage handler, long max, boolean simulate) {
        if (max <= 0) {
            return 0L;
        }

        int request = clampToInt(max);
        int received = handler.receiveEnergy(request, simulate);
        return Math.max(0L, (long) received);
    }

    private static long extractEnergy(IEnergyStorage handler, long max, boolean simulate) {
        if (max <= 0) {
            return 0L;
        }

        int request = clampToInt(max);
        int extracted = handler.extractEnergy(request, simulate);
        return Math.max(0L, (long) extracted);
    }

    private static String consumerCacheKey(SidedConsumer consumer) {
        return consumer.getConsumerId().getId() + ":" + String.valueOf(consumer.getSide());
    }
}
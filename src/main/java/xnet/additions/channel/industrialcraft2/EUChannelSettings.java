package xnet.additions.channel.industrialcraft2;

import com.google.gson.JsonObject;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import mcjty.lib.varia.WorldTools;
import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IControllerContext;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.DefaultChannelSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.config.ConfigSetup;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class EUChannelSettings extends DefaultChannelSettings implements IChannelSettings {
    private static final Logger LOGGER = LogManager.getLogger(EUChannelSettings.class);

    private List<ConnectorRuntime> euExtractors;
    private List<ConnectorRuntime> euConsumers;
    // Different multiblock ports may resolve to the same IO delegate.
    private final Map<IEnergySource, SourceBudget> sourceBudgets = new IdentityHashMap<>();
    private long lastHandledWorldTick = Long.MIN_VALUE;
    private long nextWarningTick = Long.MIN_VALUE;

    private static final class SourceBudget {
        private double remaining;
    }

    private static final class ConnectorRuntime {
        private final SidedConsumer consumer;
        private final EUConnectorSettings settings;
        private final EUEnergyEndpoint endpoint = new EUEnergyEndpoint();
        private final SourceBudget ownSourceBudget = new SourceBudget();
        private boolean prepared;
        private boolean operationPaid;
        private double remainingRate;
        private SourceBudget sourceBudget;

        private ConnectorRuntime(SidedConsumer consumer, EUConnectorSettings settings) {
            this.consumer = consumer;
            this.settings = settings;
        }

        private void clearTick() {
            endpoint.clear();
            prepared = false;
            operationPaid = false;
            remainingRate = 0.0D;
            sourceBudget = null;
            ownSourceBudget.remaining = 0.0D;
        }
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

    @Override
    public void tick(int channel, IControllerContext context) {
        World world = context.getControllerWorld();
        if (world.isRemote) {
            return;
        }
        long worldTick = world.getTotalWorldTime();
        if (lastHandledWorldTick == worldTick) {
            return;
        }
        lastHandledWorldTick = worldTick;
        updateCache(channel, context);
        List<ConnectorRuntime> extractors = euExtractors;
        List<ConnectorRuntime> consumers = euConsumers;
        if (extractors.isEmpty() || consumers.isEmpty()) {
            return;
        }

        ConnectorRuntime active = null;
        try {
            int firstPotentialExtractor = 0;
            for (ConnectorRuntime insert : consumers) {
                if (firstPotentialExtractor == extractors.size()) {
                    return;
                }
                active = insert;
                prepare(insert, context, world, false);
                IEnergySink sink = insert.endpoint.getSink();
                if (insert.remainingRate <= 0.0D || sink == null
                        || positiveEnergy(sink.getDemandedEnergy()) <= 0.0D) {
                    continue;
                }

                for (int i = firstPotentialExtractor; i < extractors.size(); i++) {
                    ConnectorRuntime extract = extractors.get(i);
                    active = extract;
                    prepare(extract, context, world, true);
                    SourceBudget offeredBudget = extract.sourceBudget;
                    if (extract.remainingRate <= 0.0D || offeredBudget == null || offeredBudget.remaining <= 0.0D) {
                        if (i == firstPotentialExtractor) {
                            firstPotentialExtractor++;
                        }
                        continue;
                    }
                    IEnergySource source = extract.endpoint.getSource();
                    // Never circulate energy through two ports of the same IO delegate.
                    if (source == sink) {
                        continue;
                    }
                    if (!extract.endpoint.isLive() || !extract.endpoint.canExtract()) {
                        extract.remainingRate = 0.0D;
                        if (i == firstPotentialExtractor) {
                            firstPotentialExtractor++;
                        }
                        continue;
                    }
                    active = insert;
                    if (!insert.endpoint.isLive() || !insert.endpoint.canInsert()) {
                        break;
                    }
                    double demanded = positiveEnergy(sink.getDemandedEnergy());
                    if (demanded <= 0.0D) {
                        break;
                    }
                    active = extract;
                    double offered = positiveEnergy(source.getOfferedEnergy());
                    if (offered <= 0.0D) {
                        offeredBudget.remaining = 0.0D;
                        if (i == firstPotentialExtractor) {
                            firstPotentialExtractor++;
                        }
                        continue;
                    }
                    double amount = Math.min(Math.min(extract.remainingRate, offeredBudget.remaining),
                            Math.min(insert.remainingRate, Math.min(offered, demanded)));

                    // Keep EU's cost per extractor, even with inserters outermost.
                    if (!extract.operationPaid) {
                        if (!context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
                            return;
                        }
                        extract.operationPaid = true;
                    }
                    active = insert;
                    // Injection takes travel direction, opposite the selected machine face.
                    // Voltage zero follows Mekanism's untiered direct EU policy.
                    double rejected = sink.injectEnergy(insert.endpoint.getTravelDirection(), amount, 0.0D);
                    if (!Double.isFinite(rejected) || rejected < 0.0D || rejected > amount) {
                        throw new IllegalStateException("IC2 sink returned invalid rejected EU: " + rejected
                                + " for an injection of " + amount);
                    }
                    double accepted = amount - rejected;
                    if (accepted <= 0.0D) {
                        // Do not repeat a wholly rejected insert with each remaining source this tick.
                        break;
                    }

                    active = extract;
                    // IC2 has no extraction simulation or rollback. A throwing draw aborts this pass.
                    source.drawEnergy(accepted);
                    extract.remainingRate = Math.max(0.0D, extract.remainingRate - accepted);
                    offeredBudget.remaining = Math.max(0.0D, offeredBudget.remaining - accepted);
                    insert.remainingRate = Math.max(0.0D, insert.remainingRate - accepted);
                    if (i == firstPotentialExtractor
                            && (extract.remainingRate <= 0.0D || offeredBudget.remaining <= 0.0D)) {
                        firstPotentialExtractor++;
                    }
                    if (insert.remainingRate <= 0.0D) {
                        break;
                    }
                }
            }
        } catch (RuntimeException failure) {
            if (worldTick >= nextWarningTick) {
                nextWarningTick = worldTick + 100;
                LOGGER.warn("EU channel {} stopped this tick at connector {}. An IC2 callback failed; "
                                + "energy already mutated by that callback cannot be rolled back.",
                        channel, active == null ? null : active.consumer, failure);
            }
        } finally {
            // No worlds, tiles, delegates or transport peer locations survive the pass.
            sourceBudgets.clear();
            for (ConnectorRuntime runtime : extractors) {
                runtime.clearTick();
            }
            for (ConnectorRuntime runtime : consumers) {
                runtime.clearTick();
            }
        }
    }

    private void prepare(ConnectorRuntime runtime, IControllerContext context, World world, boolean extracting) {
        if (runtime.prepared) {
            return;
        }
        runtime.prepared = true;
        int rate = getRate(runtime.settings);
        if (rate <= 0) {
            return;
        }
        BlockPos connectorPos = context.findConsumerPosition(runtime.consumer.getConsumerId());
        if (connectorPos == null || !WorldTools.chunkLoaded(world, connectorPos)
                || !runtime.settings.matchesColor(context)) {
            return;
        }
        EnumFacing connectorSide = runtime.consumer.getSide();
        BlockPos targetPos = connectorPos.offset(connectorSide);
        if (!WorldTools.chunkLoaded(world, targetPos) || checkRedstone(world, runtime.settings, connectorPos)) {
            return;
        }
        runtime.endpoint.resolve(world, targetPos, connectorPos,
                runtime.settings.getEffectiveFacing(connectorSide.getOpposite()));
        if (extracting) {
            if (!runtime.endpoint.canExtract()) {
                return;
            }
            IEnergySource source = runtime.endpoint.getSource();
            SourceBudget shared = sourceBudgets.get(source);
            if (shared == null) {
                shared = runtime.ownSourceBudget;
                shared.remaining = positiveEnergy(source.getOfferedEnergy());
                sourceBudgets.put(source, shared);
            }
            runtime.sourceBudget = shared;
        } else if (!runtime.endpoint.canInsert()) {
            return;
        }
        runtime.remainingRate = rate;
    }

    private static double positiveEnergy(double value) {
        if (Double.isNaN(value) || value < 0.0D) {
            throw new IllegalStateException("IC2 callback returned invalid available/demanded EU: " + value);
        }
        // Unbounded advertised demand is still bounded by the configured connector rate.
        return Math.min(value, Double.MAX_VALUE);
    }

    private static int getRate(EUConnectorSettings connector) {
        int maxRate = connector.isAdvanced()
                ? XNetAdditionsConfig.maxEuRateAdvanced : XNetAdditionsConfig.maxEuRateNormal;
        Integer rate = connector.getRate();
        return rate == null ? Math.max(0, maxRate) : Math.max(0, Math.min(rate, maxRate));
    }

    @Nullable
    public static IEnergySource getEnergySourceAt(@Nonnull World world, @Nonnull BlockPos pos) {
        IEnergyTile tile = EUEnergyEndpoint.getIOTile(world, pos);
        return tile instanceof IEnergySource ? (IEnergySource) tile : null;
    }

    @Nullable
    public static IEnergySink getEnergySinkAt(@Nonnull World world, @Nonnull BlockPos pos) {
        IEnergyTile tile = EUEnergyEndpoint.getIOTile(world, pos);
        return tile instanceof IEnergySink ? (IEnergySink) tile : null;
    }

    public static boolean canExtractAt(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull EnumFacing machineFace) {
        return canExtractAt(world, pos, pos.offset(machineFace), machineFace);
    }

    public static boolean canExtractAt(@Nonnull World world, @Nonnull BlockPos pos,
                                       @Nonnull BlockPos connectorPos, @Nonnull EnumFacing machineFace) {
        EUEnergyEndpoint endpoint = new EUEnergyEndpoint();
        try {
            endpoint.resolve(world, pos, connectorPos, machineFace);
            return endpoint.canExtract();
        } finally {
            endpoint.clear();
        }
    }

    public static boolean canInsertAt(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull EnumFacing machineFace) {
        return canInsertAt(world, pos, pos.offset(machineFace), machineFace);
    }

    public static boolean canInsertAt(@Nonnull World world, @Nonnull BlockPos pos,
                                      @Nonnull BlockPos connectorPos, @Nonnull EnumFacing machineFace) {
        EUEnergyEndpoint endpoint = new EUEnergyEndpoint();
        try {
            endpoint.resolve(world, pos, connectorPos, machineFace);
            return endpoint.canInsert();
        } finally {
            endpoint.clear();
        }
    }

    public static boolean isEUTE(@Nonnull World world, @Nonnull BlockPos pos) {
        return EUEnergyEndpoint.getIOTile(world, pos) != null;
    }

    @Override
    public void cleanCache() {
        euExtractors = null;
        euConsumers = null;
    }

    private void updateCache(int channel, IControllerContext context) {
        if (euExtractors != null) {
            return;
        }
        euExtractors = new ArrayList<>();
        euConsumers = new ArrayList<>();
        Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
        for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
            EUConnectorSettings settings = (EUConnectorSettings) entry.getValue();
            ConnectorRuntime runtime = new ConnectorRuntime(entry.getKey(), settings);
            if (settings.getEuMode() == EUConnectorSettings.EUMode.EXT) {
                euExtractors.add(runtime);
            } else {
                euConsumers.add(runtime);
            }
        }
        for (Map.Entry<SidedConsumer, IConnectorSettings> entry : context.getRoutedConnectors(channel).entrySet()) {
            EUConnectorSettings settings = (EUConnectorSettings) entry.getValue();
            if (settings.getEuMode() == EUConnectorSettings.EUMode.INS && !connectors.containsKey(entry.getKey())) {
                euConsumers.add(new ConnectorRuntime(entry.getKey(), settings));
            }
        }
        euExtractors.sort((a, b) -> Integer.compare(b.settings.getPriority(), a.settings.getPriority()));
        euConsumers.sort((a, b) -> Integer.compare(b.settings.getPriority(), a.settings.getPriority()));
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(XNetAdditions.ICON_GUIELEMENTS, 33, 0, 11, 10);
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
}

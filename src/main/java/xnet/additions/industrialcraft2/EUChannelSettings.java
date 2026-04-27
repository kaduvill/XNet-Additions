package xnet.additions.industrialcraft2;

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
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.config.ConfigSetup;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EUChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    private List<Pair<SidedConsumer, EUConnectorSettings>> euExtractors = null;
    private List<Pair<SidedConsumer, EUConnectorSettings>> euConsumers = null;

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
        updateCache(channel, context);

        if (euExtractors == null || euExtractors.isEmpty()) {
            return;
        }

        if (euConsumers == null || euConsumers.isEmpty()) {
            return;
        }

        World world = context.getControllerWorld();

        for (Pair<SidedConsumer, EUConnectorSettings> entry : euExtractors) {
            EUConnectorSettings settings = entry.getValue();

            BlockPos connectorPos = context.findConsumerPosition(entry.getKey().getConsumerId());
            if (connectorPos == null) {
                continue;
            }

            EnumFacing connectorSide = entry.getKey().getSide();
            BlockPos sourcePos = connectorPos.offset(connectorSide);

            if (!WorldTools.chunkLoaded(world, sourcePos)) {
                continue;
            }

            if (checkRedstone(world, settings, connectorPos)) {
                continue;
            }

            if (!context.matchColor(settings.getColorsMask())) {
                continue;
            }

            IEnergySource source = getEnergySourceAt(world, sourcePos);
            if (source == null) {
                continue;
            }

            tickEnergySource(context, settings, connectorPos, source);
        }
    }

    private void tickEnergySource(@Nonnull IControllerContext context,
                                  @Nonnull EUConnectorSettings extractSettings,
                                  @Nonnull BlockPos extractorConnectorPos,
                                  @Nonnull IEnergySource source) {
        World world = context.getControllerWorld();

        int extractRate = getRate(extractSettings, world, extractorConnectorPos);
        if (extractRate <= 0) {
            return;
        }

        double offered = safeGetOfferedEnergy(source);
        if (offered <= 0.0D) {
            return;
        }

        double budget = Math.min(extractRate, offered);
        if (budget <= 0.0D) {
            return;
        }

        transferEU(source, budget, context);
    }

    private void transferEU(@Nonnull IEnergySource source,
                            double budget,
                            @Nonnull IControllerContext context) {
        if (budget <= 0.0D || euConsumers == null || euConsumers.isEmpty()) {
            return;
        }

        World world = context.getControllerWorld();
        boolean consumedControllerPower = false;

        for (Pair<SidedConsumer, EUConnectorSettings> entry : euConsumers) {
            if (budget <= 0.0D) {
                return;
            }

            EUConnectorSettings insertSettings = entry.getValue();

            BlockPos consumerConnectorPos = context.findConsumerPosition(entry.getKey().getConsumerId());
            if (consumerConnectorPos == null) {
                continue;
            }

            if (checkRedstone(world, insertSettings, consumerConnectorPos)) {
                continue;
            }

            if (!context.matchColor(insertSettings.getColorsMask())) {
                continue;
            }

            EnumFacing connectorSide = entry.getKey().getSide();
            BlockPos sinkPos = consumerConnectorPos.offset(connectorSide);
            EnumFacing sinkSide = connectorSide.getOpposite();

            if (!WorldTools.chunkLoaded(world, sinkPos)) {
                continue;
            }

            IEnergySink sink = getEnergySinkAt(world, sinkPos);
            if (sink == null) {
                continue;
            }

            double planned = getPlannedTransfer(source, sink, insertSettings, world, consumerConnectorPos, budget);
            if (planned <= 0.0D) {
                continue;
            }

            if (!consumedControllerPower) {
                if (!context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
                    return;
                }
                consumedControllerPower = true;
            }

            double moved = injectThenDraw(source, sink, sinkSide, planned);

            if (moved > 0.0D) {
                budget -= moved;
            }
        }
    }

    private static double getPlannedTransfer(@Nonnull IEnergySource source,
                                             @Nonnull IEnergySink sink,
                                             @Nonnull EUConnectorSettings insertSettings,
                                             @Nonnull World world,
                                             @Nonnull BlockPos insertConnectorPos,
                                             double remainingBudget) {
        int insertRate = getRate(insertSettings, world, insertConnectorPos);
        if (insertRate <= 0) {
            return 0.0D;
        }

        double offered = safeGetOfferedEnergy(source);
        double demanded = safeGetDemandedEnergy(sink);

        if (offered <= 0.0D || demanded <= 0.0D) {
            return 0.0D;
        }

        double voltage = getSafeVoltage(source, sink);
        if (voltage <= 0.0D) {
            return 0.0D;
        }

        double planned = remainingBudget;
        planned = Math.min(planned, insertRate);
        planned = Math.min(planned, offered);
        planned = Math.min(planned, demanded);

        /*
         * Total planned transfer is EU/t throughput.
         * injectThenDraw() splits it into safe voltage-sized IC2 packets.
         */
        return clampFinitePositive(planned);
    }

    private static double injectThenDraw(@Nonnull IEnergySource source,
                                         @Nonnull IEnergySink sink,
                                         @Nonnull EnumFacing directionFrom,
                                         double amount) {
        amount = clampFinitePositive(amount);
        if (amount <= 0.0D) {
            return 0.0D;
        }

        double voltage = getSafeVoltage(source, sink);
        if (voltage <= 0.0D) {
            return 0.0D;
        }

        double remaining = amount;
        double movedTotal = 0.0D;

        int packets = 0;
        int maxPackets = 1024;

        while (remaining > 0.0D && packets < maxPackets) {
            double offered = safeGetOfferedEnergy(source);
            double demanded = safeGetDemandedEnergy(sink);

            if (offered <= 0.0D || demanded <= 0.0D) {
                break;
            }

            double packet = Math.min(remaining, voltage);
            packet = Math.min(packet, offered);
            packet = Math.min(packet, demanded);

            if (packet <= 0.0D) {
                break;
            }

            double rejected;

            try {
                rejected = sink.injectEnergy(directionFrom, packet, voltage);
            } catch (RuntimeException e) {
                break;
            }

            if (Double.isNaN(rejected) || rejected < 0.0D) {
                rejected = 0.0D;
            }

            if (Double.isInfinite(rejected) || rejected > packet) {
                rejected = packet;
            }

            double accepted = packet - rejected;
            if (accepted <= 0.0D) {
                break;
            }

            /*
             * Draw only after accepted insert.
             * Do not swallow drawEnergy exceptions here, because doing so could duplicate EU.
             */
            source.drawEnergy(accepted);

            movedTotal += accepted;
            remaining -= accepted;
            packets++;

            if (rejected > 0.0D) {
                break;
            }
        }

        return movedTotal;
    }

    private static double safeGetOfferedEnergy(@Nonnull IEnergySource source) {
        try {
            return clampFinitePositive(source.getOfferedEnergy());
        } catch (RuntimeException e) {
            return 0.0D;
        }
    }

    private static double safeGetDemandedEnergy(@Nonnull IEnergySink sink) {
        try {
            return clampFinitePositive(sink.getDemandedEnergy());
        } catch (RuntimeException e) {
            return 0.0D;
        }
    }

    private static int safeGetSourceTier(@Nonnull IEnergySource source) {
        try {
            return Math.max(0, source.getSourceTier());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int safeGetSinkTier(@Nonnull IEnergySink sink) {
        try {
            return Math.max(0, sink.getSinkTier());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double getSafeVoltage(@Nonnull IEnergySource source, @Nonnull IEnergySink sink) {
        int tier = Math.min(safeGetSourceTier(source), safeGetSinkTier(sink));
        return getVoltageForTier(tier);
    }

    private static double getVoltageForTier(int tier) {
        switch (tier) {
            case 0:
            case 1:
                return 32.0D;
            case 2:
                return 128.0D;
            case 3:
                return 512.0D;
            case 4:
                return 2048.0D;
            case 5:
                return 8192.0D;
            case 6:
                return 32768.0D;
            default:
                return 131072.0D;
        }
    }

    private static double clampFinitePositive(double value) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return 0.0D;
        }

        if (Double.isInfinite(value)) {
            return Double.MAX_VALUE;
        }

        return value;
    }

    private static int getRate(EUConnectorSettings connector, World world, BlockPos pos) {
        int maxRate = ConnectorBlock.isAdvancedConnector(world, pos)
                ? XNetAdditionsConfig.maxEuRateAdvanced
                : XNetAdditionsConfig.maxEuRateNormal;

        Integer rate = connector.getRate();
        if (rate != null) {
            return Math.max(0, Math.min(rate, maxRate));
        }

        return Math.max(0, maxRate);
    }

    @Nullable
    public static IEnergySource getEnergySourceAt(@Nonnull World world, @Nonnull BlockPos pos) {
        IEnergyTile energyTile = getEnergyTileAt(world, pos);

        if (energyTile instanceof IEnergySource) {
            return (IEnergySource) energyTile;
        }

        TileEntity te = world.getTileEntity(pos);
        if (te instanceof IEnergySource) {
            return (IEnergySource) te;
        }

        return null;
    }

    @Nullable
    public static IEnergySink getEnergySinkAt(@Nonnull World world, @Nonnull BlockPos pos) {
        IEnergyTile energyTile = getEnergyTileAt(world, pos);

        if (energyTile instanceof IEnergySink) {
            return (IEnergySink) energyTile;
        }

        TileEntity te = world.getTileEntity(pos);
        if (te instanceof IEnergySink) {
            return (IEnergySink) te;
        }

        return null;
    }

    public static boolean isEUTE(@Nonnull World world, @Nonnull BlockPos pos) {
        IEnergyTile energyTile = getEnergyTileAt(world, pos);

        if (energyTile instanceof IEnergySource || energyTile instanceof IEnergySink) {
            return true;
        }

        TileEntity te = world.getTileEntity(pos);
        return te instanceof IEnergySource || te instanceof IEnergySink || te instanceof IEnergyTile;
    }

    @Nullable
    private static IEnergyTile getEnergyTileAt(@Nonnull World world, @Nonnull BlockPos pos) {
        IEnergyTile fromEnergyNet = getEnergyTileFromEnergyNet(world, pos);
        if (fromEnergyNet != null) {
            return fromEnergyNet;
        }

        TileEntity te = world.getTileEntity(pos);
        if (te instanceof IEnergyTile) {
            return (IEnergyTile) te;
        }

        return null;
    }

    @Nullable
    private static IEnergyTile getEnergyTileFromEnergyNet(@Nonnull World world, @Nonnull BlockPos pos) {
        try {
            Class<?> energyNetClass = Class.forName("ic2.api.energy.EnergyNet");
            Field instanceField = energyNetClass.getField("instance");
            Object energyNet = instanceField.get(null);

            if (energyNet == null) {
                return null;
            }

            IEnergyTile result;

            result = tryEnergyNetMethod(energyNet, "getTile", World.class, BlockPos.class, world, pos);
            if (result != null) {
                return result;
            }

            result = tryEnergyNetMethod(energyNet, "getSubTile", World.class, BlockPos.class, world, pos);
            if (result != null) {
                return result;
            }

            TileEntity te = world.getTileEntity(pos);
            if (te != null) {
                result = tryEnergyNetMethod(energyNet, "getTile", TileEntity.class, te);
                if (result != null) {
                    return result;
                }

                result = tryEnergyNetMethod(energyNet, "getSubTile", TileEntity.class, te);
                if (result != null) {
                    return result;
                }
            }

            result = tryEnergyNetMethod(
                    energyNet,
                    "getTile",
                    World.class,
                    int.class,
                    int.class,
                    int.class,
                    world,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
            if (result != null) {
                return result;
            }

            result = tryEnergyNetMethod(
                    energyNet,
                    "getSubTile",
                    World.class,
                    int.class,
                    int.class,
                    int.class,
                    world,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
            if (result != null) {
                return result;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    @Nullable
    private static IEnergyTile tryEnergyNetMethod(@Nonnull Object energyNet,
                                                  @Nonnull String methodName,
                                                  Class<?> arg0,
                                                  Object value0) {
        return tryEnergyNetMethodInternal(energyNet, methodName, new Class<?>[]{arg0}, new Object[]{value0});
    }

    @Nullable
    private static IEnergyTile tryEnergyNetMethod(@Nonnull Object energyNet,
                                                  @Nonnull String methodName,
                                                  Class<?> arg0,
                                                  Class<?> arg1,
                                                  Object value0,
                                                  Object value1) {
        return tryEnergyNetMethodInternal(energyNet, methodName, new Class<?>[]{arg0, arg1}, new Object[]{value0, value1});
    }

    @Nullable
    private static IEnergyTile tryEnergyNetMethod(@Nonnull Object energyNet,
                                                  @Nonnull String methodName,
                                                  Class<?> arg0,
                                                  Class<?> arg1,
                                                  Class<?> arg2,
                                                  Class<?> arg3,
                                                  Object value0,
                                                  Object value1,
                                                  Object value2,
                                                  Object value3) {
        return tryEnergyNetMethodInternal(
                energyNet,
                methodName,
                new Class<?>[]{arg0, arg1, arg2, arg3},
                new Object[]{value0, value1, value2, value3}
        );
    }

    @Nullable
    private static IEnergyTile tryEnergyNetMethodInternal(@Nonnull Object energyNet,
                                                          @Nonnull String methodName,
                                                          Class<?>[] argTypes,
                                                          Object[] args) {
        try {
            Method method = findMethod(energyNet.getClass(), methodName, argTypes);
            if (method == null) {
                return null;
            }

            method.setAccessible(true);
            Object raw = method.invoke(energyNet, args);
            return unwrapEnergyTile(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name, Class<?>... argTypes) {
        Class<?> current = type;

        while (current != null) {
            try {
                return current.getDeclaredMethod(name, argTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }

        try {
            return type.getMethod(name, argTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static IEnergyTile unwrapEnergyTile(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof IEnergyTile) {
            return (IEnergyTile) raw;
        }

        IEnergyTile fromMethod;

        fromMethod = unwrapEnergyTileMethod(raw, "getMainTile");
        if (fromMethod != null) {
            return fromMethod;
        }

        fromMethod = unwrapEnergyTileMethod(raw, "getSubTile");
        if (fromMethod != null) {
            return fromMethod;
        }

        fromMethod = unwrapEnergyTileMethod(raw, "getEnergyTile");
        if (fromMethod != null) {
            return fromMethod;
        }

        fromMethod = unwrapEnergyTileMethod(raw, "getTile");
        if (fromMethod != null) {
            return fromMethod;
        }

        IEnergyTile fromField;

        fromField = unwrapEnergyTileField(raw, "mainTile");
        if (fromField != null) {
            return fromField;
        }

        fromField = unwrapEnergyTileField(raw, "subTile");
        if (fromField != null) {
            return fromField;
        }

        fromField = unwrapEnergyTileField(raw, "energyTile");
        if (fromField != null) {
            return fromField;
        }

        fromField = unwrapEnergyTileField(raw, "tile");
        if (fromField != null) {
            return fromField;
        }

        return null;
    }

    @Nullable
    private static IEnergyTile unwrapEnergyTileMethod(@Nonnull Object raw, @Nonnull String methodName) {
        try {
            Method method = findMethod(raw.getClass(), methodName);
            if (method == null) {
                return null;
            }

            method.setAccessible(true);
            Object value = method.invoke(raw);

            if (value instanceof IEnergyTile) {
                return (IEnergyTile) value;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    @Nullable
    private static IEnergyTile unwrapEnergyTileField(@Nonnull Object raw, @Nonnull String fieldName) {
        try {
            Field field = findField(raw.getClass(), fieldName);
            if (field == null) {
                return null;
            }

            field.setAccessible(true);
            Object value = field.get(raw);

            if (value instanceof IEnergyTile) {
                return (IEnergyTile) value;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    @Nullable
    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;

        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
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
            EUConnectorSettings con = (EUConnectorSettings) entry.getValue();

            if (con.getEuMode() == EUConnectorSettings.EUMode.EXT) {
                euExtractors.add(Pair.of(entry.getKey(), con));
            } else {
                euConsumers.add(Pair.of(entry.getKey(), con));
            }
        }

        connectors = context.getRoutedConnectors(channel);
        for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
            EUConnectorSettings con = (EUConnectorSettings) entry.getValue();

            if (con.getEuMode() == EUConnectorSettings.EUMode.INS) {
                euConsumers.add(Pair.of(entry.getKey(), con));
            }
        }

        euExtractors.sort((o1, o2) -> Integer.compare(o2.getRight().getPriority(), o1.getRight().getPriority()));
        euConsumers.sort((o1, o2) -> Integer.compare(o2.getRight().getPriority(), o1.getRight().getPriority()));
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
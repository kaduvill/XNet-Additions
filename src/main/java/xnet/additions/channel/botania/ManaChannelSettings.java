package xnet.additions.channel.botania;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.commons.lang3.tuple.Pair;
import vazkii.botania.api.mana.IManaCollector;
import vazkii.botania.api.mana.IManaReceiver;
import vazkii.botania.api.mana.spark.ISparkAttachable;
import vazkii.botania.api.subtile.SubTileEntity;
import vazkii.botania.api.subtile.SubTileFunctional;
import vazkii.botania.api.subtile.SubTileGenerating;
import vazkii.botania.common.block.tile.TileSpecialFlower;
import vazkii.botania.common.block.tile.TileBrewery;
import vazkii.botania.common.block.tile.TileRuneAltar;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

public class ManaChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final ResourceLocation iconGuiElements =
            new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";

    public enum ChannelMode {
        PRIORITY,
        ROUNDROBIN,
        DISTRIBUTE
    }

    private ChannelMode channelMode = ChannelMode.PRIORITY;
    private int delay = 0;
    private int roundRobinOffset = 0;

    private Map<SidedConsumer, ManaConnectorSettings> manaExtractors = null;
    private List<Pair<SidedConsumer, ManaConnectorSettings>> manaConsumers = null;

    public ChannelMode getChannelMode() {
        return channelMode;
    }
    private static int getExtractAmount(ManaConnectorSettings settings) {
        if (settings.getAmountMode() == ManaConnectorSettings.AmountMode.HIGHEST) {
            return settings.isAdvanced()
                    ? XNetAdditionsConfig.maxManaRateAdvanced
                    : XNetAdditionsConfig.maxManaRateNormal;
        }
        return getRate(settings);
    }
    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();
        object.add(TAG_MODE, new JsonPrimitive(channelMode.name()));
        return object;
    }

    @Override
    public void readFromJson(JsonObject data) {
        channelMode = ChannelMode.PRIORITY;
        if (data != null && data.has(TAG_MODE) && !data.get(TAG_MODE).isJsonNull()) {
            try {
                channelMode = ChannelMode.valueOf(data.get(TAG_MODE).getAsString().toUpperCase());
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        int mode = tag.getByte(TAG_MODE);
        channelMode = mode >= 0 && mode < ChannelMode.values().length
                ? ChannelMode.values()[mode]
                : ChannelMode.PRIORITY;
        delay = tag.getInteger("delay");
        roundRobinOffset = tag.getInteger("offset");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setByte(TAG_MODE, (byte) channelMode.ordinal());
        tag.setInteger("delay", delay);
        tag.setInteger("offset", roundRobinOffset);
    }

    private static int getRate(ManaConnectorSettings settings) {
        int maxRate = settings.isAdvanced()
                ? XNetAdditionsConfig.maxManaRateAdvanced
                : XNetAdditionsConfig.maxManaRateNormal;
        Integer rate = settings.getRate();
        return rate == null ? maxRate : Math.max(0, Math.min(rate, maxRate));
    }

    @Override
    public void tick(int channel, IControllerContext context) {
        delay--;
        if (delay <= 0) {
            delay = 200 * 6;
        }
        if (delay % 10 != 0) {
            return;
        }

        int d = delay / 10;
        updateCache(channel, context);
        World world = context.getControllerWorld();
        for (Map.Entry<SidedConsumer, ManaConnectorSettings> entry : manaExtractors.entrySet()) {
            ManaConnectorSettings settings = entry.getValue();

            if (d % settings.getSpeed() != 0) {
                continue;
            }

            BlockPos extractorPos = context.findConsumerPosition(entry.getKey().getConsumerId());
            if (extractorPos == null) {
                continue;
            }

            EnumFacing side = entry.getKey().getSide();
            BlockPos pos = extractorPos.offset(side);

            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }
            if (checkRedstone(world, settings, extractorPos)) {
                continue;
            }
            if (!settings.matchesColor(context)) {
                continue;
            }

            ManaNode node = getManaNode(world.getTileEntity(pos), settings.getFacing());
            if (node == null || !node.canExtract()) {
                continue;
            }

            int toExtract = Math.min(getExtractAmount(settings), node.getCurrentMana());
            Integer count = settings.getMinmax();
            if (count != null) {
                int canExtract = node.getCurrentMana() - count;
                if (canExtract <= 0) {
                    continue;
                }
                toExtract = Math.min(toExtract, canExtract);
            }

            if (toExtract <= 0) {
                continue;
            }
            transferMana(node, context, toExtract);
        }
    }

    private void transferMana(@Nonnull ManaNode from,
                              @Nonnull IControllerContext context,
                              int amount) {
        if (manaConsumers == null || manaConsumers.isEmpty() || amount <= 0) {
            return;
        }

        if (channelMode == ChannelMode.DISTRIBUTE) {
            transferManaDistribute(from, context, amount);
            return;
        }

        if (channelMode == ChannelMode.PRIORITY) {
            roundRobinOffset = 0;
        }

        World world = context.getControllerWorld();
        int size = manaConsumers.size();
        int start = Math.floorMod(roundRobinOffset, size);
        int remaining = amount;
        boolean consumedControllerPower = false;

        for (int j = 0; j < size; j++) {
            int i = (start + j) % size;
            Pair<SidedConsumer, ManaConnectorSettings> entry = manaConsumers.get(i);
            ManaConnectorSettings settings = entry.getValue();

            BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
            if (consumerPos == null || checkRedstone(world, settings, consumerPos) || !settings.matchesColor(context)) {
                continue;
            }

            BlockPos pos = consumerPos.offset(entry.getKey().getSide());
            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }

            ManaNode to = getManaNode(world.getTileEntity(pos), settings.getFacing());
            if (to == null || !to.canInsert()) {
                continue;
            }

            int moved = Math.min(getRate(settings), remaining);

            Integer maximum = settings.getMinmax();
            if (maximum != null) {
                int canInsert = maximum - to.getCurrentMana();
                if (canInsert <= 0) {
                    continue;
                }
                moved = Math.min(moved, canInsert);
            }

            moved = Math.min(moved, to.getAvailableSpace());
            moved = Math.min(moved, from.getCurrentMana());
            if (moved <= 0) {
                continue;
            }

            if (!consumedControllerPower) {
                if (!context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
                    return;
                }
                consumedControllerPower = true;
            }

            from.extract(moved);
            to.insert(moved);
            remaining -= moved;

            if (channelMode == ChannelMode.ROUNDROBIN) {
                roundRobinOffset = (i + 1) % size;
                return;
            }

            if (remaining <= 0) {
                return;
            }
        }
    }
    private void transferManaDistribute(@Nonnull ManaNode from,
                                        @Nonnull IControllerContext context,
                                        int amount) {
        Map<Pair<SidedConsumer, ManaConnectorSettings>, Integer> distribution = new LinkedHashMap<>();
        int planned = getManaDistribution(distribution, context, amount);
        if (planned <= 0 || !context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
            return;
        }

        World world = context.getControllerWorld();
        int remaining = amount;

        for (Map.Entry<Pair<SidedConsumer, ManaConnectorSettings>, Integer> plannedEntry : distribution.entrySet()) {
            int desired = Math.min(plannedEntry.getValue(), remaining);
            if (desired <= 0) {
                continue;
            }

            Pair<SidedConsumer, ManaConnectorSettings> entry = plannedEntry.getKey();
            ManaConnectorSettings settings = entry.getValue();
            BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());

            if (consumerPos == null || checkRedstone(world, settings, consumerPos) || !settings.matchesColor(context)) {
                continue;
            }

            BlockPos pos = consumerPos.offset(entry.getKey().getSide());
            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }

            ManaNode to = getManaNode(world.getTileEntity(pos), settings.getFacing());
            if (to == null || !to.canInsert()) {
                continue;
            }

            int moved = Math.min(desired, getRate(settings));

            Integer maximum = settings.getMinmax();
            if (maximum != null) {
                int canInsert = maximum - to.getCurrentMana();
                if (canInsert <= 0) {
                    continue;
                }
                moved = Math.min(moved, canInsert);
            }

            moved = Math.min(moved, to.getAvailableSpace());
            moved = Math.min(moved, from.getCurrentMana());
            if (moved <= 0) {
                continue;
            }

            from.extract(moved);
            to.insert(moved);
            remaining -= moved;

            if (remaining <= 0) {
                return;
            }
        }
    }
    private int getManaDistribution(@Nonnull Map<Pair<SidedConsumer, ManaConnectorSettings>, Integer> distribution,
                                    @Nonnull IControllerContext context,
                                    int amount) {
        World world = context.getControllerWorld();
        long possibleOverall = 0L;

        for (Pair<SidedConsumer, ManaConnectorSettings> entry : manaConsumers) {
            ManaConnectorSettings settings = entry.getValue();
            BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());

            if (consumerPos == null || checkRedstone(world, settings, consumerPos) || !settings.matchesColor(context)) {
                continue;
            }

            BlockPos pos = consumerPos.offset(entry.getKey().getSide());
            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }

            ManaNode to = getManaNode(world.getTileEntity(pos), settings.getFacing());
            if (to == null || !to.canInsert()) {
                continue;
            }

            int possible = Math.min(getRate(settings), amount);

            Integer maximum = settings.getMinmax();
            if (maximum != null) {
                int canInsert = maximum - to.getCurrentMana();
                if (canInsert <= 0) {
                    continue;
                }
                possible = Math.min(possible, canInsert);
            }

            possible = Math.min(possible, to.getAvailableSpace());
            if (possible <= 0) {
                continue;
            }

            distribution.put(entry, possible);
            possibleOverall += possible;
        }

        if (possibleOverall <= 0L) {
            return 0;
        }

        int planned = 0;

        for (Map.Entry<Pair<SidedConsumer, ManaConnectorSettings>, Integer> entry : distribution.entrySet()) {
            int possible = entry.getValue();
            int share = planned >= amount
                    ? 0
                    : (int) Math.ceil(amount * (possible / (double) possibleOverall));

            share = Math.min(share, possible);
            share = Math.min(share, amount - planned);
            entry.setValue(share);
            planned += share;
        }

        return planned;
    }
    private void updateCache(int channel, IControllerContext context) {
        if (manaExtractors == null) {
            manaExtractors = new HashMap<>();
            manaConsumers = new ArrayList<>();

            Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                ManaConnectorSettings con = (ManaConnectorSettings) entry.getValue();
                if (con.getManaMode() == ManaConnectorSettings.ManaMode.EXT) {
                    manaExtractors.put(entry.getKey(), con);
                } else {
                    manaConsumers.add(Pair.of(entry.getKey(), con));
                }
            }
            Map<SidedConsumer, IConnectorSettings> routedConnectors = context.getRoutedConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : routedConnectors.entrySet()) {
                ManaConnectorSettings con = (ManaConnectorSettings) entry.getValue();
                if (con.getManaMode() == ManaConnectorSettings.ManaMode.INS && !connectors.containsKey(entry.getKey())) {
                    manaConsumers.add(Pair.of(entry.getKey(), con));
                }
            }

            manaConsumers.sort((o1, o2) -> o2.getRight().getPriority().compareTo(o1.getRight().getPriority()));
        }
    }

    @Override
    public void cleanCache() {
        manaExtractors = null;
        manaConsumers = null;
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(XNetAdditions.ICON_GUIELEMENTS, 11, 0, 11, 10);
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        gui.nl().choices(TAG_MODE, "Mana distribution mode", channelMode, ChannelMode.values());
    }

    @Override
    public void update(Map<String, Object> data) {
        channelMode = ChannelMode.PRIORITY;
        Object mode = data.get(TAG_MODE);
        if (mode instanceof String) {
            try {
                channelMode = ChannelMode.valueOf(((String) mode).toUpperCase());
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public int getColors() {
        return 0;
    }

    public interface ManaNode {
        int getCurrentMana();
        int getAvailableSpace();
        boolean canInsert();
        boolean canExtract();
        void insert(int amount);
        void extract(int amount);
    }

    @Nullable
    public static ManaNode getManaNode(@Nullable TileEntity te, @Nullable EnumFacing side) {
        if (te == null) {
            return null;
        }

        if (te instanceof TileRuneAltar) {
            TileRuneAltar altar = (TileRuneAltar) te;
            return new ReceiverBackedNode<>(altar, () -> altar.getTargetMana() - altar.getCurrentMana());
        }

        if (te instanceof TileBrewery) {
            TileBrewery brewery = (TileBrewery) te;
            return new ReceiverBackedNode<>(brewery, () -> brewery.getManaCost() - brewery.getCurrentMana());
        }

        if (te instanceof TileSpecialFlower) {
            TileSpecialFlower flower = (TileSpecialFlower) te;
            SubTileEntity subTile = flower.getSubTile();
            if (subTile instanceof SubTileGenerating || subTile instanceof SubTileFunctional) {
                return new FlowerNode(subTile);
            }
        }

        if (te instanceof IManaCollector) {
            IManaCollector collector = (IManaCollector) te;
            return new ReceiverBackedNode<>(collector, () -> collector.getMaxMana() - collector.getCurrentMana());
        }

        if (te instanceof ISparkAttachable) {
            ISparkAttachable attachable = (ISparkAttachable) te;
            return new ReceiverBackedNode<>(attachable, attachable::getAvailableSpaceForMana);
        }

        return null;
    }

    private static class FlowerNode implements ManaNode {
        private static final Field GENERATING_MANA_FIELD =
                ReflectionHelper.findField(SubTileGenerating.class, "mana");
        private static final Field FUNCTIONAL_MANA_FIELD =
                ReflectionHelper.findField(SubTileFunctional.class, "mana");

        private final SubTileEntity subTile;

        private FlowerNode(@Nonnull SubTileEntity subTile) {
            this.subTile = subTile;
        }

        @Override
        public int getCurrentMana() {
            return Math.max(0, readMana(subTile));
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, getMaxMana(subTile) - getCurrentMana());
        }

        @Override
        public boolean canInsert() {
            return getAvailableSpace() > 0;
        }

        @Override
        public boolean canExtract() {
            return getCurrentMana() > 0;
        }

        @Override
        public void insert(int amount) {
            if (amount <= 0) {
                return;
            }

            int current = getCurrentMana();
            int max = getMaxMana(subTile);
            int target = Math.min(max, current + amount);
            writeMana(subTile, target);
        }

        @Override
        public void extract(int amount) {
            if (amount <= 0) {
                return;
            }

            int current = getCurrentMana();
            int target = Math.max(0, current - amount);
            writeMana(subTile, target);
        }

        private static int getMaxMana(@Nonnull SubTileEntity subTile) {
            if (subTile instanceof SubTileGenerating) {
                return Math.max(0, ((SubTileGenerating) subTile).getMaxMana());
            }
            if (subTile instanceof SubTileFunctional) {
                return Math.max(0, ((SubTileFunctional) subTile).getMaxMana());
            }
            return 0;
        }

        private static int readMana(@Nonnull SubTileEntity subTile) {
            try {
                if (subTile instanceof SubTileGenerating) {
                    return GENERATING_MANA_FIELD.getInt(subTile);
                }
                if (subTile instanceof SubTileFunctional) {
                    return FUNCTIONAL_MANA_FIELD.getInt(subTile);
                }
            } catch (IllegalAccessException e) {
                return 0;
            }
            return 0;
        }

        private static void writeMana(@Nonnull SubTileEntity subTile, int mana) {
            try {
                if (subTile instanceof SubTileGenerating) {
                    GENERATING_MANA_FIELD.setInt(subTile, mana);
                } else if (subTile instanceof SubTileFunctional) {
                    FUNCTIONAL_MANA_FIELD.setInt(subTile, mana);
                }
            } catch (IllegalAccessException e) {
                // ignore
            }
        }
    }

    private static class ReceiverBackedNode<T extends IManaReceiver> implements ManaNode {
        private final T receiver;
        private final IntSupplier availableSpaceSupplier;

        private ReceiverBackedNode(@Nonnull T receiver, @Nonnull IntSupplier availableSpaceSupplier) {
            this.receiver = receiver;
            this.availableSpaceSupplier = availableSpaceSupplier;
        }

        @Override
        public int getCurrentMana() {
            return receiver.getCurrentMana();
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, availableSpaceSupplier.getAsInt());
        }

        @Override
        public boolean canInsert() {
            return receiver.canRecieveManaFromBursts() && !receiver.isFull();
        }

        @Override
        public boolean canExtract() {
            return receiver.getCurrentMana() > 0;
        }

        @Override
        public void insert(int amount) {
            if (amount > 0) {
                receiver.recieveMana(amount);
            }
        }

        @Override
        public void extract(int amount) {
            int extracted = Math.min(amount, receiver.getCurrentMana());
            if (extracted > 0) {
                receiver.recieveMana(-extracted);
            }
        }
    }
}
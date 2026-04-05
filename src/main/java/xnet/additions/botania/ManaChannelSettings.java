package xnet.additions.botania;

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
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.config.ConfigSetup;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;
import vazkii.botania.api.mana.IManaCollector;
import vazkii.botania.api.mana.IManaPool;
import vazkii.botania.api.mana.IManaReceiver;
import vazkii.botania.api.mana.spark.ISparkAttachable;
import vazkii.botania.common.block.tile.TileRuneAltar;
import vazkii.botania.common.block.tile.TileTerraPlate;
import vazkii.botania.common.block.tile.mana.TilePool;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ManaChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final ResourceLocation iconGuiElements =
            new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";

    public enum ChannelMode {
        PRIORITY,
        ROUNDROBIN
    }

    private ChannelMode channelMode = ChannelMode.PRIORITY;
    private int delay = 0;
    private int roundRobinOffset = 0;

    private Map<SidedConsumer, ManaConnectorSettings> manaExtractors = null;
    private List<Pair<SidedConsumer, ManaConnectorSettings>> manaConsumers = null;

    public ChannelMode getChannelMode() {
        return channelMode;
    }

    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();
        object.add("mode", new JsonPrimitive(channelMode.name()));
        return object;
    }

    private int getRate(ManaConnectorSettings settings, World world, BlockPos pos) {
        Integer rate = settings.getRate();
        if (rate != null) {
            return Math.max(0, rate);
        }

        return ConnectorBlock.isAdvancedConnector(world, pos)
                ? XNetAdditionsConfig.maxManaRateAdvanced
                : XNetAdditionsConfig.maxManaRateNormal;
    }

    @Override
    public void readFromJson(JsonObject data) {
        if (data.has("mode")) {
            channelMode = ChannelMode.valueOf(data.get("mode").getAsString().toUpperCase());
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        channelMode = ChannelMode.values()[tag.getByte("mode")];
        delay = tag.getInteger("delay");
        roundRobinOffset = tag.getInteger("offset");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setByte("mode", (byte) channelMode.ordinal());
        tag.setInteger("delay", delay);
        tag.setInteger("offset", roundRobinOffset);
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

        extractorsLoop:
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
            if (!context.matchColor(settings.getColorsMask())) {
                continue;
            }

            ManaNode node = getManaNode(world.getTileEntity(pos), settings.getFacing());
            if (node == null || !node.canExtract()) {
                continue;
            }

            int toExtract = Math.min(getRate(settings, world, extractorPos), node.getCurrentMana());
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

            List<Pair<SidedConsumer, ManaConnectorSettings>> inserted = new ArrayList<>();
            int remaining = insertManaSimulate(inserted, context, toExtract);
            int accepted = toExtract - remaining;

            if (inserted.isEmpty() || accepted <= 0) {
                continue;
            }

            if (context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
                node.extract(accepted);
                insertManaReal(context, inserted, accepted);
            } else {
                continue extractorsLoop;
            }
        }
    }

    private int insertManaSimulate(@Nonnull List<Pair<SidedConsumer, ManaConnectorSettings>> inserted,
                                   @Nonnull IControllerContext context,
                                   int amount) {
        World world = context.getControllerWorld();

        if (channelMode == ChannelMode.PRIORITY) {
            roundRobinOffset = 0;
        }

        int remaining = amount;

        for (int j = 0; j < manaConsumers.size(); j++) {
            int i = (j + roundRobinOffset) % manaConsumers.size();
            Pair<SidedConsumer, ManaConnectorSettings> entry = manaConsumers.get(i);
            ManaConnectorSettings settings = entry.getValue();

            BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
            if (consumerPos == null) {
                continue;
            }
            if (!WorldTools.chunkLoaded(world, consumerPos)) {
                continue;
            }
            if (checkRedstone(world, settings, consumerPos)) {
                continue;
            }
            if (!context.matchColor(settings.getColorsMask())) {
                continue;
            }

            EnumFacing side = entry.getKey().getSide();
            BlockPos pos = consumerPos.offset(side);
            ManaNode node = getManaNode(world.getTileEntity(pos), settings.getFacing());
            if (node == null || !node.canInsert()) {
                continue;
            }

            int toInsert = Math.min(getRate(settings, world, consumerPos), remaining);

            Integer count = settings.getMinmax();
            if (count != null) {
                int canInsert = count - node.getCurrentMana();
                if (canInsert <= 0) {
                    continue;
                }
                toInsert = Math.min(toInsert, canInsert);
            }

            int filled = Math.min(toInsert, node.getAvailableSpace());
            if (filled > 0) {
                inserted.add(entry);
                remaining -= filled;
                if (remaining <= 0) {
                    return 0;
                }
            }
        }

        return remaining;
    }

    private void insertManaReal(@Nonnull IControllerContext context,
                                @Nonnull List<Pair<SidedConsumer, ManaConnectorSettings>> inserted,
                                int amount) {
        for (Pair<SidedConsumer, ManaConnectorSettings> pair : inserted) {
            BlockPos consumerPos = context.findConsumerPosition(pair.getKey().getConsumerId());
            if (consumerPos == null) {
                continue;
            }

            EnumFacing side = pair.getKey().getSide();
            ManaConnectorSettings settings = pair.getValue();
            BlockPos pos = consumerPos.offset(side);

            ManaNode node = getManaNode(context.getControllerWorld().getTileEntity(pos), settings.getFacing());
            if (node == null || !node.canInsert()) {
                continue;
            }

            int toInsert = Math.min(getRate(settings, context.getControllerWorld(), consumerPos), amount);

            Integer count = settings.getMinmax();
            if (count != null) {
                int canInsert = count - node.getCurrentMana();
                if (canInsert <= 0) {
                    continue;
                }
                toInsert = Math.min(toInsert, canInsert);
            }

            int filled = Math.min(toInsert, node.getAvailableSpace());
            if (filled > 0) {
                node.insert(filled);
                roundRobinOffset = (roundRobinOffset + 1) % manaConsumers.size();
                amount -= filled;
                if (amount <= 0) {
                    return;
                }
            }
        }
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

            connectors = context.getRoutedConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                ManaConnectorSettings con = (ManaConnectorSettings) entry.getValue();
                if (con.getManaMode() == ManaConnectorSettings.ManaMode.INS) {
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
        channelMode = ChannelMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
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

        // Specific exact-capacity nodes first
        if (te instanceof TilePool) {
            return new PoolNode((TilePool) te);
        }
        if (te instanceof TileTerraPlate) {
            return new TerraPlateNode((TileTerraPlate) te);
        }
        if (te instanceof TileRuneAltar) {
            return new RuneAltarNode((TileRuneAltar) te);
        }

        // Generic nodes after that
        if (te instanceof IManaCollector) {
            return new CollectorNode((IManaCollector) te);
        }
        if (te instanceof ISparkAttachable) {
            return new SparkAttachableNode((ISparkAttachable) te);
        }

        return null;
    }

    private static class PoolNode implements ManaNode {
        private final TilePool pool;

        private PoolNode(TilePool pool) {
            this.pool = pool;
        }

        @Override
        public int getCurrentMana() {
            return pool.getCurrentMana();
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, pool.getAvailableSpaceForMana());
        }

        @Override
        public boolean canInsert() {
            return pool.canRecieveManaFromBursts() && !pool.isFull();
        }

        @Override
        public boolean canExtract() {
            return pool.getCurrentMana() > 0;
        }

        @Override
        public void insert(int amount) {
            if (amount > 0) {
                pool.recieveMana(amount);
            }
        }

        @Override
        public void extract(int amount) {
            int extracted = Math.min(amount, pool.getCurrentMana());
            if (extracted > 0) {
                pool.recieveMana(-extracted);
            }
        }
    }

    private static class TerraPlateNode implements ManaNode {
        private final TileTerraPlate plate;

        private TerraPlateNode(TileTerraPlate plate) {
            this.plate = plate;
        }

        @Override
        public int getCurrentMana() {
            return plate.getCurrentMana();
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, plate.getAvailableSpaceForMana());
        }

        @Override
        public boolean canInsert() {
            return plate.canRecieveManaFromBursts() && !plate.isFull();
        }

        @Override
        public boolean canExtract() {
            return plate.getCurrentMana() > 0;
        }

        @Override
        public void insert(int amount) {
            if (amount > 0) {
                plate.recieveMana(amount);
            }
        }

        @Override
        public void extract(int amount) {
            int extracted = Math.min(amount, plate.getCurrentMana());
            if (extracted > 0) {
                plate.recieveMana(-extracted);
            }
        }
    }

    private static class RuneAltarNode implements ManaNode {
        private final TileRuneAltar altar;

        private RuneAltarNode(TileRuneAltar altar) {
            this.altar = altar;
        }

        @Override
        public int getCurrentMana() {
            return altar.getCurrentMana();
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, altar.getTargetMana() - altar.getCurrentMana());
        }

        @Override
        public boolean canInsert() {
            return altar.canRecieveManaFromBursts() && !altar.isFull();
        }

        @Override
        public boolean canExtract() {
            return altar.getCurrentMana() > 0;
        }

        @Override
        public void insert(int amount) {
            if (amount > 0) {
                altar.recieveMana(amount);
            }
        }

        @Override
        public void extract(int amount) {
            int extracted = Math.min(amount, altar.getCurrentMana());
            if (extracted > 0) {
                altar.recieveMana(-extracted);
            }
        }
    }

    private static class CollectorNode implements ManaNode {
        private final IManaCollector collector;

        private CollectorNode(IManaCollector collector) {
            this.collector = collector;
        }

        @Override
        public int getCurrentMana() {
            return collector.getCurrentMana();
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, collector.getMaxMana() - collector.getCurrentMana());
        }

        @Override
        public boolean canInsert() {
            return collector.canRecieveManaFromBursts() && !collector.isFull();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public void insert(int amount) {
            if (amount > 0) {
                collector.recieveMana(amount);
            }
        }

        @Override
        public void extract(int amount) {
        }
    }

    private static class SparkAttachableNode implements ManaNode {
        private final ISparkAttachable attachable;

        private SparkAttachableNode(ISparkAttachable attachable) {
            this.attachable = attachable;
        }

        @Override
        public int getCurrentMana() {
            return attachable.getCurrentMana();
        }

        @Override
        public int getAvailableSpace() {
            return Math.max(0, attachable.getAvailableSpaceForMana());
        }

        @Override
        public boolean canInsert() {
            return attachable.canRecieveManaFromBursts() && !attachable.isFull();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public void insert(int amount) {
            if (amount > 0) {
                attachable.recieveMana(amount);
            }
        }

        @Override
        public void extract(int amount) {
        }
    }
}
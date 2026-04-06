package xnet.additions.thaumcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcjty.lib.varia.WorldTools;
import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IControllerContext;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.DefaultChannelSettings;
import mcjty.xnet.api.keys.ConsumerId;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.config.ConfigSetup;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.aspects.IAspectContainer;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EssentiaChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final String TAG_MODE = "mode";

    public enum ChannelMode {
        PRIORITY,
        ROUNDROBIN
    }
    private enum ContainerAddSemantics {
        NORMAL_LEFTOVER,
        RETURNS_ACCEPTED_AMOUNT
    }

    private static final String OBLIVION_JAR_CLASS =
            "com.verdantartifice.thaumicwonders.common.tiles.essentia.TileOblivionEssentiaJar";

    @Nonnull
    private static ContainerAddSemantics classifyAddSemantics(@Nullable TileEntity te) {
        if (te != null && OBLIVION_JAR_CLASS.equals(te.getClass().getName())) {
            return ContainerAddSemantics.RETURNS_ACCEPTED_AMOUNT;
        }
        return ContainerAddSemantics.NORMAL_LEFTOVER;
    }

    private static class EndpointEntry {
        private final SidedConsumer consumer;
        private final EssentiaConnectorSettings settings;

        @Nullable
        private String cachedClassName = null;

        @Nonnull
        private ContainerAddSemantics cachedAddSemantics = ContainerAddSemantics.NORMAL_LEFTOVER;

        private EndpointEntry(@Nonnull SidedConsumer consumer, @Nonnull EssentiaConnectorSettings settings) {
            this.consumer = consumer;
            this.settings = settings;
        }

        @Nonnull
        public SidedConsumer getConsumer() {
            return consumer;
        }

        @Nonnull
        public EssentiaConnectorSettings getSettings() {
            return settings;
        }

        @Nonnull
        public ContainerAddSemantics getAddSemantics(@Nullable TileEntity te) {
            String className = te == null ? null : te.getClass().getName();
            if (!java.util.Objects.equals(cachedClassName, className)) {
                cachedClassName = className;
                cachedAddSemantics = classifyAddSemantics(te);
            }
            return cachedAddSemantics;
        }
    }

    public static class ExtractOffer {
        private final Aspect aspect;
        private final int amount;
        private final int nextIndex;

        public ExtractOffer(@Nonnull Aspect aspect, int amount, int nextIndex) {
            this.aspect = aspect;
            this.amount = amount;
            this.nextIndex = nextIndex;
        }

        @Nonnull
        public Aspect getAspect() {
            return aspect;
        }

        public int getAmount() {
            return amount;
        }

        public int getNextIndex() {
            return nextIndex;
        }
    }

    private ChannelMode channelMode = ChannelMode.PRIORITY;
    private int delay = 0;
    private int roundRobinOffset = 0;

    private List<EndpointEntry> essentiaExtractors = null;
    private List<EndpointEntry> essentiaConsumers = null;
    private Map<ConsumerId, Integer> extractIndices = new HashMap<>();

    public ChannelMode getChannelMode() {
        return channelMode;
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

        if (data == null || !data.has(TAG_MODE) || data.get(TAG_MODE).isJsonNull()) {
            return;
        }

        try {
            channelMode = ChannelMode.valueOf(data.get(TAG_MODE).getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            channelMode = ChannelMode.PRIORITY;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (tag.hasKey(TAG_MODE)) {
            int mode = tag.getByte(TAG_MODE);
            if (mode >= 0 && mode < ChannelMode.values().length) {
                channelMode = ChannelMode.values()[mode];
            } else {
                channelMode = ChannelMode.PRIORITY;
            }
        } else {
            channelMode = ChannelMode.PRIORITY;
        }

        delay = tag.getInteger("delay");
        roundRobinOffset = tag.getInteger("offset");

        extractIndices.clear();
        int[] cons = tag.getIntArray("extidx");
        for (int idx = 0; idx < cons.length; idx += 2) {
            extractIndices.put(new ConsumerId(cons[idx]), cons[idx + 1]);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setByte(TAG_MODE, (byte) channelMode.ordinal());
        tag.setInteger("delay", delay);
        tag.setInteger("offset", roundRobinOffset);

        if (!extractIndices.isEmpty()) {
            int[] cons = new int[extractIndices.size() * 2];
            int idx = 0;
            for (Map.Entry<ConsumerId, Integer> entry : extractIndices.entrySet()) {
                cons[idx++] = entry.getKey().getId();
                cons[idx++] = entry.getValue();
            }
            tag.setIntArray("extidx", cons);
        }
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
        for (EndpointEntry entry : essentiaExtractors) {
            EssentiaConnectorSettings settings = entry.getSettings();

            if (d % settings.getSpeed() != 0) {
                continue;
            }

            BlockPos extractorPos = context.findConsumerPosition(entry.getConsumer().getConsumerId());
            if (extractorPos == null) {
                continue;
            }

            BlockPos pos = extractorPos.offset(entry.getConsumer().getSide());

            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }
            if (checkRedstone(world, settings, extractorPos)) {
                continue;
            }
            if (!context.matchColor(settings.getColorsMask())) {
                continue;
            }

            TileEntity te = world.getTileEntity(pos);
            EssentiaNode node = getEssentiaNode(te, entry.getAddSemantics(te));
            if (node == null || !node.canExtract()) {
                continue;
            }

            tickEssentiaNode(context, settings, entry.getConsumer().getConsumerId(), extractorPos, node);
        }
    }

    private void tickEssentiaNode(IControllerContext context,
                                  EssentiaConnectorSettings settings,
                                  ConsumerId consumerId,
                                  BlockPos extractorPos,
                                  EssentiaNode node) {
        ExtractOffer offer = node.findExtractable(settings, getExtractIndex(consumerId));
        if (offer == null) {
            return;
        }

        rememberExtractIndex(consumerId, offer.getNextIndex());

        Aspect aspect = offer.getAspect();
        int amount = offer.getAmount();
        if (amount <= 0) {
            return;
        }

        int rate = getRate(settings, context.getControllerWorld(), extractorPos);
        int toExtract = Math.min(rate, amount);

        Integer count = settings.getMinmax();
        if (count != null) {
            int canExtract = amount - count;
            if (canExtract <= 0) {
                return;
            }
            toExtract = Math.min(toExtract, canExtract);
        }

        if (toExtract <= 0) {
            return;
        }

        if (context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
            transferEssentia(node, aspect, toExtract, context);
        }
    }

    private int getExtractIndex(ConsumerId consumer) {
        return extractIndices.getOrDefault(consumer, 0);
    }

    private void rememberExtractIndex(ConsumerId consumer, int index) {
        extractIndices.put(consumer, index);
    }

    private static int getRate(EssentiaConnectorSettings connector, World world, BlockPos pos) {
        Integer rate = connector.getRate();
        if (rate != null) {
            return Math.max(0, rate);
        }

        return ConnectorBlock.isAdvancedConnector(world, pos)
                ? XNetAdditionsConfig.maxEssentiaRateAdvanced
                : XNetAdditionsConfig.maxEssentiaRateNormal;
    }

    private int transferEssentia(@Nonnull EssentiaNode from,
                                 @Nonnull Aspect aspect,
                                 int amount,
                                 @Nonnull IControllerContext context) {
        if (essentiaConsumers == null || essentiaConsumers.isEmpty() || amount <= 0) {
            return amount;
        }

        World world = context.getControllerWorld();

        if (channelMode == ChannelMode.PRIORITY) {
            roundRobinOffset = 0;
        }

        int remaining = amount;
        int startOffset = roundRobinOffset;

        for (int j = 0; j < essentiaConsumers.size(); j++) {
            int i = (j + startOffset) % essentiaConsumers.size();
            EndpointEntry entry = essentiaConsumers.get(i);
            EssentiaConnectorSettings insertSettings = entry.getSettings();

            if (!insertSettings.matches(aspect)) {
                continue;
            }

            BlockPos consumerPos = context.findConsumerPosition(entry.getConsumer().getConsumerId());
            if (consumerPos == null) {
                continue;
            }

            if (checkRedstone(world, insertSettings, consumerPos)) {
                continue;
            }
            if (!context.matchColor(insertSettings.getColorsMask())) {
                continue;
            }

            BlockPos pos = consumerPos.offset(entry.getConsumer().getSide());
            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }

            TileEntity te = world.getTileEntity(pos);
            EssentiaNode to = getEssentiaNode(te, entry.getAddSemantics(te));
            if (to == null || !to.canInsert()) {
                continue;
            }

            if (!to.accepts(aspect)) {
                continue;
            }

            int toInsert = Math.min(getRate(insertSettings, world, consumerPos), remaining);
            if (toInsert <= 0) {
                continue;
            }

            Integer count = insertSettings.getMinmax();
            if (count != null) {
                int currentAmount = to.count(aspect);
                int canInsert = count - currentAmount;
                if (canInsert <= 0) {
                    continue;
                }
                toInsert = Math.min(toInsert, canInsert);
            }

            int moved = moveEssentia(from, to, aspect, toInsert);
            if (moved <= 0) {
                continue;
            }

            remaining -= moved;

            if (channelMode == ChannelMode.ROUNDROBIN) {
                roundRobinOffset = (i + 1) % essentiaConsumers.size();
                return remaining;
            }

            if (remaining <= 0) {
                return 0;
            }
        }

        return remaining;
    }

    private int moveEssentia(@Nonnull EssentiaNode from,
                             @Nonnull EssentiaNode to,
                             @Nonnull Aspect aspect,
                             int request) {
        if (request <= 0) {
            return 0;
        }
        if (!from.canExtract() || !to.canInsert()) {
            return 0;
        }

        int available = from.count(aspect);
        if (available <= 0) {
            return 0;
        }

        int wanted = Math.min(request, available);
        if (wanted <= 0) {
            return 0;
        }

        int accepted = to.add(aspect, wanted);
        if (accepted <= 0) {
            return 0;
        }

        int taken = from.take(aspect, accepted);
        if (taken < accepted) {
            int rollback = accepted - taken;
            if (rollback > 0) {
                to.take(aspect, rollback);
            }
        }

        return taken;
    }

    private void updateCache(int channel, IControllerContext context) {
        if (essentiaExtractors == null) {
            essentiaExtractors = new ArrayList<>();
            essentiaConsumers = new ArrayList<>();

            Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                EssentiaConnectorSettings con = (EssentiaConnectorSettings) entry.getValue();
                if (con.getEssentiaMode() == EssentiaConnectorSettings.EssentiaMode.EXT) {
                    essentiaExtractors.add(new EndpointEntry(entry.getKey(), con));
                } else {
                    essentiaConsumers.add(new EndpointEntry(entry.getKey(), con));
                }
            }

            connectors = context.getRoutedConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                EssentiaConnectorSettings con = (EssentiaConnectorSettings) entry.getValue();
                if (con.getEssentiaMode() == EssentiaConnectorSettings.EssentiaMode.INS) {
                    essentiaConsumers.add(new EndpointEntry(entry.getKey(), con));
                }
            }

            essentiaConsumers.sort((o1, o2) ->
                    Integer.compare(o2.getSettings().getPriority(), o1.getSettings().getPriority()));
        }
    }

    @Override
    public void cleanCache() {
        essentiaExtractors = null;
        essentiaConsumers = null;
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(XNetAdditions.ICON_GUIELEMENTS, 22, 0, 11, 10);
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        gui.nl().choices(TAG_MODE, "Essentia channel mode", channelMode, ChannelMode.values());
    }

    @Override
    public void update(Map<String, Object> data) {
        if (data.containsKey(TAG_MODE) && data.get(TAG_MODE) instanceof String) {
            try {
                channelMode = ChannelMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
            } catch (IllegalArgumentException e) {
                channelMode = ChannelMode.PRIORITY;
            }
        } else {
            channelMode = ChannelMode.PRIORITY;
        }
    }

    @Override
    public int getColors() {
        return 0;
    }

    public interface EssentiaNode {
        boolean canInsert();
        boolean canExtract();

        @Nullable
        ExtractOffer findExtractable(@Nonnull EssentiaConnectorSettings settings, int startIndex);

        boolean accepts(@Nonnull Aspect aspect);

        int count(@Nonnull Aspect aspect);

        int add(@Nonnull Aspect aspect, int amount);

        int take(@Nonnull Aspect aspect, int amount);
    }

    @Nullable
    public static EssentiaNode getEssentiaNode(@Nullable TileEntity te,
                                               @Nonnull ContainerAddSemantics addSemantics) {
        if (te instanceof IAspectContainer) {
            return new ContainerNode((IAspectContainer) te, addSemantics);
        }
        return null;
    }

    private static class ContainerNode implements EssentiaNode {
        private final IAspectContainer container;
        private final ContainerAddSemantics addSemantics;

        private ContainerNode(@Nonnull IAspectContainer container,
                              @Nonnull ContainerAddSemantics addSemantics) {
            this.container = container;
            this.addSemantics = addSemantics;
        }

        @Override
        public boolean canInsert() {
            return true;
        }

        @Override
        public boolean canExtract() {
            try {
                AspectList list = container.getAspects();
                Aspect[] aspects = list == null ? null : list.getAspects();
                return aspects != null && aspects.length > 0;
            } catch (Exception e) {
                return false;
            }
        }

        @Nullable
        @Override
        public ExtractOffer findExtractable(@Nonnull EssentiaConnectorSettings settings, int startIndex) {
            try {
                AspectList list = container.getAspects();
                Aspect[] aspects = list == null ? null : list.getAspects();
                if (aspects == null || aspects.length == 0) {
                    return null;
                }

                int offset = Math.floorMod(startIndex, aspects.length);

                for (int j = 0; j < aspects.length; j++) {
                    int idx = (offset + j) % aspects.length;
                    Aspect aspect = aspects[idx];
                    if (aspect == null) {
                        continue;
                    }
                    if (!settings.matches(aspect)) {
                        continue;
                    }

                    int amount = count(aspect);
                    if (amount > 0) {
                        return new ExtractOffer(aspect, amount, (idx + 1) % aspects.length);
                    }
                }

                return null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean accepts(@Nonnull Aspect aspect) {
            try {
                return container.doesContainerAccept(aspect);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public int count(@Nonnull Aspect aspect) {
            try {
                return Math.max(0, container.containerContains(aspect));
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public int add(@Nonnull Aspect aspect, int amount) {
            if (amount <= 0) {
                return 0;
            }

            try {
                int result = container.addToContainer(aspect, amount);

                switch (addSemantics) {
                    case RETURNS_ACCEPTED_AMOUNT:
                        return Math.max(0, Math.min(amount, result));
                    case NORMAL_LEFTOVER:
                    default:
                        return Math.max(0, amount - Math.max(0, result));
                }
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public int take(@Nonnull Aspect aspect, int amount) {
            if (amount <= 0) {
                return 0;
            }

            try {
                if (container.containerContains(aspect) < amount) {
                    return 0;
                }
                return container.takeFromContainer(aspect, amount) ? amount : 0;
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
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
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.config.ConfigSetup;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.IEssentiaTransport;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EssentiaChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final String TAG_MODE = "mode";

    public enum ChannelMode {
        PRIORITY,
        ROUNDROBIN
    }

    private static class EndpointEntry {
        private final SidedConsumer consumer;
        private final EssentiaConnectorSettings settings;

        @Nullable
        private String cachedClassName = null;

        @Nonnull
        private EssentiaTilePolicy.TilePolicy cachedPolicy = EssentiaTilePolicy.TilePolicy.generic();

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
        public EssentiaTilePolicy.TilePolicy getPolicy(@Nullable TileEntity te) {
            String className = te == null ? null : te.getClass().getName();
            if (!Objects.equals(cachedClassName, className)) {
                cachedClassName = className;
                cachedPolicy = EssentiaTilePolicy.classify(te);
            }
            return cachedPolicy;
        }
    }

    private ChannelMode channelMode = ChannelMode.PRIORITY;
    private int delay = 0;
    private int roundRobinOffset = 0;

    private List<EndpointEntry> essentiaExtractors = null;
    private List<EndpointEntry> essentiaConsumers = null;

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
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setByte(TAG_MODE, (byte) channelMode.ordinal());
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
        for (EndpointEntry entry : essentiaExtractors) {
            EssentiaConnectorSettings settings = entry.getSettings();

            if (d % settings.getSpeed() != 0) {
                continue;
            }

            BlockPos extractorPos = context.findConsumerPosition(entry.getConsumer().getConsumerId());
            if (extractorPos == null) {
                continue;
            }

            EnumFacing cableSide = entry.getConsumer().getSide();
            BlockPos pos = extractorPos.offset(cableSide);

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
            EssentiaTilePolicy.TilePolicy policy = entry.getPolicy(te);
            EnumFacing effectiveFacing = EssentiaTilePolicy.getEffectiveOutputSide(settings.getFacing(), policy);

            EssentiaNode node = getEssentiaNode(te, effectiveFacing);
            if (node == null || !node.canExtract()) {
                continue;
            }

            tickEssentiaNode(context, settings, extractorPos, node);
        }
    }

    private void tickEssentiaNode(IControllerContext context,
                                  EssentiaConnectorSettings settings,
                                  BlockPos extractorPos,
                                  EssentiaNode node) {
        Aspect aspect = node.getAspect();
        if (aspect == null) {
            return;
        }
        if (!settings.matches(aspect)) {
            return;
        }

        int amount = node.getAmount();
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

            EnumFacing cableSide = entry.getConsumer().getSide();
            BlockPos pos = consumerPos.offset(cableSide);
            if (!WorldTools.chunkLoaded(world, pos)) {
                continue;
            }

            TileEntity te = world.getTileEntity(pos);
            EssentiaTilePolicy.TilePolicy policy = entry.getPolicy(te);
            if (policy.getSinkPolicy() == EssentiaTilePolicy.SinkPolicy.NEVER_GENERIC) {
                continue;
            }

            EnumFacing effectiveFacing = EssentiaTilePolicy.getEffectiveInputSide(insertSettings.getFacing(), policy);
            EssentiaNode to = getEssentiaNode(te, effectiveFacing);
            if (to == null || !to.canInsert()) {
                continue;
            }

            Aspect currentAspect = to.getAspect();
            if (currentAspect != null && !EssentiaTilePolicy.sameAspect(currentAspect, aspect)) {
                continue;
            }

            if (!EssentiaTilePolicy.canInsertInto(to, aspect, policy)) {
                continue;
            }

            int toInsert = Math.min(getRate(insertSettings, world, consumerPos), remaining);
            if (toInsert <= 0) {
                continue;
            }

            Integer count = insertSettings.getMinmax();
            if (count != null) {
                int currentAmount = currentAspect == null ? 0 : to.getAmount();
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

        int available = from.getAmount();
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
        Aspect getAspect();

        int getAmount();

        @Nullable
        Aspect getSuctionAspect();

        int getSuctionAmount();

        int add(@Nonnull Aspect aspect, int amount);
        int take(@Nonnull Aspect aspect, int amount);
    }

    @Nullable
    public static EssentiaNode getEssentiaNode(@Nullable TileEntity te, @Nullable EnumFacing requestedSide) {
        if (!(te instanceof IEssentiaTransport)) {
            return null;
        }

        IEssentiaTransport transport = (IEssentiaTransport) te;
        EnumFacing side = resolveSide(transport, requestedSide);
        if (side == null) {
            return null;
        }

        return new TransportNode(transport, side);
    }

    @Nullable
    private static EnumFacing resolveSide(@Nonnull IEssentiaTransport transport, @Nullable EnumFacing requestedSide) {
        if (requestedSide != null) {
            try {
                return transport.isConnectable(requestedSide) ? requestedSide : null;
            } catch (Exception e) {
                return null;
            }
        }

        for (EnumFacing facing : EnumFacing.values()) {
            try {
                if (transport.isConnectable(facing)) {
                    return facing;
                }
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    private static class TransportNode implements EssentiaNode {
        private final IEssentiaTransport transport;
        private final EnumFacing side;

        private TransportNode(@Nonnull IEssentiaTransport transport, @Nonnull EnumFacing side) {
            this.transport = transport;
            this.side = side;
        }

        @Override
        public boolean canInsert() {
            try {
                return transport.isConnectable(side) && transport.canInputFrom(side);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean canExtract() {
            try {
                return transport.isConnectable(side)
                        && transport.canOutputTo(side)
                        && transport.getEssentiaType(side) != null
                        && transport.getEssentiaAmount(side) > 0;
            } catch (Exception e) {
                return false;
            }
        }

        @Nullable
        @Override
        public Aspect getAspect() {
            try {
                return transport.getEssentiaType(side);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public int getAmount() {
            try {
                return transport.getEssentiaAmount(side);
            } catch (Exception e) {
                return 0;
            }
        }

        @Nullable
        @Override
        public Aspect getSuctionAspect() {
            try {
                return transport.getSuctionType(side);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public int getSuctionAmount() {
            try {
                return transport.getSuctionAmount(side);
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
                return transport.addEssentia(aspect, amount, side);
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
                return transport.takeEssentia(aspect, amount, side);
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
package xnet.additions.powertools.logic.network;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.api.channels.Color;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.apiimpl.logic.LogicConnectorSettings;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.logic.ChannelInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import xnet.additions.powertools.logicstatus.network.LogicSignalNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LogicSnapshotNetwork {

    private static final double MAX_CONTROLLER_DISTANCE_SQ = 64.0D;
    private static final int SIGNAL_MASK = 0xffff & ~(1 << Color.OFF.ordinal());

    private LogicSnapshotNetwork() {}

    public interface Receiver {
        void xnetadditions$receiveLogicSnapshot(Response response);
    }

    public static void init() {
        LogicSignalNetwork.CHANNEL.registerMessage(Request.Handler.class, Request.class, 2, Side.SERVER);
        LogicSignalNetwork.CHANNEL.registerMessage(Response.Handler.class, Response.class, 3, Side.CLIENT);
    }

    public static void request(BlockPos controllerPos, int requestId) {
        LogicSignalNetwork.CHANNEL.sendToServer(new Request(controllerPos, requestId));
    }

    public static final class Request implements IMessage {
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private int requestId;

        public Request() {}

        public Request(BlockPos controllerPos, int requestId) {
            this.controllerPos = controllerPos;
            this.requestId = requestId;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            controllerPos = BlockPos.fromLong(buf.readLong());
            requestId = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(controllerPos.toLong());
            buf.writeInt(requestId);
        }

        public static final class Handler implements IMessageHandler<Request, IMessage> {
            @Override
            public IMessage onMessage(Request message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().player;
                player.getServerWorld().addScheduledTask(() -> sendSnapshot(player, message.controllerPos, message.requestId));
                return null;
            }
        }
    }

    public static final class SourceState {
        private final int channel;
        private final int consumerId;
        private final EnumFacing side;
        private final int colorMask;

        private SourceState(int channel, int consumerId, EnumFacing side, int colorMask) {
            this.channel = channel;
            this.consumerId = consumerId;
            this.side = side;
            this.colorMask = colorMask;
        }

        public int getChannel() {
            return channel;
        }

        public int getConsumerId() {
            return consumerId;
        }

        public EnumFacing getSide() {
            return side;
        }

        public int getColorMask() {
            return colorMask;
        }
    }

    public static final class RoutedReference {
        private final int channel;
        private final SidedPos target;
        private final int colorMask;
        private final byte operator;

        private RoutedReference(int channel, SidedPos target, int colorMask, byte operator) {
            this.channel = channel;
            this.target = target;
            this.colorMask = colorMask;
            this.operator = operator;
        }

        public int getChannel() {
            return channel;
        }

        public SidedPos getTarget() {
            return target;
        }

        public int getColorMask() {
            return colorMask;
        }

        public byte getOperator() {
            return operator;
        }
    }

    public static final class Response implements IMessage {
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private int requestId;
        private final List<SourceState> sources = new ArrayList<>();
        private final List<RoutedReference> routedReferences = new ArrayList<>();

        public Response() {}

        private Response(BlockPos controllerPos, int requestId, List<SourceState> sources, List<RoutedReference> routedReferences) {
            this.controllerPos = controllerPos;
            this.requestId = requestId;
            this.sources.addAll(sources);
            this.routedReferences.addAll(routedReferences);
        }

        public BlockPos getControllerPos() {
            return controllerPos;
        }

        public int getRequestId() {
            return requestId;
        }

        public List<SourceState> getSources() {
            return sources;
        }

        public List<RoutedReference> getRoutedReferences() {
            return routedReferences;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            controllerPos = BlockPos.fromLong(buf.readLong());
            requestId = buf.readInt();

            int sourceCount = buf.readInt();
            for (int i = 0; i < sourceCount; i++) {
                int channel = buf.readUnsignedByte();
                int consumerId = buf.readInt();
                EnumFacing side = EnumFacing.VALUES[buf.readUnsignedByte()];
                int colorMask = buf.readInt();
                sources.add(new SourceState(channel, consumerId, side, colorMask));
            }

            int referenceCount = buf.readInt();
            for (int i = 0; i < referenceCount; i++) {
                int channel = buf.readUnsignedByte();
                BlockPos pos = BlockPos.fromLong(buf.readLong());
                EnumFacing side = EnumFacing.VALUES[buf.readUnsignedByte()];
                int colorMask = buf.readInt();
                byte operator = buf.readByte();
                routedReferences.add(new RoutedReference(channel, new SidedPos(pos, side), colorMask, operator));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(controllerPos.toLong());
            buf.writeInt(requestId);

            buf.writeInt(sources.size());
            for (SourceState source : sources) {
                buf.writeByte(source.channel);
                buf.writeInt(source.consumerId);
                buf.writeByte(source.side.ordinal());
                buf.writeInt(source.colorMask);
            }

            buf.writeInt(routedReferences.size());
            for (RoutedReference reference : routedReferences) {
                buf.writeByte(reference.channel);
                buf.writeLong(reference.target.getPos().toLong());
                buf.writeByte(reference.target.getSide().ordinal());
                buf.writeInt(reference.colorMask);
                buf.writeByte(reference.operator);
            }
        }

        public static final class Handler implements IMessageHandler<Response, IMessage> {
            @Override
            public IMessage onMessage(Response message, MessageContext ctx) {
                Minecraft minecraft = Minecraft.getMinecraft();
                minecraft.addScheduledTask(() -> {
                    if (minecraft.currentScreen instanceof Receiver) {
                        ((Receiver) minecraft.currentScreen).xnetadditions$receiveLogicSnapshot(message);
                    }
                });
                return null;
            }
        }
    }

    private static void sendSnapshot(EntityPlayerMP player, BlockPos controllerPos, int requestId) {
        if (player.getDistanceSqToCenter(controllerPos) > MAX_CONTROLLER_DISTANCE_SQ || !player.world.isBlockLoaded(controllerPos)) {return;}
        TileEntity tile = player.world.getTileEntity(controllerPos);
        if (!(tile instanceof TileEntityController)) {return;}

        TileEntityController controller = (TileEntityController) tile;
        List<SourceState> sources = new ArrayList<>();
        List<RoutedReference> routedReferences = new ArrayList<>();
        ChannelInfo[] channels = controller.getChannels();

        for (int channelIndex = 0; channelIndex < channels.length; channelIndex++) {
            ChannelInfo channel = channels[channelIndex];
            if (channel == null) {continue;}

            if ("xnet.logic".equals(channel.getType().getID())) {
                for (Map.Entry<SidedConsumer, IConnectorSettings> entry : controller.getConnectors(channelIndex).entrySet()) {
                    if (findConnectedTarget(controller, entry.getKey()) == null) {continue;}
                    if (!(entry.getValue() instanceof LogicConnectorSettings)) {continue;}
                    LogicConnectorSettings settings = (LogicConnectorSettings) entry.getValue();
                    if (settings.getLogicMode() != LogicConnectorSettings.LogicMode.SENSOR) {continue;}
                    int mask = channel.isEnabled() ? settings.getColorMask() & SIGNAL_MASK : 0;
                    sources.add(new SourceState(channelIndex, entry.getKey().getConsumerId().getId(), entry.getKey().getSide(), mask));
                }
            }

            if (channel.getChannelName().isEmpty()) {continue;}
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : controller.getRoutedConnectors(channelIndex).entrySet()) {
                if (!(entry.getValue() instanceof AbstractConnectorSettings)) {continue;}
                AbstractConnectorSettings settings = (AbstractConnectorSettings) entry.getValue();
                int mask = settings.getColorsMask() & SIGNAL_MASK;
                if (mask == 0) {continue;}

                SidedPos target = findConnectedTarget(controller, entry.getKey());
                if (target == null) {continue;}
                routedReferences.add(new RoutedReference(channelIndex, target, mask, encodeOperator(channel.getType().getID(), settings)));
            }
        }

        LogicSignalNetwork.CHANNEL.sendTo(new Response(controllerPos, requestId, sources, routedReferences), player);
    }

    private static byte encodeOperator(String type, AbstractConnectorSettings settings) {
        if (usesDirectColorMask(type)) {return 0;}
        int ordinal = settings.getColorOperator().ordinal();
        return ordinal >= 0 && ordinal <= 3 ? (byte) ordinal : 0;
    }

    private static SidedPos findConnectedTarget(TileEntityController controller, SidedConsumer consumer) {
        BlockPos connectorPos = controller.findConsumerPosition(consumer.getConsumerId());
        if (connectorPos == null || !controller.getWorld().isBlockLoaded(connectorPos)) {return null;}
        EnumFacing side = consumer.getSide();
        BlockPos targetPos = connectorPos.offset(side);
        if (!controller.getWorld().isBlockLoaded(targetPos) || !ConnectorBlock.isConnectable(controller.getWorld(), connectorPos, side)) {return null;}
        return new SidedPos(targetPos, side.getOpposite());
    }

    private static boolean usesDirectColorMask(String type) {
        switch (type) {
            case "advanced.energy":
            case "mekanism.gas":
            case "botania.mana":
            case "tc.essentia":
            case "ic2.eu":
                return true;
            default:
                return false;
        }
    }
}
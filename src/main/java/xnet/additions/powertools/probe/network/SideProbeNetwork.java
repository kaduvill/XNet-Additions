package xnet.additions.powertools.probe.network;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.api.keys.ConsumerId;
import mcjty.xnet.api.keys.NetworkId;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.blocks.cables.ConnectorTileEntity;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.multiblock.WorldBlob;
import mcjty.xnet.multiblock.XNetBlobData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xnet.additions.powertools.probe.SideProbe;

public final class SideProbeNetwork {
    private static final Logger LOGGER = LogManager.getLogger(SideProbeNetwork.class);
    public static final byte RESPONSE_RESULT = 0;
    public static final byte RESPONSE_ERROR = 1;
    private static final double MAX_CONTROLLER_DISTANCE_SQ = 64.0D;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("xnetaddprobe");

    private SideProbeNetwork() {}

    public interface Receiver {
        void xnetadditions$receiveSideProbe(Response response);
    }

    public static void init() {
        CHANNEL.registerMessage(Request.Handler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(Response.Handler.class, Response.class, 1, Side.CLIENT);
    }

    private static boolean send(EntityPlayerMP player, Response response) {
        try {
            if (player == null || player.isDead || player.getServer() == null
                    || player.getServer().getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) {return false;}
            CHANNEL.sendTo(response, player);
            return true;
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            return false;
        }
    }

    public static final class Request implements IMessage {
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private BlockPos targetPos = BlockPos.ORIGIN;
        private int targetSide = -1;
        private int requestId;

        public Request() {}

        public Request(BlockPos controllerPos, SidedPos target, int requestId) {
            this.controllerPos = controllerPos;
            this.targetPos = target.getPos();
            this.targetSide = target.getSide().ordinal();
            this.requestId = requestId;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            controllerPos = BlockPos.fromLong(buf.readLong());
            targetPos = BlockPos.fromLong(buf.readLong());
            targetSide = buf.readUnsignedByte();
            requestId = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(controllerPos.toLong());
            buf.writeLong(targetPos.toLong());
            buf.writeByte(targetSide);
            buf.writeInt(requestId);
        }

        public static final class Handler implements IMessageHandler<Request, IMessage> {
            @Override
            public IMessage onMessage(Request message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().player;
                player.getServerWorld().addScheduledTask(() -> handle(player, message));
                return null;
            }
        }

        private static void handle(EntityPlayerMP player, Request request) {
            WorldServer world = player.getServerWorld();
            int dimension = world.provider.getDimension();
            if (request.targetSide < 0 || request.targetSide >= EnumFacing.VALUES.length) {
                send(player, Response.error(dimension, request.controllerPos, null, request.requestId, "Invalid probe target"));
                return;
            }

            SidedPos target = new SidedPos(request.targetPos, EnumFacing.VALUES[request.targetSide]);
            if (player.world != world || player.getDistanceSqToCenter(request.controllerPos) > MAX_CONTROLLER_DISTANCE_SQ
                    || !world.isBlockLoaded(request.controllerPos)) {
                send(player, Response.error(dimension, request.controllerPos, target, request.requestId, "Controller is unavailable"));
                return;
            }

            TileEntity tile = world.getTileEntity(request.controllerPos);
            if (!(tile instanceof TileEntityController) || tile.getWorld() != world
                    || !((TileEntityController) tile).canPlayerAccess(player)) {
                send(player, Response.error(dimension, request.controllerPos, target, request.requestId, "Controller is unavailable"));
                return;
            }

            BlockPos connectorPos = target.getPos().offset(target.getSide());
            if (!world.isBlockLoaded(target.getPos()) || !world.isBlockLoaded(connectorPos)) {
                send(player, Response.error(dimension, request.controllerPos, target, request.requestId, "Target is unavailable"));
                return;
            }

            try {
                TileEntityController controller = (TileEntityController) tile;
                NetworkId networkId = controller.getNetworkId();
                WorldBlob blob = XNetBlobData.getBlobData(world).getWorldBlob(world);
                ConsumerId consumer = blob.getConsumerAt(connectorPos);
                TileEntity connectorTile = world.getTileEntity(connectorPos);
                if (networkId == null || consumer == null || !(connectorTile instanceof ConnectorTileEntity)
                        || !(world.getBlockState(connectorPos).getBlock() instanceof ConnectorBlock)
                        || !blob.getNetworksAt(connectorPos).contains(networkId)
                        || !ConnectorBlock.isConnectable(world, connectorPos, target.getSide().getOpposite())) {
                    send(player, Response.error(dimension, request.controllerPos, target, request.requestId, "Target is no longer connected"));
                    return;
                }

                TileEntity targetTile = world.getTileEntity(target.getPos());
                if (targetTile == null || targetTile.isInvalid() || targetTile.getWorld() != world) {
                    send(player, Response.error(dimension, request.controllerPos, target, request.requestId, "Target is unavailable"));
                    return;
                }

                send(player, Response.result(controller, target, request.requestId, SideProbe.scan(targetTile, target.getSide())));
            } catch (Throwable throwable) {
                rethrowFatal(throwable);
                LOGGER.error("Side probe failed for " + player.getName() + " at " + request.targetPos, throwable);
                send(player, Response.error(dimension, request.controllerPos, target, request.requestId, "Side probe failed safely"));
            }
        }
    }

    public static final class Response implements IMessage {
        private byte kind;
        private int dimension;
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private SidedPos target = new SidedPos(BlockPos.ORIGIN, EnumFacing.DOWN);
        private int requestId;
        private String message = "";
        private SideProbe.Snapshot snapshot;

        public Response() {}

        private static Response result(TileEntityController controller, SidedPos target, int requestId, SideProbe.Snapshot snapshot) {
            Response response = new Response();
            response.kind = RESPONSE_RESULT;
            response.dimension = controller.getWorld().provider.getDimension();
            response.controllerPos = controller.getPos();
            response.target = target;
            response.requestId = requestId;
            response.snapshot = snapshot;
            return response;
        }

        private static Response error(int dimension, BlockPos controllerPos, SidedPos target, int requestId, String message) {
            Response response = new Response();
            response.kind = RESPONSE_ERROR;
            response.dimension = dimension;
            response.controllerPos = controllerPos;
            if (target != null) {response.target = target;}
            response.requestId = requestId;
            response.message = message;
            return response;
        }

        public byte getKind() {return kind;}
        public int getDimension() {return dimension;}
        public BlockPos getControllerPos() {return controllerPos;}
        public SidedPos getTarget() {return target;}
        public int getRequestId() {return requestId;}
        public String getMessage() {return message;}
        public SideProbe.Snapshot getSnapshot() {return snapshot;}

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            dimension = buf.readInt();
            controllerPos = BlockPos.fromLong(buf.readLong());
            BlockPos targetPos = BlockPos.fromLong(buf.readLong());
            int side = buf.readUnsignedByte();
            if (side >= EnumFacing.VALUES.length) {throw new IllegalArgumentException("Invalid Side Probe target side: " + side);}
            target = new SidedPos(targetPos, EnumFacing.VALUES[side]);
            requestId = buf.readInt();
            if (kind == RESPONSE_RESULT) {
                snapshot = SideProbe.Snapshot.fromBytes(buf);
            } else if (kind == RESPONSE_ERROR) {
                message = ByteBufUtils.readUTF8String(buf);
            } else {
                throw new IllegalArgumentException("Invalid Side Probe response: " + kind);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            buf.writeInt(dimension);
            buf.writeLong(controllerPos.toLong());
            buf.writeLong(target.getPos().toLong());
            buf.writeByte(target.getSide().ordinal());
            buf.writeInt(requestId);
            if (kind == RESPONSE_RESULT) {
                snapshot.toBytes(buf);
            } else if (kind == RESPONSE_ERROR) {
                ByteBufUtils.writeUTF8String(buf, message);
            } else {
                throw new IllegalArgumentException("Invalid Side Probe response: " + kind);
            }
        }

        public static final class Handler implements IMessageHandler<Response, IMessage> {
            @Override
            public IMessage onMessage(Response message, MessageContext ctx) {
                Minecraft minecraft = Minecraft.getMinecraft();
                NetHandlerPlayClient connection = ctx.getClientHandler();
                minecraft.addScheduledTask(() -> {
                    if (minecraft.getConnection() != connection || !(minecraft.currentScreen instanceof Receiver)) {return;}
                    try {
                        ((Receiver) minecraft.currentScreen).xnetadditions$receiveSideProbe(message);
                    } catch (Throwable throwable) {
                        rethrowFatal(throwable);
                    }
                });
                return null;
            }
        }
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof ThreadDeath) {throw (ThreadDeath) throwable;}
        if (throwable instanceof VirtualMachineError) {throw (VirtualMachineError) throwable;}
    }
}
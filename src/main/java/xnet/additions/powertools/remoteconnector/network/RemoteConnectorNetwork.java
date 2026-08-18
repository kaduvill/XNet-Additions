package xnet.additions.powertools.remoteconnector.network;

import io.netty.buffer.ByteBuf;
import mcjty.lib.network.NetworkTools;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.lib.typed.TypedMap;
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
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import xnet.additions.powertools.remoteconnector.ConnectorDirectionNames;

import javax.annotation.Nullable;

public final class RemoteConnectorNetwork {
    public static final byte OPEN = 0;
    public static final byte ERROR = 1;
    public static final byte RETURN = 2;
    public static final byte CLOSE = 3;
    private static final byte REQUEST_OPEN = 0;
    private static final byte NAME = 1;
    private static final byte SIDE = 2;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("xnetaddremote");

    private RemoteConnectorNetwork() {}

    public interface Receiver {
        void xnetadditions$receiveRemoteConnector(Response response);
    }

    public static void init() {
        CHANNEL.registerMessage(Request.Handler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(Response.Handler.class, Response.class, 1, Side.CLIENT);
    }

    public static final class Request implements IMessage {
        private byte operation;
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private SidedPos target = new SidedPos(BlockPos.ORIGIN, EnumFacing.DOWN);
        private int requestId;
        private String name = "";
        private int facing;
        private boolean enabled;

        public Request() {}

        private Request(byte operation, BlockPos controllerPos, SidedPos target, int requestId) {
            this.operation = operation;
            this.controllerPos = controllerPos;
            this.target = target;
            this.requestId = requestId;
        }

        public static Request open(BlockPos controllerPos, SidedPos target, int requestId) {
            return new Request(REQUEST_OPEN, controllerPos, target, requestId);
        }

        public static Request name(BlockPos controllerPos, SidedPos target, int requestId, String name) {
            Request request = new Request(NAME, controllerPos, target, requestId);
            request.name = name;
            return request;
        }

        public static Request side(BlockPos controllerPos, SidedPos target, int requestId, int facing, boolean enabled) {
            Request request = new Request(SIDE, controllerPos, target, requestId);
            request.facing = facing;
            request.enabled = enabled;
            return request;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            operation = buf.readByte();
            controllerPos = BlockPos.fromLong(buf.readLong());
            target = readSidedPos(buf);
            requestId = buf.readInt();
            if (operation == NAME) {
                name = NetworkTools.readString(buf);
                if (name == null) {throw new IllegalArgumentException("Null connector name");}
            } else if (operation == SIDE) {
                facing = buf.readUnsignedByte();
                if (facing >= EnumFacing.VALUES.length) {throw new IllegalArgumentException("Invalid connector facing: " + facing);}
                enabled = buf.readBoolean();
            } else if (operation != REQUEST_OPEN) {
                throw new IllegalArgumentException("Invalid remote connector request: " + operation);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(operation);
            buf.writeLong(controllerPos.toLong());
            writeSidedPos(buf, target);
            buf.writeInt(requestId);
            if (operation == NAME) {
                NetworkTools.writeString(buf, name);
            } else if (operation == SIDE) {
                buf.writeByte(facing);
                buf.writeBoolean(enabled);
            } else if (operation != REQUEST_OPEN) {
                throw new IllegalArgumentException("Invalid remote connector request: " + operation);
            }
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
            TileEntityController controller = findController(player, request.controllerPos);
            if (controller == null) {
                CHANNEL.sendTo(request.operation == REQUEST_OPEN
                        ? Response.error(request, "Controller is unavailable")
                        : Response.close(request, "Controller is unavailable"), player);
                return;
            }
            ConnectorTileEntity connector = findConnector(controller, request.target, request.operation == REQUEST_OPEN);
            if (connector == null) {
                CHANNEL.sendTo(request.operation == REQUEST_OPEN
                        ? Response.error(request, "Connector is unavailable")
                        : Response.back(request, "Connector is unavailable"), player);
                return;
            }
            if (request.operation == REQUEST_OPEN) {
                CHANNEL.sendTo(Response.open(request, connector), player);
                return;
            }
            TypedMap params;
            String command;
            if (request.operation == NAME) {
                command = GenericTileEntity.COMMAND_SYNC_BINDING;
                params = TypedMap.builder().put(ConnectorTileEntity.VALUE_NAME, request.name).build();
            } else {
                command = ConnectorTileEntity.CMD_ENABLE;
                params = TypedMap.builder().put(ConnectorTileEntity.PARAM_FACING, request.facing)
                        .put(ConnectorTileEntity.PARAM_ENABLED, request.enabled).build();
            }
            if (!connector.execute(player, command, params)) {
                CHANNEL.sendTo(Response.back(request, "Connector rejected the update"), player);
            }
        }
    }

    public static final class Response implements IMessage {
        private byte kind;
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private SidedPos target = new SidedPos(BlockPos.ORIGIN, EnumFacing.DOWN);
        private int requestId;
        private String name = "";
        private int enabledMask;
        private int connectedMask;
        private String[] directionNames = new String[EnumFacing.VALUES.length];
        private String message = "";

        public Response() {}

        private Response(byte kind, Request request) {
            this.kind = kind;
            controllerPos = request.controllerPos;
            target = request.target;
            requestId = request.requestId;
        }

        private static Response open(Request request, ConnectorTileEntity connector) {
            Response response = new Response(OPEN, request);
            response.name = connector.getConnectorName();
            for (EnumFacing facing : EnumFacing.VALUES) {
                if (connector.isEnabled(facing)) {response.enabledMask |= 1 << facing.ordinal();}
            }
            response.connectedMask = ConnectorDirectionNames.connectedMask(connector.getWorld(), connector.getPos());
            response.directionNames = ConnectorDirectionNames.snapshot(connector.getWorld(), connector.getPos());
            return response;
        }

        private static Response error(Request request, String message) {
            Response response = new Response(ERROR, request);
            response.message = message;
            return response;
        }

        private static Response back(Request request, String message) {
            Response response = new Response(RETURN, request);
            response.message = message;
            return response;
        }

        private static Response close(Request request, String message) {
            Response response = new Response(CLOSE, request);
            response.message = message;
            return response;
        }

        public byte getKind() {return kind;}
        public BlockPos getControllerPos() {return controllerPos;}
        public SidedPos getTarget() {return target;}
        public int getRequestId() {return requestId;}
        public String getName() {return name;}
        public int getEnabledMask() {return enabledMask;}
        public int getConnectedMask() {return connectedMask;}
        public String[] getDirectionNames() {return directionNames.clone();}
        public String getMessage() {return message;}

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            controllerPos = BlockPos.fromLong(buf.readLong());
            target = readSidedPos(buf);
            requestId = buf.readInt();
            if (kind == OPEN) {
                name = NetworkTools.readString(buf);
                enabledMask = buf.readUnsignedByte();
                connectedMask = buf.readUnsignedByte();
                for (EnumFacing facing : EnumFacing.VALUES) {
                    directionNames[facing.ordinal()] = NetworkTools.readStringUTF8(buf);
                }
            } else if (kind == ERROR || kind == RETURN || kind == CLOSE) {
                message = NetworkTools.readString(buf);
            } else {
                throw new IllegalArgumentException("Invalid remote connector response: " + kind);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            buf.writeLong(controllerPos.toLong());
            writeSidedPos(buf, target);
            buf.writeInt(requestId);
            if (kind == OPEN) {
                NetworkTools.writeString(buf, name);
                buf.writeByte(enabledMask);
                buf.writeByte(connectedMask);
                for (String directionName : directionNames) {
                    NetworkTools.writeStringUTF8(buf, directionName);
                }
            } else if (kind == ERROR || kind == RETURN || kind == CLOSE) {
                NetworkTools.writeString(buf, message);
            } else {
                throw new IllegalArgumentException("Invalid remote connector response: " + kind);
            }
        }

        public static final class Handler implements IMessageHandler<Response, IMessage> {
            @Override
            public IMessage onMessage(Response message, MessageContext ctx) {
                Minecraft minecraft = Minecraft.getMinecraft();
                NetHandlerPlayClient connection = ctx.getClientHandler();
                minecraft.addScheduledTask(() -> {
                    if (minecraft.getConnection() == connection && minecraft.currentScreen instanceof Receiver) {
                        ((Receiver) minecraft.currentScreen).xnetadditions$receiveRemoteConnector(message);
                    }
                });
                return null;
            }
        }
    }

    @Nullable
    private static TileEntityController findController(EntityPlayerMP player, BlockPos pos) {
        WorldServer world = player.getServerWorld();
        if (player.world != world || !world.isBlockLoaded(pos, false)) {return null;}
        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof TileEntityController) || tile.getWorld() != world) {return null;}
        TileEntityController controller = (TileEntityController) tile;
        return controller.canPlayerAccess(player) ? controller : null;
    }

    @Nullable
    private static ConnectorTileEntity findConnector(TileEntityController controller, SidedPos target, boolean requireConnectedSide) {
        WorldServer world = (WorldServer) controller.getWorld();
        BlockPos connectorPos = target.getPos().offset(target.getSide());
        if (!world.isBlockLoaded(connectorPos, false)) {return null;}
        TileEntity tile = world.getTileEntity(connectorPos);
        if (!(tile instanceof ConnectorTileEntity) || tile.getWorld() != world
                || !(world.getBlockState(connectorPos).getBlock() instanceof ConnectorBlock)) {return null;}
        NetworkId network = controller.getNetworkId();
        WorldBlob worldBlob = XNetBlobData.getBlobData(world).getWorldBlob(world);
        if (network == null || worldBlob.getConsumerAt(connectorPos) == null
                || !worldBlob.getNetworksAt(connectorPos).contains(network)) {return null;}
        if (requireConnectedSide && (!world.isBlockLoaded(target.getPos(), false)
                || !ConnectorBlock.isConnectable(world, connectorPos, target.getSide().getOpposite()))) {return null;}
        return (ConnectorTileEntity) tile;
    }

    private static SidedPos readSidedPos(ByteBuf buf) {
        BlockPos pos = BlockPos.fromLong(buf.readLong());
        int side = buf.readUnsignedByte();
        if (side >= EnumFacing.VALUES.length) {throw new IllegalArgumentException("Invalid side: " + side);}
        return new SidedPos(pos, EnumFacing.VALUES[side]);
    }

    private static void writeSidedPos(ByteBuf buf, SidedPos pos) {
        buf.writeLong(pos.getPos().toLong());
        buf.writeByte(pos.getSide().ordinal());
    }
}
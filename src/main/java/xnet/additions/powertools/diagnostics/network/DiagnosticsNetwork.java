package xnet.additions.powertools.diagnostics.network;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.blocks.controller.TileEntityController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
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
import xnet.additions.powertools.diagnostics.ControllerDiagnostics;
import xnet.additions.powertools.diagnostics.client.ControllerDiagnosticsSessionStore;

public final class DiagnosticsNetwork {
    private static final Logger LOGGER = LogManager.getLogger(DiagnosticsNetwork.class);
    public static final byte SNAPSHOT = 0;
    public static final byte START_PROFILE = 1;
    public static final byte RESPONSE_SNAPSHOT = 0;
    public static final byte RESPONSE_STARTED = 1;
    public static final byte RESPONSE_PROGRESS = 2;
    public static final byte RESPONSE_RESULT = 3;
    public static final byte RESPONSE_BUSY = 4;
    public static final byte RESPONSE_ERROR = 5;
    private static final double MAX_CONTROLLER_DISTANCE_SQ = 64.0D;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("xnetadditionsdiag");

    private DiagnosticsNetwork() {}

    public interface Receiver {
        void xnetadditions$receiveDiagnostics(Response response);
    }

    public static void init() {
        CHANNEL.registerMessage(Request.Handler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(Response.Handler.class, Response.class, 1, Side.CLIENT);
    }

    public static boolean sendProgress(EntityPlayerMP player, TileEntityController controller, int requestId, int samples) {
        try {
            return send(player, Response.progress(controller, requestId, samples));
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            return false;
        }
    }

    public static void sendResult(EntityPlayerMP player, TileEntityController controller, int requestId,
                                  ControllerDiagnostics.Result result) {
        try {
            send(player, Response.result(controller, requestId, result));
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
        }
    }

    public static void sendError(EntityPlayerMP player, TileEntityController controller, int requestId, String message) {
        try {
            send(player, Response.error(controller.getWorld().provider.getDimension(), controller.getPos(), requestId, message));
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
        }
    }

    private static boolean send(EntityPlayerMP player, Response response) {
        try {
            if (!isConnected(player)) {return false;}
            CHANNEL.sendTo(response, player);
            return true;
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            return false;
        }
    }

    private static boolean isConnected(EntityPlayerMP player) {
        return player != null && !player.isDead && player.getServer() != null
                && player.getServer().getPlayerList().getPlayerByUUID(player.getUniqueID()) == player;
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof ThreadDeath) {throw (ThreadDeath) throwable;}
        if (throwable instanceof VirtualMachineError) {throw (VirtualMachineError) throwable;}
    }

    public static final class Request implements IMessage {
        private byte operation;
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private int requestId;

        public Request() {}

        public Request(byte operation, BlockPos controllerPos, int requestId) {
            this.operation = operation;
            this.controllerPos = controllerPos;
            this.requestId = requestId;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            operation = buf.readByte();
            controllerPos = BlockPos.fromLong(buf.readLong());
            requestId = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(operation);
            buf.writeLong(controllerPos.toLong());
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
            if ((request.operation != SNAPSHOT && request.operation != START_PROFILE)
                    || player.world != world || player.getDistanceSqToCenter(request.controllerPos) > MAX_CONTROLLER_DISTANCE_SQ
                    || !world.isBlockLoaded(request.controllerPos)) {
                send(player, Response.error(dimension, request.controllerPos, request.requestId, "Controller is unavailable"));
                return;
            }
            TileEntity tile = world.getTileEntity(request.controllerPos);
            if (!(tile instanceof TileEntityController) || !(tile instanceof ControllerDiagnostics.Access)
                    || tile.getWorld() != world) {
                send(player, Response.error(dimension, request.controllerPos, request.requestId, "Controller is unavailable"));
                return;
            }
            TileEntityController controller = (TileEntityController) tile;
            ControllerDiagnostics.Access access = (ControllerDiagnostics.Access) (Object) controller;
            try {
                if (request.operation == SNAPSHOT) {
                    send(player, Response.snapshot(controller, request.requestId, ControllerDiagnostics.Snapshot.capture(controller), access.xnetadditions$getProfileStatus(player)));
                } else if (access.xnetadditions$startProfile(player, request.requestId)) {
                    send(player, Response.started(controller, request.requestId));
                } else {
                    send(player, Response.busy(controller, request.requestId));
                }
            } catch (Throwable throwable) {
                rethrowFatal(throwable);
                LOGGER.error("Diagnostics request failed for " + player.getName() + " at " + request.controllerPos, throwable);
                send(player, Response.error(dimension, request.controllerPos, request.requestId, "Diagnostics request failed safely"));
            }
        }
    }

    public static final class Response implements IMessage {
        private byte kind;
        private int dimension;
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private int requestId;
        private int samples;
        private byte profileStatus;
        private int profileRequestId;
        private String message = "";
        private ControllerDiagnostics.Snapshot snapshot;
        private ControllerDiagnostics.Result result;

        public Response() {}

        private Response(byte kind, TileEntityController controller, int requestId) {
            this.kind = kind;
            this.dimension = controller.getWorld().provider.getDimension();
            this.controllerPos = controller.getPos();
            this.requestId = requestId;
        }

        private static Response snapshot(TileEntityController controller, int requestId, ControllerDiagnostics.Snapshot snapshot,
                                         ControllerDiagnostics.ProfileStatus profile) {
            Response response = new Response(RESPONSE_SNAPSHOT, controller, requestId);
            response.snapshot = snapshot;
            response.profileStatus = profile.state;
            response.profileRequestId = profile.requestId;
            response.samples = profile.samples;
            return response;
        }

        private static Response started(TileEntityController controller, int requestId) {
            return new Response(RESPONSE_STARTED, controller, requestId);
        }

        private static Response progress(TileEntityController controller, int requestId, int samples) {
            Response response = new Response(RESPONSE_PROGRESS, controller, requestId);
            response.samples = samples;
            return response;
        }

        private static Response result(TileEntityController controller, int requestId, ControllerDiagnostics.Result result) {
            Response response = new Response(RESPONSE_RESULT, controller, requestId);
            response.result = result;
            return response;
        }

        private static Response busy(TileEntityController controller, int requestId) {
            Response response = new Response(RESPONSE_BUSY, controller, requestId);
            response.message = "This Controller is already being profiled";
            return response;
        }

        private static Response error(int dimension, BlockPos controllerPos, int requestId, String message) {
            Response response = new Response();
            response.kind = RESPONSE_ERROR;
            response.dimension = dimension;
            response.controllerPos = controllerPos;
            response.requestId = requestId;
            response.message = message;
            return response;
        }

        public byte getKind() {return kind;}
        public int getDimension() {return dimension;}
        public BlockPos getControllerPos() {return controllerPos;}
        public int getRequestId() {return requestId;}
        public int getSamples() {return samples;}
        public byte getProfileStatus() {return profileStatus;}
        public int getProfileRequestId() {return profileRequestId;}
        public String getMessage() {return message;}
        public ControllerDiagnostics.Snapshot getSnapshot() {return snapshot;}
        public ControllerDiagnostics.Result getResult() {return result;}

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            dimension = buf.readInt();
            controllerPos = BlockPos.fromLong(buf.readLong());
            requestId = buf.readInt();
            switch (kind) {
                case RESPONSE_SNAPSHOT: snapshot = ControllerDiagnostics.Snapshot.fromBytes(buf);
                    profileStatus = buf.readByte();
                    if (profileStatus == ControllerDiagnostics.PROFILE_OWN_ACTIVE) {
                        profileRequestId = buf.readInt();samples = buf.readInt();
                    }
                    break;
                case RESPONSE_PROGRESS: samples = buf.readInt(); break;
                case RESPONSE_RESULT: result = ControllerDiagnostics.Result.fromBytes(buf); break;
                case RESPONSE_BUSY:
                case RESPONSE_ERROR: message = ByteBufUtils.readUTF8String(buf); break;
                case RESPONSE_STARTED: break;
                default: throw new IllegalArgumentException("Invalid Diagnostics response: " + kind);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            buf.writeInt(dimension);
            buf.writeLong(controllerPos.toLong());
            buf.writeInt(requestId);
            switch (kind) {
                case RESPONSE_SNAPSHOT:
                    snapshot.toBytes(buf);
                    buf.writeByte(profileStatus);
                    if (profileStatus == ControllerDiagnostics.PROFILE_OWN_ACTIVE) {
                        buf.writeInt(profileRequestId);
                        buf.writeInt(samples);
                    }
                    break;
                case RESPONSE_PROGRESS: buf.writeInt(samples); break;
                case RESPONSE_RESULT: result.toBytes(buf); break;
                case RESPONSE_BUSY:
                case RESPONSE_ERROR: ByteBufUtils.writeUTF8String(buf, message); break;
                case RESPONSE_STARTED: break;
                default: throw new IllegalArgumentException("Invalid Diagnostics response: " + kind);
            }
        }

        public static final class Handler implements IMessageHandler<Response, IMessage> {
            @Override
            public IMessage onMessage(Response message, MessageContext ctx) {
                Minecraft minecraft = Minecraft.getMinecraft();
                NetHandlerPlayClient connection = ctx.getClientHandler();
                minecraft.addScheduledTask(() -> {
                    if (minecraft.getConnection() != connection) {return;}
                    try {
                        ControllerDiagnosticsSessionStore.receive(message);
                    } catch (Throwable throwable) {
                        rethrowFatal(throwable);
                        LOGGER.error("Could not retain Controller profiler response", throwable);
                    }
                    if (!(minecraft.currentScreen instanceof Receiver)) {return;}
                    try {
                        ((Receiver) minecraft.currentScreen).xnetadditions$receiveDiagnostics(message);
                    } catch (Throwable throwable) {
                        rethrowFatal(throwable);
                    }
                });
                return null;
            }
        }
    }
}
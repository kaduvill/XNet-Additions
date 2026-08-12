package xnet.additions.powertools.health.network;

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
import xnet.additions.powertools.health.HealthFinding;
import xnet.additions.powertools.health.HealthScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HealthNetwork {
    private static final Logger LOGGER = LogManager.getLogger(HealthNetwork.class);
    public static final byte RESPONSE_RESULT = 0;
    public static final byte RESPONSE_ERROR = 1;
    private static final int MAX_FINDINGS = 4096;
    private static final double MAX_CONTROLLER_DISTANCE_SQ = 64.0D;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("xnetaddhealth");

    private HealthNetwork() {}

    public interface Receiver {
        void xnetadditions$receiveHealth(Response response);
    }

    public static void init() {
        CHANNEL.registerMessage(Request.Handler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(Response.Handler.class, Response.class, 1, Side.CLIENT);
    }

    private static boolean send(EntityPlayerMP player, Response response) {
        try {
            if (player == null || player.isDead || player.getServer() == null
                    || player.getServer().getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) {
                return false;
            }
            CHANNEL.sendTo(response, player);
            return true;
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            return false;
        }
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof ThreadDeath) {throw (ThreadDeath) throwable;}
        if (throwable instanceof VirtualMachineError) {throw (VirtualMachineError) throwable;}
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
                player.getServerWorld().addScheduledTask(() -> handle(player, message));
                return null;
            }
        }

        private static void handle(EntityPlayerMP player, Request request) {
            WorldServer world = player.getServerWorld();
            int dimension = world.provider.getDimension();
            if (player.world != world || player.getDistanceSqToCenter(request.controllerPos) > MAX_CONTROLLER_DISTANCE_SQ
                    || !world.isBlockLoaded(request.controllerPos)) {
                send(player, Response.error(dimension, request.controllerPos, request.requestId, "Controller is unavailable"));
                return;
            }

            TileEntity tile = world.getTileEntity(request.controllerPos);
            if (!(tile instanceof TileEntityController) || tile.getWorld() != world) {
                send(player, Response.error(dimension, request.controllerPos, request.requestId, "Controller is unavailable"));
                return;
            }

            try {
                TileEntityController controller = (TileEntityController) tile;
                List<HealthFinding> findings = HealthScanner.scan(controller);
                if (findings.size() > MAX_FINDINGS) {
                    send(player, Response.error(dimension, request.controllerPos, request.requestId, "Health scan produced too many findings"));
                    return;
                }
                send(player, Response.result(controller, request.requestId, findings));
            } catch (Throwable throwable) {
                rethrowFatal(throwable);
                LOGGER.error("Health scan failed for " + player.getName() + " at " + request.controllerPos, throwable);
                send(player, Response.error(dimension, request.controllerPos, request.requestId, "Health scan failed safely"));
            }
        }
    }

    public static final class Response implements IMessage {
        private byte kind;
        private int dimension;
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private int requestId;
        private String message = "";
        private List<HealthFinding> findings = Collections.emptyList();

        public Response() {}

        private static Response result(TileEntityController controller, int requestId, List<HealthFinding> findings) {
            Response response = new Response();
            response.kind = RESPONSE_RESULT;
            response.dimension = controller.getWorld().provider.getDimension();
            response.controllerPos = controller.getPos();
            response.requestId = requestId;
            response.findings = findings;
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
        public String getMessage() {return message;}
        public List<HealthFinding> getFindings() {return findings;}

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            dimension = buf.readInt();
            controllerPos = BlockPos.fromLong(buf.readLong());
            requestId = buf.readInt();
            if (kind == RESPONSE_RESULT) {
                int count = buf.readInt();
                if (count < 0 || count > MAX_FINDINGS) {throw new IllegalArgumentException("Invalid Health finding count: " + count);}
                findings = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    findings.add(HealthFinding.fromBytes(buf));
                }
            } else if (kind == RESPONSE_ERROR) {
                message = ByteBufUtils.readUTF8String(buf);
            } else {
                throw new IllegalArgumentException("Invalid Health response: " + kind);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            buf.writeInt(dimension);
            buf.writeLong(controllerPos.toLong());
            buf.writeInt(requestId);
            if (kind == RESPONSE_RESULT) {
                buf.writeInt(findings.size());
                for (HealthFinding finding : findings) {
                    finding.toBytes(buf);
                }
            } else if (kind == RESPONSE_ERROR) {
                ByteBufUtils.writeUTF8String(buf, message);
            } else {
                throw new IllegalArgumentException("Invalid Health response: " + kind);
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
                        ((Receiver) minecraft.currentScreen).xnetadditions$receiveHealth(message);
                    } catch (Throwable throwable) {
                        rethrowFatal(throwable);
                    }
                });
                return null;
            }
        }
    }
}
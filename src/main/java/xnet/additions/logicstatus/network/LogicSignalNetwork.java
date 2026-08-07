package xnet.additions.logicstatus.network;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.logic.ChannelInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import xnet.additions.logicstatus.client.LogicSignalStatusReceiver;

public final class LogicSignalNetwork {

    private static final double MAX_CONTROLLER_DISTANCE_SQ = 64.0D;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("xnetadditionslogic");

    private LogicSignalNetwork() {}

    public static void init() {
        CHANNEL.registerMessage(Request.Handler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(Response.Handler.class, Response.class, 1, Side.CLIENT);
    }

    public static final class Request implements IMessage {
        private BlockPos controllerPos = BlockPos.ORIGIN;

        public Request() {}
        public Request(BlockPos controllerPos) {this.controllerPos = controllerPos;}

        @Override
        public void fromBytes(ByteBuf buf) {
            controllerPos = BlockPos.fromLong(buf.readLong());
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(controllerPos.toLong());
        }

        public static final class Handler implements IMessageHandler<Request, IMessage> {
            @Override
            public IMessage onMessage(Request message, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().player;
                player.getServerWorld().addScheduledTask(() -> sendStatus(player, message.controllerPos));
                return null;
            }
        }
    }

    public static final class Response implements IMessage {
        private BlockPos controllerPos = BlockPos.ORIGIN;
        private int activeMask;

        public Response() {}
        private Response(BlockPos controllerPos, int activeMask) {this.controllerPos = controllerPos; this.activeMask = activeMask;}

        @Override
        public void fromBytes(ByteBuf buf) {
            controllerPos = BlockPos.fromLong(buf.readLong());
            activeMask = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(controllerPos.toLong());
            buf.writeInt(activeMask);
        }

        public static final class Handler implements IMessageHandler<Response, IMessage> {
            @Override
            public IMessage onMessage(Response message, MessageContext ctx) {
                Minecraft minecraft = Minecraft.getMinecraft();
                minecraft.addScheduledTask(() -> {
                    if (minecraft.currentScreen instanceof LogicSignalStatusReceiver) {
                        ((LogicSignalStatusReceiver) minecraft.currentScreen)
                                .xnetadditions$setActiveSignalMask(message.controllerPos, message.activeMask);
                    }
                });
                return null;
            }
        }
    }

    private static void sendStatus(EntityPlayerMP player, BlockPos controllerPos) {
        if (player.getDistanceSqToCenter(controllerPos) > MAX_CONTROLLER_DISTANCE_SQ
                || !player.world.isBlockLoaded(controllerPos)) {return;}
        TileEntity tile = player.world.getTileEntity(controllerPos);
        if (!(tile instanceof TileEntityController)) {return;}
        int activeMask = 0;
        for (ChannelInfo channel : ((TileEntityController) tile).getChannels()) {
            if (channel != null && channel.isEnabled()) {
                activeMask |= channel.getChannelSettings().getColors();
            }
        }
        CHANNEL.sendTo(new Response(controllerPos, activeMask), player);
    }
}
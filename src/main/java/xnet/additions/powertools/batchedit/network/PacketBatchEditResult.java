package xnet.additions.powertools.batchedit.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public final class PacketBatchEditResult implements IMessage {

    private static final int MAX_MESSAGE_LENGTH = 256;

    private BlockPos controllerPos = BlockPos.ORIGIN;
    private String message = "";

    public PacketBatchEditResult() {
    }

    public PacketBatchEditResult(BlockPos controllerPos, String message) {
        this.controllerPos = controllerPos;
        this.message = message == null ? "" : message;
    }

    public interface Receiver {
        boolean xnetadditions$showBatchResult(BlockPos controllerPos, String message);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        controllerPos = BlockPos.fromLong(buf.readLong());
        message = ByteBufUtils.readUTF8String(buf);
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(controllerPos.toLong());
        String encoded = message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        ByteBufUtils.writeUTF8String(buf, encoded);
    }

    public static final class Handler implements IMessageHandler<PacketBatchEditResult, IMessage> {
        @Override
        public IMessage onMessage(PacketBatchEditResult result, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                boolean shown = mc.currentScreen instanceof Receiver
                        && ((Receiver) mc.currentScreen).xnetadditions$showBatchResult(result.controllerPos, result.message);
                if (!shown && mc.player != null) {
                    mc.player.sendStatusMessage(new TextComponentString(result.message), true);
                }
            });
            return null;
        }
    }
}
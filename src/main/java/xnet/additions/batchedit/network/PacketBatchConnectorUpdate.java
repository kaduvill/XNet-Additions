package xnet.additions.batchedit.network;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.api.keys.SidedPos;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import xnet.additions.batchedit.BatchValueCodec;
import xnet.additions.batchedit.server.BatchConnectorUpdateService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class PacketBatchConnectorUpdate implements IMessage {

    public static final int MAX_TARGETS = 128;
    public static final int MAX_VALUES = BatchValueCodec.MAX_VALUES;

    private BlockPos controllerPos = BlockPos.ORIGIN;
    private int channel = -1;
    private List<SidedPos> targets = Collections.emptyList();
    private NBTTagCompound changedValues = new NBTTagCompound();

    public PacketBatchConnectorUpdate() {
    }

    public PacketBatchConnectorUpdate(BlockPos controllerPos, int channel,
                                      Collection<SidedPos> targets,
                                      Map<String, Object> changedValues) {
        this.controllerPos = controllerPos;
        this.channel = channel;
        this.targets = new ArrayList<>(new LinkedHashSet<>(targets));
        this.changedValues = BatchValueCodec.write(changedValues);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        controllerPos = BlockPos.fromLong(buf.readLong());
        channel = buf.readUnsignedByte();

        int count = buf.readUnsignedByte();
        if (count > MAX_TARGETS) {
            throw new IllegalArgumentException("Too many batch-edit targets: " + count);
        }
        List<SidedPos> decoded = new ArrayList<>(count);
        EnumFacing[] facings = EnumFacing.values();
        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.fromLong(buf.readLong());
            int side = buf.readUnsignedByte();
            if (side >= facings.length) {
                throw new IllegalArgumentException("Invalid connector side: " + side);
            }
            decoded.add(new SidedPos(pos, facings[side]));
        }
        targets = decoded;
        NBTTagCompound tag = ByteBufUtils.readTag(buf);
        changedValues = tag == null ? new NBTTagCompound() : tag;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("Too many batch-edit targets: " + targets.size());
        }
        buf.writeLong(controllerPos.toLong());
        buf.writeByte(channel);
        buf.writeByte(targets.size());
        for (SidedPos target : targets) {
            buf.writeLong(target.getPos().toLong());
            buf.writeByte(target.getSide().ordinal());
        }
        ByteBufUtils.writeTag(buf, changedValues);
    }

    public static final class Handler implements IMessageHandler<PacketBatchConnectorUpdate, IMessage> {
        @Override
        public IMessage onMessage(PacketBatchConnectorUpdate message, MessageContext ctx) {
            ctx.getServerHandler().player.getServerWorld().addScheduledTask(() -> {
                Map<String, Object> changes = BatchValueCodec.read(message.changedValues, MAX_VALUES);
                BatchConnectorUpdateService.apply(
                        ctx.getServerHandler().player,
                        message.controllerPos,
                        message.channel,
                        message.targets,
                        changes
                );
            });
            return null;
        }
    }
}

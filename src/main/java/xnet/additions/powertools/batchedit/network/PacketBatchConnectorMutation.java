package xnet.additions.powertools.batchedit.network;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.api.keys.SidedPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import xnet.additions.powertools.batchedit.server.BatchConnectorMutationService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class PacketBatchConnectorMutation
        implements IMessage {

    public static final int MAX_TARGETS = 128;
    public static final int MAX_JSON_BYTES = 26000;

    public enum Operation {
        CREATE,
        PASTE,
        DELETE,
        APPLY
    }

    private BlockPos controllerPos = BlockPos.ORIGIN;
    private int channel = -1;
    private Operation operation = Operation.CREATE;
    private List<SidedPos> targets = Collections.emptyList();
    private String clipboardJson = "";

    public PacketBatchConnectorMutation() {
    }

    public PacketBatchConnectorMutation(
            BlockPos controllerPos,
            int channel,
            Operation operation,
            Collection<SidedPos> targets,
            String clipboardJson
    ) {
        this.controllerPos = controllerPos;
        this.channel = channel;
        this.operation = operation;
        this.targets = new ArrayList<>(
                new LinkedHashSet<>(targets)
        );
        this.clipboardJson =
                clipboardJson == null ? "" : clipboardJson;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        controllerPos = BlockPos.fromLong(buf.readLong());
        channel = buf.readUnsignedByte();

        int operationOrdinal = buf.readUnsignedByte();
        Operation[] operations = Operation.values();

        if (operationOrdinal >= operations.length) {
            throw new IllegalArgumentException(
                    "Invalid batch mutation operation: "
                            + operationOrdinal
            );
        }

        operation = operations[operationOrdinal];

        int count = buf.readUnsignedByte();

        if (count > MAX_TARGETS) {
            throw new IllegalArgumentException(
                    "Too many batch mutation targets: " + count
            );
        }

        List<SidedPos> decoded = new ArrayList<>(count);
        EnumFacing[] facings = EnumFacing.values();

        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.fromLong(buf.readLong());
            int side = buf.readUnsignedByte();

            if (side >= facings.length) {
                throw new IllegalArgumentException(
                        "Invalid target side: " + side
                );
            }

            decoded.add(new SidedPos(pos, facings[side]));
        }

        targets = decoded;

        int jsonLength = buf.readUnsignedShort();

        if (jsonLength > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(
                    "Batch clipboard data is too large: "
                            + jsonLength
            );
        }

        byte[] jsonBytes = new byte[jsonLength];
        buf.readBytes(jsonBytes);
        clipboardJson =
                new String(jsonBytes, StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException(
                    "Too many batch mutation targets: "
                            + targets.size()
            );
        }

        byte[] jsonBytes =
                clipboardJson.getBytes(StandardCharsets.UTF_8);

        if (jsonBytes.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(
                    "Batch clipboard data is too large: "
                            + jsonBytes.length
            );
        }

        buf.writeLong(controllerPos.toLong());
        buf.writeByte(channel);
        buf.writeByte(operation.ordinal());
        buf.writeByte(targets.size());

        for (SidedPos target : targets) {
            buf.writeLong(target.getPos().toLong());
            buf.writeByte(target.getSide().ordinal());
        }

        buf.writeShort(jsonBytes.length);
        buf.writeBytes(jsonBytes);
    }

    public static final class Handler
            implements IMessageHandler<
            PacketBatchConnectorMutation,
            IMessage
            > {

        @Override
        public IMessage onMessage(
                PacketBatchConnectorMutation message,
                MessageContext ctx
        ) {
            ctx.getServerHandler()
                    .player
                    .getServerWorld()
                    .addScheduledTask(
                            () -> BatchConnectorMutationService.apply(
                                    ctx.getServerHandler().player,
                                    message.controllerPos,
                                    message.channel,
                                    message.operation,
                                    message.targets,
                                    message.clipboardJson
                            )
                    );

            return null;
        }
    }
}
package xnet.additions.powertools.health;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.logic.ChannelInfo;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;

import javax.annotation.Nullable;

public final class HealthFinding {
    public enum Severity {
        ERROR,
        WARN
    }

    private final Severity severity;
    private final int channel;
    @Nullable private final SidedPos connector;
    private final String message;

    private HealthFinding(Severity severity, int channel, @Nullable SidedPos connector, String message) {
        this.severity = severity;
        this.channel = channel;
        this.connector = connector;
        this.message = message;
    }

    public static HealthFinding controller(Severity severity, String message) {
        return new HealthFinding(severity, -1, null, message);
    }

    public static HealthFinding channel(Severity severity, int channel, String message) {
        return new HealthFinding(severity, channel, null, message);
    }

    public static HealthFinding connector(Severity severity, int channel, SidedPos connector, String message) {
        return new HealthFinding(severity, channel, connector, message);
    }

    public Severity getSeverity() {return severity;}
    public int getChannel() {return channel;}
    @Nullable public SidedPos getConnector() {return connector;}
    public String getMessage() {return message;}

    public void toBytes(ByteBuf buf) {
        buf.writeByte(severity.ordinal());
        buf.writeByte(channel);
        buf.writeBoolean(connector != null);
        if (connector != null) {
            buf.writeLong(connector.getPos().toLong());
            buf.writeByte(connector.getSide().ordinal());
        }
        ByteBufUtils.writeUTF8String(buf, message);
    }

    public static HealthFinding fromBytes(ByteBuf buf) {
        int severityIndex = buf.readUnsignedByte();
        if (severityIndex >= Severity.values().length) {throw new IllegalArgumentException("Invalid Health severity: " + severityIndex);}
        int channel = buf.readByte();
        if (channel < -1 || channel >= ChannelInfo.MAX_CHANNELS) {throw new IllegalArgumentException("Invalid Health channel: " + channel);}
        SidedPos connector = null;
        if (buf.readBoolean()) {
            BlockPos pos = BlockPos.fromLong(buf.readLong());
            int side = buf.readUnsignedByte();
            if (side >= EnumFacing.VALUES.length) {throw new IllegalArgumentException("Invalid Health side: " + side);}
            connector = new SidedPos(pos, EnumFacing.VALUES[side]);
        }
        return new HealthFinding(Severity.values()[severityIndex], channel, connector, ByteBufUtils.readUTF8String(buf));
    }
}
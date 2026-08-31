package xnet.additions.powertools.diagnostics;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.clientinfo.ConnectorInfo;
import mcjty.xnet.logic.ChannelInfo;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;

import java.util.Arrays;
import java.util.Map;

public final class ControllerDiagnostics {

    public static final int PROFILE_TICKS = 1200;
    public static final int CHANNELS = ChannelInfo.MAX_CHANNELS;
    public static final byte PROFILE_IDLE = 0;
    public static final byte PROFILE_OWN_ACTIVE = 1;
    public static final byte PROFILE_BUSY_OTHER = 2;
    public static final int[] TIMINGS = {1, 2, 4, 5, 10, 20, 40, 60, 100, 200, 600, 1200};

    private ControllerDiagnostics() {}

    public interface Access {
        boolean xnetadditions$startProfile(EntityPlayerMP player, int requestId);
        ProfileStatus xnetadditions$getProfileStatus(EntityPlayerMP player);
    }

    public static final class ProfileStatus {
        public final byte state;
        public final int requestId;
        public final int samples;

        public ProfileStatus(byte state, int requestId, int samples) {
            this.state = state;
            this.requestId = requestId;
            this.samples = samples;
        }
    }

    public static final class Snapshot {
        public int presentChannels;
        public int enabledChannels;
        public int configuredConnectors;
        public int advancedConnectors;
        public final boolean[] present = new boolean[CHANNELS];
        public final boolean[] enabled = new boolean[CHANNELS];
        public final String[] typeIds = new String[CHANNELS];
        public final String[] typeNames = new String[CHANNELS];
        public final int[] configured = new int[CHANNELS];
        public final int[] advanced = new int[CHANNELS];
        public final int[] extractors = new int[CHANNELS];
        public final int[] consumers = new int[CHANNELS];
        public final int[] routedConsumers = new int[CHANNELS];
        public final long[] scheduledOperations = new long[CHANNELS];
        public final int[] maxSameTick = new int[CHANNELS];
        public final boolean[] adaptive = new boolean[CHANNELS];
        public final int[][] localTimingCounts = new int[CHANNELS][TIMINGS.length];
        public final int[][] routedTimingCounts = new int[CHANNELS][TIMINGS.length];

        private Snapshot() {
            Arrays.fill(routedConsumers, -1);
            Arrays.fill(typeIds, "");
            Arrays.fill(typeNames, "");
        }

        public static Snapshot capture(TileEntityController controller) {
            Snapshot snapshot = new Snapshot();
            ChannelInfo[] channels = controller.getChannels();
            int[] pressure = new int[PROFILE_TICKS];

            for (int channel = 0; channel < CHANNELS; channel++) {
                ChannelInfo info = channels[channel];
                if (info == null) {continue;}

                snapshot.present[channel] = true;
                snapshot.enabled[channel] = info.isEnabled();
                snapshot.typeIds[channel] = info.getType().getID();
                snapshot.typeNames[channel] = info.getType().getName();
                snapshot.presentChannels++;
                if (info.isEnabled()) {snapshot.enabledChannels++;}

                Arrays.fill(pressure, 0);
                Map<SidedConsumer, IConnectorSettings> local = controller.getConnectors(channel);

                for (Map.Entry<SidedConsumer, ConnectorInfo> entry : info.getConnectors().entrySet()) {
                    ConnectorInfo connector = entry.getValue();
                    IConnectorSettings settings = connector.getConnectorSettings();

                    snapshot.configured[channel]++;
                    snapshot.configuredConnectors++;
                    if (connector.isAdvanced()) {
                        snapshot.advanced[channel]++;
                        snapshot.advancedConnectors++;
                    }

                    NBTTagCompound tag = writeSettings(settings);
                    int mode = getMode(snapshot.typeIds[channel], tag);
                    if (isExtractor(snapshot.typeIds[channel], mode)) {snapshot.extractors[channel]++;}
                    if (isConsumer(snapshot.typeIds[channel], mode)) {snapshot.consumers[channel]++;}

                    if (!local.containsKey(entry.getKey())) {continue;}

                    int speed = getScheduledTiming(snapshot.typeIds[channel], tag, false);
                    if (speed <= 0) {continue;}

                    addTiming(snapshot.localTimingCounts[channel], speed);
                    addPressure(pressure, snapshot.typeIds[channel], entry.getKey().getConsumerId().getId(), speed);

                    if ("advanced.energy".equals(snapshot.typeIds[channel])
                            && tag.getBoolean("adaptive") && speed <= 20) {
                        snapshot.adaptive[channel] = true;
                    }
                }

                Map<SidedConsumer, IConnectorSettings> routed =
                        controller.hasCachedRoutedConnectors(channel) ? controller.getRoutedConnectors(channel) : null;

                if (routed != null) {
                    snapshot.routedConsumers[channel] = 0;

                    for (Map.Entry<SidedConsumer, IConnectorSettings> entry : routed.entrySet()) {
                        if (local.containsKey(entry.getKey())
                                && ignoresDuplicateRoutedConnector(snapshot.typeIds[channel])) {continue;}

                        NBTTagCompound tag = writeSettings(entry.getValue());
                        int mode = getMode(snapshot.typeIds[channel], tag);
                        if (isConsumer(snapshot.typeIds[channel], mode)) {
                            snapshot.routedConsumers[channel]++;
                        }

                        int speed = getScheduledTiming(snapshot.typeIds[channel], tag, true);
                        if (speed <= 0) {continue;}

                        addTiming(snapshot.routedTimingCounts[channel], speed);
                        addPressure(pressure, snapshot.typeIds[channel],
                                entry.getKey().getConsumerId().getId(), speed);

                        if ("advanced.energy".equals(snapshot.typeIds[channel])
                                && tag.getBoolean("adaptive") && speed <= 20) {
                            snapshot.adaptive[channel] = true;
                        }
                    }
                }

                for (int load : pressure) {
                    snapshot.scheduledOperations[channel] += load;
                    if (load > snapshot.maxSameTick[channel]) {
                        snapshot.maxSameTick[channel] = load;
                    }
                }
            }

            return snapshot;
        }


        public void toBytes(ByteBuf buf) {
            buf.writeByte(presentChannels);
            buf.writeByte(enabledChannels);
            buf.writeInt(configuredConnectors);
            buf.writeInt(advancedConnectors);
            for (int i = 0; i < CHANNELS; i++) {
                buf.writeBoolean(present[i]);
                if (!present[i]) {continue;}
                buf.writeBoolean(enabled[i]);
                ByteBufUtils.writeUTF8String(buf, typeIds[i]);
                ByteBufUtils.writeUTF8String(buf, typeNames[i]);
                buf.writeInt(configured[i]);
                buf.writeInt(advanced[i]);
                buf.writeInt(extractors[i]);
                buf.writeInt(consumers[i]);
                buf.writeInt(routedConsumers[i]);
                buf.writeLong(scheduledOperations[i]);
                buf.writeInt(maxSameTick[i]);
                buf.writeBoolean(adaptive[i]);
                for (int j = 0; j < TIMINGS.length; j++) {
                    buf.writeInt(localTimingCounts[i][j]);
                    buf.writeInt(routedTimingCounts[i][j]);
                }
            }
        }

        public static Snapshot fromBytes(ByteBuf buf) {
            Snapshot snapshot = new Snapshot();
            snapshot.presentChannels = buf.readUnsignedByte();
            snapshot.enabledChannels = buf.readUnsignedByte();
            snapshot.configuredConnectors = buf.readInt();
            snapshot.advancedConnectors = buf.readInt();
            for (int i = 0; i < CHANNELS; i++) {
                snapshot.present[i] = buf.readBoolean();
                if (!snapshot.present[i]) {continue;}
                snapshot.enabled[i] = buf.readBoolean();
                snapshot.typeIds[i] = ByteBufUtils.readUTF8String(buf);
                snapshot.typeNames[i] = ByteBufUtils.readUTF8String(buf);
                snapshot.configured[i] = buf.readInt();
                snapshot.advanced[i] = buf.readInt();
                snapshot.extractors[i] = buf.readInt();
                snapshot.consumers[i] = buf.readInt();
                snapshot.routedConsumers[i] = buf.readInt();
                snapshot.scheduledOperations[i] = buf.readLong();
                snapshot.maxSameTick[i] = buf.readInt();
                snapshot.adaptive[i] = buf.readBoolean();
                for (int j = 0; j < TIMINGS.length; j++) {
                    snapshot.localTimingCounts[i][j] = buf.readInt();
                    snapshot.routedTimingCounts[i][j] = buf.readInt();
                }
            }
            return snapshot;
        }
    }

    public static final class Result {
        public final int samples;
        public final long totalNanos;
        public final long peakNanos;
        public final int peakSample;
        public final long[] channelTotals;
        public final long[] channelPeaks;
        public final int[] channelCalls;
        public final long[] peakChannels;

        public Result(int samples, long totalNanos, long peakNanos, int peakSample, long[] channelTotals,
                      long[] channelPeaks, int[] channelCalls, long[] peakChannels) {
            this.samples = samples;
            this.totalNanos = totalNanos;
            this.peakNanos = peakNanos;
            this.peakSample = peakSample;
            this.channelTotals = channelTotals;
            this.channelPeaks = channelPeaks;
            this.channelCalls = channelCalls;
            this.peakChannels = peakChannels;
        }

        public long getCoreNanos() {
            long channels = 0L;
            for (long value : channelTotals) {channels += value;}
            return Math.max(0L, totalNanos - channels);
        }

        public long getPeakCoreNanos() {
            long channels = 0L;
            for (long value : peakChannels) {channels += value;}
            return Math.max(0L, peakNanos - channels);
        }

        public void toBytes(ByteBuf buf) {
            buf.writeInt(samples);
            buf.writeLong(totalNanos);
            buf.writeLong(peakNanos);
            buf.writeInt(peakSample);
            for (long value : channelTotals) {buf.writeLong(value);}
            for (long value : channelPeaks) {buf.writeLong(value);}
            for (int value : channelCalls) {buf.writeInt(value);}
            for (long value : peakChannels) {buf.writeLong(value);}
        }

        public static Result fromBytes(ByteBuf buf) {
            long[] totals = new long[CHANNELS];
            long[] peaks = new long[CHANNELS];
            int[] calls = new int[CHANNELS];
            long[] peakChannels = new long[CHANNELS];
            int samples = buf.readInt();
            long total = buf.readLong();
            long peak = buf.readLong();
            int peakSample = buf.readInt();
            for (int i = 0; i < CHANNELS; i++) {totals[i] = buf.readLong();}
            for (int i = 0; i < CHANNELS; i++) {peaks[i] = buf.readLong();}
            for (int i = 0; i < CHANNELS; i++) {calls[i] = buf.readInt();}
            for (int i = 0; i < CHANNELS; i++) {peakChannels[i] = buf.readLong();}
            return new Result(samples, total, peak, peakSample, totals, peaks, calls, peakChannels);
        }
    }
    private static NBTTagCompound writeSettings(IConnectorSettings settings) {
        NBTTagCompound tag = new NBTTagCompound();
        if (settings != null) {settings.writeToNBT(tag);}
        return tag;
    }
    private static int getMode(String typeId, NBTTagCompound tag) {
        switch (typeId) {
            case "xnet.item": return tag.getByte("itemMode");
            case "xnet.fluid": return tag.getByte("fluidMode");
            case "xnet.logic": return tag.getByte("logicMode");
            case "xnet.energy": return tag.getByte("itemMode");
            case "advanced.energy": return tag.getByte("energyMode");
            case "mekanism.gas": return tag.getByte("mode");
            case "botania.mana": return tag.getByte("manaMode");
            case "tc.essentia": return tag.getByte("essentiaMode");
            case "ic2.eu": return tag.getByte("euMode");
            default: return -1;
        }
    }

    private static boolean isExtractor(String typeId, int mode) {
        return mode >= 0 && ("xnet.logic".equals(typeId) ? mode == 0 : mode == 1);
    }

    private static boolean isConsumer(String typeId, int mode) {
        return mode >= 0 && ("xnet.logic".equals(typeId) ? mode == 1 : mode == 0);
    }

    private static boolean isScheduled(String typeId, int mode, boolean routed) {
        if (routed) {
            return "xnet.logic".equals(typeId)
                    ? mode == 1
                    : "advanced.energy".equals(typeId) && mode == 0;
        }

        if ("xnet.logic".equals(typeId)) {return mode == 0 || mode == 1;}
        if ("advanced.energy".equals(typeId)) {return mode == 0;}
        return isExtractor(typeId, mode);
    }

    public static boolean hasRoutedTiming(String typeId) {
        return "xnet.logic".equals(typeId) || "advanced.energy".equals(typeId);
    }

    public static int getScheduledTiming(String typeId, IConnectorSettings settings, boolean routed) {
        return getScheduledTiming(typeId, writeSettings(settings), routed);
    }

    public static String getModeLabel(String typeId, IConnectorSettings settings) {
        int mode = getMode(typeId, writeSettings(settings));
        if (mode < 0) {return "";}
        return "xnet.logic".equals(typeId)
                ? (mode == 0 ? "SENSOR" : "OUTPUT")
                : (mode == 0 ? "INS" : "EXT");
    }

    private static int getScheduledTiming(String typeId, NBTTagCompound tag, boolean routed) {
        int mode = getMode(typeId, tag);
        return isScheduled(typeId, mode, routed) ? getPhysicalSpeed(typeId, tag) : 0;
    }

    private static boolean ignoresDuplicateRoutedConnector(String typeId) {
        return "advanced.energy".equals(typeId)
                || "mekanism.gas".equals(typeId)
                || "botania.mana".equals(typeId)
                || "tc.essentia".equals(typeId)
                || "ic2.eu".equals(typeId);
    }

    private static int getPhysicalSpeed(String typeId, NBTTagCompound tag) {
        if ("xnet.energy".equals(typeId) || "ic2.eu".equals(typeId)) {return 1;}

        int speed;
        switch (typeId) {
            case "xnet.item": speed = tag.getInteger("spd") * 5; break;
            case "xnet.fluid": speed = tag.getInteger("speed") * 10; break;
            case "xnet.logic": speed = tag.getInteger("speed") * 5; break;
            case "advanced.energy": speed = tag.getInteger("speed"); break;
            case "mekanism.gas": speed = tag.getInteger("speed") * 10; break;
            case "botania.mana": speed = tag.getInteger("speed") * 10; break;
            case "tc.essentia": speed = tag.getInteger("speed") * 10; break;
            default: return 0;
        }

        return speed > 0
                && PROFILE_TICKS % speed == 0
                && timingIndex(speed) >= 0 ? speed : 0;
    }

    private static void addPressure(int[] pressure, String typeId, int consumerId, int speed) {
        // TODO: Revisit native channel phase modeling when XNet gets proper shared stagger support.
        if ("xnet.energy".equals(typeId) || "ic2.eu".equals(typeId)) {
            for (int i = 0; i < PROFILE_TICKS; i++) {pressure[i]++;}
            return;
        }
        if ("advanced.energy".equals(typeId)) {
            int phase = Math.floorMod(consumerId, speed);
            for (int i = 0; i < PROFILE_TICKS; i++) {
                if (i % speed == phase) {pressure[i]++;}
            }
            return;
        }
        if ("xnet.fluid".equals(typeId) || "mekanism.gas".equals(typeId)) {
            int internalSpeed = speed / 10;
            int phase = Math.floorMod(consumerId, internalSpeed);
            for (int i = 0; i < PROFILE_TICKS; i++) {
                int remaining = PROFILE_TICKS - i;
                if (remaining % 10 == 0 && (remaining / 10) % internalSpeed == phase) {pressure[i]++;}
            }
            return;
        }
        for (int i = 0; i < PROFILE_TICKS; i++) {
            if ((PROFILE_TICKS - i) % speed == 0) {pressure[i]++;}
        }
    }

    private static void addTiming(int[] counts, int speed) {
        int index = timingIndex(speed);
        if (index >= 0) {counts[index]++;}
    }

    private static int timingIndex(int speed) {
        for (int i = 0; i < TIMINGS.length; i++) {
            if (TIMINGS[i] == speed) {return i;}
        }
        return -1;
    }
}
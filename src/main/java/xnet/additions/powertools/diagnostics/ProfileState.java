package xnet.additions.powertools.diagnostics;

import mcjty.xnet.logic.ChannelInfo;
import net.minecraft.entity.player.EntityPlayerMP;

public final class ProfileState {

    public final EntityPlayerMP player;
    public final int requestId;
    public int samples;
    public long totalNanos;
    public long peakNanos;
    public int peakSample;
    public final ChannelInfo[] channels = new ChannelInfo[ControllerDiagnostics.CHANNELS];
    public final long[] currentChannels = new long[ControllerDiagnostics.CHANNELS];
    public final long[] channelTotals = new long[ControllerDiagnostics.CHANNELS];
    public final long[] channelPeaks = new long[ControllerDiagnostics.CHANNELS];
    public final int[] channelCalls = new int[ControllerDiagnostics.CHANNELS];
    public final long[] peakChannels = new long[ControllerDiagnostics.CHANNELS];

    public ProfileState(EntityPlayerMP player, int requestId, ChannelInfo[] channels) {
        this.player = player;
        this.requestId = requestId;
        System.arraycopy(channels, 0, this.channels, 0, ControllerDiagnostics.CHANNELS);
    }
}
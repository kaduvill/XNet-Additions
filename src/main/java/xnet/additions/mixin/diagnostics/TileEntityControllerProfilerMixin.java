package xnet.additions.mixin.diagnostics;

import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.config.ConfigSetup;
import mcjty.xnet.logic.ChannelInfo;
import mcjty.xnet.multiblock.WorldBlob;
import mcjty.xnet.multiblock.XNetBlobData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xnet.additions.powertools.diagnostics.ControllerDiagnostics;
import xnet.additions.powertools.diagnostics.ProfileState;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;

import javax.annotation.Nullable;
import java.util.Map;

import static mcjty.xnet.logic.ChannelInfo.MAX_CHANNELS;

@Mixin(value = TileEntityController.class, remap = false)
public abstract class TileEntityControllerProfilerMixin implements ControllerDiagnostics.Access {

    @Shadow(remap = false) @Final private ChannelInfo[] channels;
    @Shadow(remap = false) private int colors;
    @Shadow(remap = false) private Map<SidedConsumer, IConnectorSettings>[] cachedRoutedConnectors;
    @Shadow(remap = false) private void checkNetwork(WorldBlob worldBlob) {throw new AssertionError();}

    @Unique private ProfileState xnetadditions$profile;

    @Override
    @Unique
    public boolean xnetadditions$startProfile(EntityPlayerMP player, int requestId) {
        TileEntityController controller = (TileEntityController) (Object) this;
        if (controller.getWorld().isRemote || player == null || player.isDead || player.world != controller.getWorld()
                || xnetadditions$profile != null) {return false;}
        xnetadditions$profile = new ProfileState(player, requestId, channels);
        return true;
    }

    @Override
    @Unique
    @Nullable
    public Map<SidedConsumer, IConnectorSettings> xnetadditions$peekRoutedConnectors(int channel) {
        return channel >= 0 && channel < MAX_CHANNELS ? cachedRoutedConnectors[channel] : null;
    }

    @Redirect(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;isRemote:Z", ordinal = 0), remap = true)
    private boolean xnetadditions$profileOrRemote(World world) {
        ProfileState profile = xnetadditions$profile;
        if (profile == null) {return world.isRemote;}
        TileEntityController controller = (TileEntityController) (Object) this;
        if (world.isRemote) {
            xnetadditions$profile = null;
            return true;
        }
        if (profile.player.isDead || profile.player.world != world) {
            xnetadditions$profile = null;
            return false;
        }
        for (int i = 0; i < MAX_CHANNELS; i++) {
            if (profile.channels[i] != channels[i]) {
                xnetadditions$profile = null;
                DiagnosticsNetwork.sendError(profile.player, controller, profile.requestId, "Profile stopped: channel layout changed");
                return false;
            }
        }
        for (int i = 0; i < MAX_CHANNELS; i++) {profile.currentChannels[i] = 0L;}
        int calledMask = 0;
        long controllerStart = System.nanoTime();
        try {
            WorldBlob worldBlob = XNetBlobData.getBlobData(world).getWorldBlob(world);
            if (worldBlob.getNetworksAt(controller.getPos()).size() > 1) {
                controller.markDirtyClient();
                xnetadditions$finishProfile(profile, System.nanoTime() - controllerStart, calledMask);
                return true;
            }
            checkNetwork(worldBlob);
            if (!controller.checkAndConsumeRF(ConfigSetup.controllerRFT.get())) {
                xnetadditions$finishProfile(profile, System.nanoTime() - controllerStart, calledMask);
                return true;
            }
            boolean dirty = false;
            int newcolors = 0;
            for (int i = 0; i < MAX_CHANNELS; i++) {
                if (channels[i] != null && channels[i].isEnabled()) {
                    if (controller.checkAndConsumeRF(ConfigSetup.controllerChannelRFT.get())) {
                        IChannelSettings settings = channels[i].getChannelSettings();
                        long channelStart = System.nanoTime();
                        settings.tick(i, controller);
                        profile.currentChannels[i] = System.nanoTime() - channelStart;
                        calledMask |= 1 << i;
                    }
                    newcolors |= channels[i].getChannelSettings().getColors();
                    dirty = true;
                }
            }
            if (newcolors != colors) {
                dirty = true;
                colors = newcolors;
            }
            if (dirty) {controller.markDirtyQuick();}
            xnetadditions$finishProfile(profile, System.nanoTime() - controllerStart, calledMask);
        } catch (Throwable throwable) {
            xnetadditions$rethrowFatal(throwable);
            xnetadditions$profile = null;
            DiagnosticsNetwork.sendError(profile.player, controller, profile.requestId,
                    "Profile stopped safely: " + throwable.getClass().getSimpleName());
        }
        return true;
    }

    @Unique
    private void xnetadditions$finishProfile(ProfileState profile, long elapsed, int calledMask) {
        profile.samples++;
        profile.totalNanos += elapsed;
        for (int i = 0; i < MAX_CHANNELS; i++) {
            if ((calledMask & (1 << i)) == 0) {continue;}
            long channelNanos = profile.currentChannels[i];
            profile.channelTotals[i] += channelNanos;
            profile.channelCalls[i]++;
            if (channelNanos > profile.channelPeaks[i]) {profile.channelPeaks[i] = channelNanos;}
        }
        if (elapsed > profile.peakNanos) {
            profile.peakNanos = elapsed;
            profile.peakSample = profile.samples;
            System.arraycopy(profile.currentChannels, 0, profile.peakChannels, 0, MAX_CHANNELS);
        }
        TileEntityController controller = (TileEntityController) (Object) this;
        if (profile.samples >= ControllerDiagnostics.PROFILE_TICKS) {
            xnetadditions$profile = null;
            DiagnosticsNetwork.sendResult(profile.player, controller, profile.requestId,
                    new ControllerDiagnostics.Result(profile.samples, profile.totalNanos, profile.peakNanos,
                            profile.peakSample, profile.channelTotals, profile.channelPeaks,
                            profile.channelCalls, profile.peakChannels));
        } else if (profile.samples % 40 == 0
                && !DiagnosticsNetwork.sendProgress(profile.player, controller, profile.requestId, profile.samples)) {
            xnetadditions$profile = null;
        }
    }

    @Unique
    private static void xnetadditions$rethrowFatal(Throwable throwable) {
        if (throwable instanceof ThreadDeath) {throw (ThreadDeath) throwable;}
        if (throwable instanceof VirtualMachineError) {throw (VirtualMachineError) throwable;}
    }
}
package xnet.additions.channel.advancedenergy;

import mcjty.xnet.api.IXNet;

public final class AdvancedEnergyCompat {

    private AdvancedEnergyCompat() {
    }

    public static void register(IXNet xNet) {
        xNet.registerChannelType(new AdvancedEnergyChannelType());
    }
}
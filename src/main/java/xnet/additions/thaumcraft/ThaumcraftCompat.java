package xnet.additions.thaumcraft;

import mcjty.xnet.api.IXNet;
import thaumcraft.api.aspects.IAspectContainer;
import xnet.additions.util.ConnectableAdapter;

public final class ThaumcraftCompat {

    private ThaumcraftCompat() {
    }

    public static void register(IXNet xNet, ConnectableAdapter connectableAdapter) {
        xNet.registerChannelType(new EssentiaChannelType());
        connectableAdapter.addType(IAspectContainer.class);
    }
}
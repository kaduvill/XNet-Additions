package xnet.additions.mekanism;

import mcjty.xnet.api.IXNet;
import mekanism.api.gas.IGasHandler;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import xnet.additions.util.ConnectableAdapter;

public final class MekanismCompat {

    @CapabilityInject(IGasHandler.class)
    private static Capability<IGasHandler> capMekGasHandler;

    private MekanismCompat() {
    }

    public static void register(IXNet xNet, ConnectableAdapter connectableAdapter) {
        xNet.registerChannelType(new GasChannelType());

        if (capMekGasHandler != null) {
            connectableAdapter.addCapability(capMekGasHandler);
        }

        connectableAdapter.addType(IGasHandler.class);
    }
}
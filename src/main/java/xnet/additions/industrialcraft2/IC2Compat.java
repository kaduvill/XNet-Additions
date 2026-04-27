package xnet.additions.industrialcraft2;

import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import mcjty.xnet.api.IXNet;
import xnet.additions.util.ConnectableAdapter;

public final class IC2Compat {
    private IC2Compat() {}

    public static void register(IXNet xNet, ConnectableAdapter adapter) {
        xNet.registerChannelType(new EUChannelType());

        adapter.addType(IEnergyTile.class);
        adapter.addType(IEnergySink.class);
        adapter.addType(IEnergySource.class);
    }
}
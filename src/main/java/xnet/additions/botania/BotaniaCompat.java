package xnet.additions.botania;

import mcjty.xnet.api.IXNet;
import vazkii.botania.api.mana.IManaReceiver;
import vazkii.botania.api.mana.spark.ISparkAttachable;
import xnet.additions.util.ConnectableAdapter;

public final class BotaniaCompat {

    private BotaniaCompat() {
    }

    public static void register(IXNet xNet, ConnectableAdapter connectableAdapter) {
        xNet.registerChannelType(new ManaChannelType());

        // Broad mana receiver contract
        connectableAdapter.addType(IManaReceiver.class);

        // Spark attachables like pools / spark-aware mana holders
        connectableAdapter.addType(ISparkAttachable.class);
    }
}
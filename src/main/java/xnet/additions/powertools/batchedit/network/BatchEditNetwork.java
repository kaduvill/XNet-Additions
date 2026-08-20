package xnet.additions.powertools.batchedit.network;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class BatchEditNetwork {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel("xnetadditions");

    private BatchEditNetwork() {}
    public static void init() {int id = 0;

        CHANNEL.registerMessage(PacketBatchConnectorUpdate.Handler.class,
                PacketBatchConnectorUpdate.class, id++, Side.SERVER);
        CHANNEL.registerMessage(PacketBatchConnectorMutation.Handler.class,
                PacketBatchConnectorMutation.class, id++, Side.SERVER);
        CHANNEL.registerMessage(PacketBatchEditResult.Handler.class,
                PacketBatchEditResult.class, id, Side.CLIENT);
    }
}

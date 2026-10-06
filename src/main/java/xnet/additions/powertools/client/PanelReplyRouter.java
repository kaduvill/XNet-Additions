package xnet.additions.powertools.client;

import mcjty.xnet.blocks.controller.TileEntityController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import xnet.additions.XNetAdditions;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Pending replies only; all access runs on the client thread, without polling. */
@Mod.EventBusSubscriber(modid = XNetAdditions.MODID, value = Side.CLIENT)
public final class PanelReplyRouter {
    private static final Map<Integer, Pending<?, ?>> PENDING = new HashMap<>();
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
    private static int nextRequestId;

    private PanelReplyRouter() {}

    // Use an unbound method reference so the callback does not retain the panel.
    public static <T, R> int register(TileEntityController controller, T owner,
                                     Class<R> responseType, BiConsumer<T, R> receiver) {
        discardCollectedOwners();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null || controller.getWorld() != minecraft.world || minecraft.getConnection() == null) {return 0;}
        int id = nextRequestId();
        PENDING.put(id, new Pending<>(id, owner, minecraft.world, minecraft.getConnection(),
                controller.getPos().toImmutable(), responseType, receiver));
        return id;
    }

    public static int nextRequestId() {
        int id;
        do {
            id = ++nextRequestId;
        } while (id == 0 || PENDING.containsKey(id));
        return id;
    }

    public static void cancel(int requestId) {
        PENDING.remove(requestId);
        discardCollectedOwners();
    }

    public static boolean receive(int requestId, BlockPos controllerPos, Object response, NetHandlerPlayClient connection) {
        discardCollectedOwners();
        Pending<?, ?> pending = PENDING.get(requestId);
        if (pending == null || !pending.responseType.isInstance(response) || !pending.controllerPos.equals(controllerPos)) {return false;}
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.getConnection() != connection || pending.connection.get() != connection
                || minecraft.world == null || pending.world.get() != minecraft.world) {return false;}
        PENDING.remove(requestId);
        pending.receive(response);
        return true;
    }

    private static void discardCollectedOwners() {
        Pending<?, ?> pending;
        while ((pending = (Pending<?, ?>) COLLECTED.poll()) != null) {
            PENDING.remove(pending.requestId, pending);
        }
    }

    @SubscribeEvent
    public static void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(PENDING::clear);
    }

    @SubscribeEvent
    public static void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(PENDING::clear);
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote) {
            Minecraft.getMinecraft().addScheduledTask(() -> PENDING.values().removeIf(pending -> pending.world.get() == world));
        }
    }

    private static final class Pending<T, R> extends WeakReference<T> {
        private final int requestId;
        private final WeakReference<World> world;
        private final WeakReference<NetHandlerPlayClient> connection;
        private final BlockPos controllerPos;
        private final Class<R> responseType;
        private final BiConsumer<T, R> receiver;

        private Pending(int requestId, T owner, World world, NetHandlerPlayClient connection,
                        BlockPos controllerPos, Class<R> responseType, BiConsumer<T, R> receiver) {
            super(owner, COLLECTED);
            this.requestId = requestId;
            this.world = new WeakReference<>(world);
            this.connection = new WeakReference<>(connection);
            this.controllerPos = controllerPos;
            this.responseType = responseType;
            this.receiver = receiver;
        }

        private void receive(Object response) {
            T owner = get();
            if (owner != null) {receiver.accept(owner, responseType.cast(response));}
        }
    }
}

package xnet.additions.powertools.diagnostics.client;

import mcjty.xnet.blocks.controller.TileEntityController;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import xnet.additions.XNetAdditions;
import xnet.additions.powertools.diagnostics.ControllerDiagnostics;
import xnet.additions.powertools.diagnostics.network.DiagnosticsNetwork;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = XNetAdditions.MODID, value = Side.CLIENT)
public final class ControllerDiagnosticsSessionStore {

    private static final Map<ControllerKey, Session> SESSIONS = new HashMap<>();

    private ControllerDiagnosticsSessionStore() {}

    @Nullable
    static Session get(TileEntityController controller) {
        return controller.getWorld() == null ? null : SESSIONS.get(new ControllerKey(
                controller.getWorld().provider.getDimension(), controller.getPos()));
    }

    static void begin(TileEntityController controller, int requestId) {
        if (controller.getWorld() == null) {return;}
        Session session = SESSIONS.computeIfAbsent(new ControllerKey(
                controller.getWorld().provider.getDimension(), controller.getPos()), ignored -> new Session());
        session.requestId = requestId;
        session.pending = true;
        session.profiling = false;
        session.busy = false;
        session.completed = false;
        session.progress = 0;
        session.status = "Starting server profiler...";
    }

    static void failed(TileEntityController controller, int requestId, String message) {
        Session session = get(controller);
        if (session == null || session.requestId != requestId) {return;}
        session.pending = false;
        session.profiling = false;
        session.busy = false;
        session.completed = false;
        session.status = message;
    }

    public static void receive(DiagnosticsNetwork.Response response) {
        ControllerKey key = new ControllerKey(response.getDimension(), response.getControllerPos());
        if (response.getKind() == DiagnosticsNetwork.RESPONSE_SNAPSHOT) {
            reconcile(key, response);
            return;
        }
        Session session = SESSIONS.get(key);
        if (session == null) {
            if (response.getKind() != DiagnosticsNetwork.RESPONSE_STARTED
                    && response.getKind() != DiagnosticsNetwork.RESPONSE_PROGRESS
                    && response.getKind() != DiagnosticsNetwork.RESPONSE_RESULT) {return;}
            session = new Session();
            session.requestId = response.getRequestId();
            SESSIONS.put(key, session);
        }
        if (session.requestId != response.getRequestId()) {return;}
        switch (response.getKind()) {
            case DiagnosticsNetwork.RESPONSE_STARTED:
                session.pending = false;
                session.profiling = true;
                session.busy = false;
                session.completed = false;
                session.progress = 0;
                session.status = "Server profiling active";
                break;
            case DiagnosticsNetwork.RESPONSE_PROGRESS:
                session.pending = false;
                session.profiling = true;
                session.busy = false;
                session.completed = false;
                session.progress = response.getSamples();
                session.status = "Server profiling active";
                break;
            case DiagnosticsNetwork.RESPONSE_RESULT:
                session.pending = false;
                session.profiling = false;
                session.busy = false;
                session.progress = ControllerDiagnostics.PROFILE_TICKS;
                session.status = "";
                if (!session.completed) {
                    session.previousResult = session.currentResult;
                    session.currentResult = response.getResult();
                }
                session.completed = true;
                break;
            case DiagnosticsNetwork.RESPONSE_BUSY:
            case DiagnosticsNetwork.RESPONSE_ERROR:
                session.pending = false;
                session.profiling = false;
                session.busy = response.getKind() == DiagnosticsNetwork.RESPONSE_BUSY;
                session.completed = false;
                session.status = response.getMessage();
                break;
            default:
        }
    }

    private static void reconcile(ControllerKey key, DiagnosticsNetwork.Response response) {
        Session session = SESSIONS.get(key);
        if (response.getProfileStatus() == ControllerDiagnostics.PROFILE_OWN_ACTIVE) {
            if (session == null) {
                session = new Session();
                SESSIONS.put(key, session);
            }
            session.requestId = response.getProfileRequestId();
            session.pending = false;
            session.profiling = true;
            session.busy = false;
            session.completed = false;
            session.progress = response.getSamples();
            session.status = "Server profiling active";
        } else if (response.getProfileStatus() == ControllerDiagnostics.PROFILE_BUSY_OTHER) {
            if (session == null) {
                session = new Session();
                SESSIONS.put(key, session);
            }
            session.pending = false;
            session.profiling = false;
            session.busy = true;
            session.completed = false;
            session.status = "This Controller is already being profiled";
        } else if (session != null && (session.pending || session.profiling || session.busy)) {
            session.pending = false;
            session.profiling = false;
            session.busy = false;
            session.completed = false;
            session.status = session.currentResult == null ? "Profile stopped before completion" : "";
        }
    }

    @SubscribeEvent
    public static void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(SESSIONS::clear);
    }

    @SubscribeEvent
    public static void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(SESSIONS::clear);
    }

    static final class Session {
        int requestId;
        boolean pending;
        boolean profiling;
        boolean busy;
        boolean completed;
        int progress;
        String status = "";
        ControllerDiagnostics.Result currentResult;
        ControllerDiagnostics.Result previousResult;
    }

    private static final class ControllerKey {
        private final int dimension;
        private final long position;

        private ControllerKey(int dimension, BlockPos position) {
            this.dimension = dimension;
            this.position = position.toLong();
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {return true;}
            if (!(object instanceof ControllerKey)) {return false;}
            ControllerKey key = (ControllerKey) object;
            return dimension == key.dimension && position == key.position;
        }

        @Override
        public int hashCode() {
            return 31 * dimension + Long.hashCode(position);
        }
    }
}
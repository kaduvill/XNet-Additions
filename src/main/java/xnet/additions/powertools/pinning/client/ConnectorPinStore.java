package xnet.additions.powertools.pinning.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.controller.TileEntityController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Client-local connector pins. Never stored on or synchronized by the server. */
public final class ConnectorPinStore {
    private static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogManager.getLogger("XNetAdditions-Pins");
    private static final Map<String, Set<SidedPos>> PINS = new HashMap<>();
    private static boolean loaded;

    private ConnectorPinStore() {}

    public static Set<SidedPos> getPins(TileEntityController controller) {
        ensureLoaded();
        Set<SidedPos> pins = PINS.get(controllerKey(controller));
        return pins == null ? Collections.emptySet() : pins;
    }

    public static boolean togglePin(TileEntityController controller, SidedPos pos) {
        ensureLoaded();
        String key = controllerKey(controller);
        Set<SidedPos> pins = PINS.computeIfAbsent(key, ignored -> new HashSet<>());
        boolean removed = pins.remove(pos);

        if (!removed) pins.add(pos);
        if (pins.isEmpty()) PINS.remove(key);
        if (writeFile()) return true;

        if (removed) {
            PINS.computeIfAbsent(key, ignored -> new HashSet<>()).add(pos);
        } else {
            pins = PINS.get(key);
            if (pins != null) {
                pins.remove(pos);
                if (pins.isEmpty()) PINS.remove(key);
            }
        }
        return false;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = getPath();
        if (!Files.isRegularFile(path)) return;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            if (!root.has("format") || root.get("format").getAsInt() != FORMAT
                    || !root.has("controllers") || !root.get("controllers").isJsonObject()) {
                LOGGER.warn("Ignoring unsupported connector pin file: {}", path);
                return;
            }

            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("controllers").entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                Set<SidedPos> pins = new HashSet<>();

                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (!element.isJsonArray()) continue;
                    JsonArray stored = element.getAsJsonArray();
                    if (stored.size() != 2) continue;

                    try {
                        int side = stored.get(1).getAsInt();
                        if (side >= 0 && side < EnumFacing.VALUES.length) {
                            pins.add(new SidedPos(BlockPos.fromLong(stored.get(0).getAsLong()), EnumFacing.VALUES[side]));
                        }
                    } catch (RuntimeException ignored) {
                    }
                }

                if (!pins.isEmpty()) PINS.put(entry.getKey(), pins);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read connector pins from {}", path, e);
            PINS.clear();
        }
    }

    private static boolean writeFile() {
        Path path = getPath();
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        JsonObject controllers = new JsonObject();

        for (Map.Entry<String, Set<SidedPos>> entry : new TreeMap<>(PINS).entrySet()) {
            JsonArray storedPins = new JsonArray();

            for (SidedPos pin : new TreeSet<>(entry.getValue())) {
                JsonArray stored = new JsonArray();
                stored.add(pin.getPos().toLong());
                stored.add(pin.getSide().ordinal());
                storedPins.add(stored);
            }

            controllers.add(entry.getKey(), storedPins);
        }
        root.add("controllers", controllers);

        try {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not save connector pins to {}", path, e);

            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    private static String controllerKey(TileEntityController controller) {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc.getCurrentServerData();
        String world;

        if (server != null) {
            world = "remote:" + server.serverIP.trim().toLowerCase(Locale.ROOT);
        } else {
            IntegratedServer integrated = mc.getIntegratedServer();
            world = "local:" + (integrated == null
                    ? controller.getWorld().getWorldInfo().getWorldName()
                    : integrated.getFolderName());
        }

        return world + '|' + controller.getWorld().provider.getDimension() + '|' + controller.getPos().toLong();
    }

    private static Path getPath() {
        return Loader.instance().getConfigDir().toPath().resolve("xnetadditions-connector-pins.json");
    }
}
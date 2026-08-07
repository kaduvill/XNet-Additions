package xnet.additions.batchedit.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xnet.additions.batchedit.network.PacketBatchConnectorMutation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Client-local connector presets.
 *
 * Presets are never stored on or synchronized by the server. Applying one
 * uses the existing validated batch mutation packet.
 */
public final class ConnectorPresetStore {

    public static final int SLOT_COUNT = 9;

    private static final int FORMAT = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Logger LOGGER =
            LogManager.getLogger("XNetAdditions-Presets");

    private static final Map<String, JsonObject[]> PRESETS =
            new HashMap<>();

    /*
     * Session-only UI state. Selection is remembered separately for every
     * channel type but is not written to disk.
     */
    private static final Map<String, Integer> SELECTED_SLOTS =
            new HashMap<>();

    private static boolean loaded;
    private static boolean expanded;

    private ConnectorPresetStore() {
    }

    public static boolean isExpanded() {
        return expanded;
    }

    public static void setExpanded(boolean value) {
        expanded = value;
    }

    public static int getSelectedSlot(String typeId) {
        if (typeId == null) {
            return -1;
        }

        Integer slot = SELECTED_SLOTS.get(typeId);
        return slot == null ? -1 : slot;
    }

    public static void setSelectedSlot(String typeId, int slot) {
        if (typeId == null) {
            return;
        }

        if (slot < 0 || slot >= SLOT_COUNT) {
            SELECTED_SLOTS.remove(typeId);
        } else {
            SELECTED_SLOTS.put(typeId, slot);
        }
    }

    public static boolean hasPreset(String typeId, int slot) {
        return getPresetObject(typeId, slot) != null;
    }

    public static int getOccupiedMask(String typeId) {
        ensureLoaded();

        JsonObject[] slots = PRESETS.get(typeId);
        if (slots == null) {
            return 0;
        }

        int mask = 0;

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (slots[slot] != null) {
                mask |= 1 << slot;
            }
        }

        return mask;
    }

    public static String getPresetJson(String typeId, int slot) {
        JsonObject preset = getPresetObject(typeId, slot);

        return preset == null
                ? null
                : GSON.toJson(preset);
    }

    public static boolean savePreset(
            String typeId,
            int slot,
            String presetJson
    ) {
        if (typeId == null
                || slot < 0
                || slot >= SLOT_COUNT) {
            return false;
        }

        JsonObject preset =
                parseAndValidate(typeId, presetJson);

        if (preset == null) {
            return false;
        }

        ensureLoaded();

        JsonObject[] slots = PRESETS.computeIfAbsent(
                typeId,
                ignored -> new JsonObject[SLOT_COUNT]
        );

        JsonObject previous = slots[slot];
        slots[slot] = copy(preset);

        if (!writeFile()) {
            slots[slot] = previous;
            return false;
        }

        setSelectedSlot(typeId, slot);
        return true;
    }

    private static JsonObject getPresetObject(
            String typeId,
            int slot
    ) {
        if (typeId == null
                || slot < 0
                || slot >= SLOT_COUNT) {
            return null;
        }

        ensureLoaded();

        JsonObject[] slots = PRESETS.get(typeId);
        return slots == null ? null : slots[slot];
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }

        loaded = true;

        Path path = getPath();

        if (!Files.isRegularFile(path)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
        )) {
            JsonObject root = new JsonParser()
                    .parse(reader)
                    .getAsJsonObject();

            if (!root.has("format")
                    || root.get("format").getAsInt() != FORMAT
                    || !root.has("channels")
                    || !root.get("channels").isJsonObject()) {
                LOGGER.warn(
                        "Ignoring unsupported connector preset file: {}",
                        path
                );
                return;
            }

            JsonObject channels =
                    root.getAsJsonObject("channels");

            for (Map.Entry<String, JsonElement> entry
                    : channels.entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }

                JsonArray storedSlots =
                        entry.getValue().getAsJsonArray();

                JsonObject[] slots =
                        new JsonObject[SLOT_COUNT];

                for (int slot = 0;
                     slot < SLOT_COUNT
                             && slot < storedSlots.size();
                     slot++) {
                    JsonElement element =
                            storedSlots.get(slot);

                    if (!element.isJsonObject()) {
                        continue;
                    }

                    JsonObject validated =
                            parseAndValidate(
                                    entry.getKey(),
                                    element.toString()
                            );

                    if (validated != null) {
                        slots[slot] = validated;
                    }
                }

                PRESETS.put(entry.getKey(), slots);
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Could not read connector presets from {}",
                    path,
                    e
            );

            PRESETS.clear();
        }
    }

    private static boolean writeFile() {
        Path path = getPath();

        Path temporary = path.resolveSibling(
                path.getFileName().toString() + ".tmp"
        );

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);

        JsonObject channels = new JsonObject();

        /*
         * TreeMap keeps the file stable and easy to inspect manually.
         */
        for (Map.Entry<String, JsonObject[]> entry
                : new TreeMap<>(PRESETS).entrySet()) {
            JsonArray slots = new JsonArray();

            for (JsonObject preset : entry.getValue()) {
                slots.add(
                        preset == null
                                ? JsonNull.INSTANCE
                                : copy(preset)
                );
            }

            channels.add(entry.getKey(), slots);
        }

        root.add("channels", channels);

        try {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer =
                         Files.newBufferedWriter(
                                 temporary,
                                 StandardCharsets.UTF_8
                         )) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            return true;
        } catch (IOException e) {
            LOGGER.warn(
                    "Could not save connector presets to {}",
                    path,
                    e
            );

            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }

            return false;
        }
    }

    private static JsonObject parseAndValidate(
            String expectedType,
            String json
    ) {
        if (json == null
                || json.isEmpty()
                || json.getBytes(StandardCharsets.UTF_8).length
                > PacketBatchConnectorMutation.MAX_JSON_BYTES) {
            return null;
        }

        try {
            JsonObject root = new JsonParser()
                    .parse(json)
                    .getAsJsonObject();

            if (!root.has("type")
                    || !root.has("connector")
                    || !root.get("connector").isJsonObject()
                    || !root.has("advanced")
                    || !expectedType.equals(
                    root.get("type").getAsString()
            )) {
                return null;
            }

            /*
             * Force validation of the primitive now rather than later.
             */
            root.get("advanced").getAsBoolean();

            return root;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject copy(JsonObject object) {
        return new JsonParser()
                .parse(object.toString())
                .getAsJsonObject();
    }

    private static Path getPath() {
        return Loader.instance()
                .getConfigDir()
                .toPath()
                .resolve(
                        "xnetadditions-connector-presets.json"
                );
    }

    public static boolean deletePreset(String typeId, int slot) {
        if (typeId == null || slot < 0 || slot >= SLOT_COUNT) return false;
        ensureLoaded();
        JsonObject[] slots = PRESETS.get(typeId);
        if (slots == null || slots[slot] == null) return false;

        JsonObject previous = slots[slot];
        slots[slot] = null;
        if (!writeFile()) {
            slots[slot] = previous;
            return false;
        }

        if (getSelectedSlot(typeId) == slot) setSelectedSlot(typeId, -1);
        return true;
    }
}
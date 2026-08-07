package xnet.additions.batchedit.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.ConsumerId;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.apiimpl.logic.LogicConnectorSettings;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.blocks.cables.ConnectorTileEntity;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.clientinfo.ConnectorInfo;
import mcjty.xnet.logic.ChannelInfo;
import mcjty.xnet.multiblock.WorldBlob;
import mcjty.xnet.multiblock.XNetBlobData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import xnet.additions.batchedit.BatchEditSupport;
import xnet.additions.batchedit.network.PacketBatchConnectorMutation;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BatchConnectorMutationService {

    private static final double MAX_CONTROLLER_DISTANCE_SQ =
            64.0D;

    private BatchConnectorMutationService() {
    }

    public static void apply(
            EntityPlayerMP player,
            BlockPos controllerPos,
            int channelIndex,
            PacketBatchConnectorMutation.Operation operation,
            List<SidedPos> requestedTargets,
            String clipboardJson
    ) {
        if (channelIndex < 0
                || channelIndex >= ChannelInfo.MAX_CHANNELS
                || requestedTargets == null
                || requestedTargets.isEmpty()
                || requestedTargets.size()
                > PacketBatchConnectorMutation.MAX_TARGETS) {
            return;
        }

        if (!isCloseEnough(player, controllerPos)) {
            return;
        }

        TileEntity tile =
                player.world.getTileEntity(controllerPos);

        if (!(tile instanceof TileEntityController)) {
            return;
        }

        TileEntityController controller =
                (TileEntityController) tile;

        ChannelInfo[] channels = controller.getChannels();
        ChannelInfo channel = channels[channelIndex];

        if (channel == null) {
            return;
        }

        if (!BatchEditSupport.isSupported(
                channel.getType().getID()
        )) {
            player.sendStatusMessage(
                    new TextComponentString(
                            TextFormatting.YELLOW
                                    + "Batch operations are not supported for "
                                    + channel.getType().getName()
                    ),
                    true
            );
            return;
        }

        PasteData pasteData = null;

        if (operation
                == PacketBatchConnectorMutation.Operation.PASTE
                || operation
                == PacketBatchConnectorMutation.Operation.APPLY) {
            pasteData = parsePaste(
                    channel,
                    clipboardJson
            );

            if (pasteData == null) {
                player.sendStatusMessage(
                        new TextComponentString(
                                TextFormatting.RED
                                        + "Invalid connector preset data"
                        ),
                        true
                );
                return;
            }
        }

        Set<SidedPos> connectedTargets =
                new HashSet<>(
                        controller.getConnectedBlockPositions()
                );

        Map<SidedPos, TargetEntry> configured =
                buildConfiguredTargetMap(
                        controller,
                        channel
                );

        World world = player.world;
        WorldBlob worldBlob =
                XNetBlobData.getBlobData(world)
                        .getWorldBlob(world);

        Set<SidedPos> uniqueTargets =
                new LinkedHashSet<>(requestedTargets);

        int changed = 0;
        int skipped = 0;

        for (SidedPos target : uniqueTargets) {
            if (!connectedTargets.contains(target)) {
                skipped++;
                continue;
            }

            TargetEntry existing = configured.get(target);
            boolean success;

            switch (operation) {
                case CREATE:
                    success = existing == null
                            && createDefault(
                            world,
                            worldBlob,
                            channel,
                            target
                    );
                    break;

                case PASTE:
                    success = existing == null
                            && pasteConnector(
                            world,
                            worldBlob,
                            channel,
                            target,
                            pasteData
                    );
                    break;

                case DELETE:
                    success = existing != null
                            && deleteConnector(
                            world,
                            controller,
                            channel,
                            existing
                    );
                    break;

                case APPLY:
                    success = existing != null
                            && applyPreset(
                            world,
                            controller,
                            channel,
                            target,
                            existing,
                            pasteData
                    );
                    break;

                default:
                    success = false;
                    break;
            }

            if (success) {
                changed++;
            } else {
                skipped++;
            }
        }

        if (changed > 0) {
            /*
             * Invalidate the controller/network only once for the
             * entire batch.
             */
            controller.markAsDirty();
        }

        sendResult(
                player,
                operation,
                changed,
                skipped
        );
    }

    private static boolean isCloseEnough(
            EntityPlayerMP player,
            BlockPos controllerPos
    ) {
        double dx =
                player.posX - (controllerPos.getX() + 0.5D);
        double dy =
                player.posY - (controllerPos.getY() + 0.5D);
        double dz =
                player.posZ - (controllerPos.getZ() + 0.5D);

        return dx * dx + dy * dy + dz * dz
                <= MAX_CONTROLLER_DISTANCE_SQ;
    }

    private static Map<SidedPos, TargetEntry>
    buildConfiguredTargetMap(
            TileEntityController controller,
            ChannelInfo channel
    ) {
        Map<SidedPos, TargetEntry> result =
                new HashMap<>();

        for (Map.Entry<SidedConsumer, ConnectorInfo> entry
                : channel.getConnectors().entrySet()) {
            SidedConsumer key = entry.getKey();

            BlockPos connectorPos =
                    controller.findConsumerPosition(
                            key.getConsumerId()
                    );

            if (connectorPos == null) {
                continue;
            }

            SidedPos target = new SidedPos(
                    connectorPos.offset(key.getSide()),
                    key.getSide().getOpposite()
            );

            result.put(
                    target,
                    new TargetEntry(
                            key,
                            entry.getValue()
                    )
            );
        }

        return result;
    }

    private static boolean createDefault(
            World world,
            WorldBlob worldBlob,
            ChannelInfo channel,
            SidedPos target
    ) {
        if (!supportsTarget(channel, world, target)) {
            return false;
        }

        SidedConsumer key =
                resolveConsumer(worldBlob, target);

        if (key == null
                || channel.getConnectors().containsKey(key)) {
            return false;
        }

        BlockPos connectorPos =
                target.getPos().offset(target.getSide());

        boolean advanced =
                ConnectorBlock.isAdvancedConnector(
                        world,
                        connectorPos
                );

        try {
            channel.createConnector(key, advanced);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean pasteConnector(
            World world,
            WorldBlob worldBlob,
            ChannelInfo channel,
            SidedPos target,
            PasteData pasteData
    ) {
        if (pasteData == null
                || !supportsTarget(channel, world, target)) {
            return false;
        }

        SidedConsumer key =
                resolveConsumer(worldBlob, target);

        if (key == null
                || channel.getConnectors().containsKey(key)) {
            return false;
        }

        BlockPos connectorPos =
                target.getPos().offset(target.getSide());

        boolean targetAdvanced =
                ConnectorBlock.isAdvancedConnector(
                        world,
                        connectorPos
                );

        if (pasteData.sourceAdvanced
                && !targetAdvanced
                && (pasteData.advancedNeeded
                || !pasteData.facingOverride.equals(
                target.getSide()
        ))) {
            return false;
        }

        ConnectorInfo created = null;

        try {
            created = channel.createConnector(
                    key,
                    targetAdvanced
            );

            JsonObject connectorJson =
                    new JsonParser()
                            .parse(pasteData.connector.toString())
                            .getAsJsonObject();

            if (!targetAdvanced) {
                connectorJson.remove("facingoverride");
            }

            created.getConnectorSettings()
                    .readFromJson(connectorJson);

            created.getConnectorSettings()
                    .sanitizeSettings(targetAdvanced);

            return true;
        } catch (RuntimeException | LinkageError e) {
            if (created != null) {
                channel.getConnectors().remove(key);
            }

            return false;
        }
    }

    private static boolean applyPreset(
            World world,
            TileEntityController controller,
            ChannelInfo channel,
            SidedPos target,
            TargetEntry existing,
            PasteData pasteData
    ) {
        if (pasteData == null
                || !supportsTarget(channel, world, target)) {
            return false;
        }

        BlockPos connectorPos =
                target.getPos().offset(target.getSide());

        boolean targetAdvanced =
                ConnectorBlock.isAdvancedConnector(
                        world,
                        connectorPos
                );

        /*
         * Match XNet's normal paste restrictions.
         */
        if (pasteData.sourceAdvanced
                && !targetAdvanced
                && (pasteData.advancedNeeded
                || !pasteData.facingOverride.equals(
                target.getSide()
        ))) {
            return false;
        }

        IConnectorSettings settings =
                existing.connector.getConnectorSettings();

        NBTTagCompound backup =
                new NBTTagCompound();

        settings.writeToNBT(backup);

        boolean wasLogicOutput =
                isLogicOutput(settings);

        try {
            JsonObject connectorJson =
                    new JsonParser()
                            .parse(
                                    pasteData.connector.toString()
                            )
                            .getAsJsonObject();

            if (!targetAdvanced) {
                connectorJson.remove("facingoverride");
            }

            settings.readFromJson(connectorJson);
            settings.sanitizeSettings(targetAdvanced);

            /*
             * Prevent stale redstone output when a logic-output preset is
             * replaced by a sensor or another connector mode.
             */
            if (wasLogicOutput
                    && !isLogicOutput(settings)) {
                clearLogicOutputAtKey(
                        world,
                        controller,
                        existing.key
                );
            }

            return true;
        } catch (RuntimeException | LinkageError e) {
            /*
             * Restore the complete previous state if this target rejects
             * the preset.
             */
            try {
                settings.readFromNBT(backup);
            } catch (RuntimeException | LinkageError ignored) {
            }

            return false;
        }
    }

    private static boolean deleteConnector(
            World world,
            TileEntityController controller,
            ChannelInfo channel,
            TargetEntry target
    ) {
        ConnectorInfo removed =
                channel.getConnectors().get(target.key);

        if (removed == null) {
            return false;
        }

        clearLogicOutput(
                world,
                controller,
                target.key,
                removed
        );

        channel.getConnectors().remove(target.key);
        return true;
    }

    private static boolean supportsTarget(
            ChannelInfo channel,
            World world,
            SidedPos target
    ) {
        try {
            return channel.getType().supportsBlock(
                    world,
                    target.getPos(),
                    target.getSide()
            );
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static SidedConsumer resolveConsumer(
            WorldBlob worldBlob,
            SidedPos target
    ) {
        BlockPos connectorPos =
                target.getPos().offset(target.getSide());

        ConsumerId consumerId =
                worldBlob.getConsumerAt(connectorPos);

        if (consumerId == null) {
            return null;
        }

        return new SidedConsumer(
                consumerId,
                target.getSide().getOpposite()
        );
    }

    private static boolean isLogicOutput(
            IConnectorSettings settings
    ) {
        return settings instanceof LogicConnectorSettings
                && ((LogicConnectorSettings) settings)
                .getLogicMode()
                == LogicConnectorSettings.LogicMode.OUTPUT;
    }

    private static void clearLogicOutput(
            World world,
            TileEntityController controller,
            SidedConsumer key,
            ConnectorInfo connector
    ) {
        if (!isLogicOutput(
                connector.getConnectorSettings()
        )) {
            return;
        }

        clearLogicOutputAtKey(
                world,
                controller,
                key
        );
    }

    private static void clearLogicOutputAtKey(
            World world,
            TileEntityController controller,
            SidedConsumer key
    ) {
        BlockPos connectorPos =
                controller.findConsumerPosition(
                        key.getConsumerId()
                );

        if (connectorPos == null
                || !world.isBlockLoaded(connectorPos)) {
            return;
        }

        TileEntity tile =
                world.getTileEntity(connectorPos);

        if (tile instanceof ConnectorTileEntity) {
            ((ConnectorTileEntity) tile)
                    .setPowerOut(
                            key.getSide(),
                            0
                    );
        }
    }

    private static PasteData parsePaste(
            ChannelInfo channel,
            String json
    ) {
        if (json == null
                || json.isEmpty()
                || json.getBytes(StandardCharsets.UTF_8).length
                > PacketBatchConnectorMutation.MAX_JSON_BYTES) {
            return null;
        }

        try {
            JsonObject root =
                    new JsonParser()
                            .parse(json)
                            .getAsJsonObject();

            if (!root.has("type")
                    || !root.has("connector")
                    || !root.has("advanced")) {
                return null;
            }

            String typeId =
                    root.get("type").getAsString();

            if (!channel.getType()
                    .getID()
                    .equals(typeId)) {
                return null;
            }

            JsonObject connector =
                    root.getAsJsonObject("connector");

            if (!connector.has("side")
                    || !connector.has("advancedneeded")) {
                return null;
            }

            EnumFacing sourceSide =
                    EnumFacing.byName(
                            connector.get("side").getAsString()
                    );

            if (sourceSide == null) {
                return null;
            }

            EnumFacing facingOverride =
                    connector.has("facingoverride")
                            ? EnumFacing.byName(
                            connector.get(
                                    "facingoverride"
                            ).getAsString()
                    )
                            : sourceSide;

            if (facingOverride == null) {
                return null;
            }

            return new PasteData(
                    connector,
                    root.get("advanced").getAsBoolean(),
                    connector.get(
                            "advancedneeded"
                    ).getAsBoolean(),
                    facingOverride
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void sendResult(
            EntityPlayerMP player,
            PacketBatchConnectorMutation.Operation operation,
            int changed,
            int skipped
    ) {
        String verb;

        switch (operation) {
            case CREATE:
                verb = "Created";
                break;

            case PASTE:
                verb = "Pasted";
                break;

            case DELETE:
                verb = "Deleted";
                break;

            case APPLY:
                verb = "Applied preset to";
                break;

            default:
                verb = "Changed";
                break;
        }

        String message =
                TextFormatting.GREEN
                        + verb
                        + " "
                        + changed
                        + " connector"
                        + (changed == 1 ? "" : "s");

        if (skipped > 0) {
            message += TextFormatting.YELLOW
                    + " ("
                    + skipped
                    + " skipped)";
        }

        player.sendStatusMessage(
                new TextComponentString(message),
                true
        );
    }

    private static final class TargetEntry {
        private final SidedConsumer key;
        private final ConnectorInfo connector;

        private TargetEntry(
                SidedConsumer key,
                ConnectorInfo connector
        ) {
            this.key = key;
            this.connector = connector;
        }
    }

    private static final class PasteData {
        private final JsonObject connector;
        private final boolean sourceAdvanced;
        private final boolean advancedNeeded;
        private final EnumFacing facingOverride;

        private PasteData(
                JsonObject connector,
                boolean sourceAdvanced,
                boolean advancedNeeded,
                EnumFacing facingOverride
        ) {
            this.connector = connector;
            this.sourceAdvanced = sourceAdvanced;
            this.advancedNeeded = advancedNeeded;
            this.facingOverride = facingOverride;
        }
    }
}
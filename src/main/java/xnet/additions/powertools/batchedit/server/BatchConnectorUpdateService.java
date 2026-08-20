package xnet.additions.powertools.batchedit.server;

import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.clientinfo.ConnectorInfo;
import mcjty.xnet.logic.ChannelInfo;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import xnet.additions.powertools.batchedit.BatchEditSupport;
import xnet.additions.powertools.batchedit.BatchValueCodec;
import xnet.additions.powertools.batchedit.DataCollectorEditorGui;
import xnet.additions.powertools.batchedit.network.BatchEditNetwork;
import xnet.additions.powertools.batchedit.network.PacketBatchEditResult;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BatchConnectorUpdateService {

    private static final double MAX_CONTROLLER_DISTANCE_SQ = 64.0D;

    private BatchConnectorUpdateService() {
    }

    public static void apply(EntityPlayerMP player, BlockPos controllerPos, int channelIndex,
                             List<SidedPos> requestedTargets, Map<String, Object> requestedChanges) {
        if (channelIndex < 0 || channelIndex >= ChannelInfo.MAX_CHANNELS
                || requestedTargets.isEmpty() || requestedTargets.size() > 128
                || requestedChanges.isEmpty() || requestedChanges.size() > BatchValueCodec.MAX_VALUES) {
            return;
        }

        double dx = player.posX - (controllerPos.getX() + 0.5D);
        double dy = player.posY - (controllerPos.getY() + 0.5D);
        double dz = player.posZ - (controllerPos.getZ() + 0.5D);
        if (dx * dx + dy * dy + dz * dz > MAX_CONTROLLER_DISTANCE_SQ) {
            return;
        }

        TileEntity tile = player.world.getTileEntity(controllerPos);
        if (!(tile instanceof TileEntityController)) {
            return;
        }
        TileEntityController controller = (TileEntityController) tile;
        ChannelInfo[] channels = controller.getChannels();
        ChannelInfo channel = channels[channelIndex];
        if (channel == null) {
            return;
        }
        String typeId = channel.getType().getID();
        if (!BatchEditSupport.isSupported(typeId)) {
            BatchEditNetwork.CHANNEL.sendTo(new PacketBatchEditResult(controllerPos,
                    TextFormatting.YELLOW + "Batch edit is not supported for "
                            + channel.getType().getName()), player);
            return;
        }
        boolean allowMode = BatchEditSupport.supportsDirection(typeId);
        Map<SidedPos, ConnectorInfo> available = new HashMap<>();
        for (Map.Entry<SidedConsumer, ConnectorInfo> entry : channel.getConnectors().entrySet()) {
            BlockPos connectorPos = controller.findConsumerPosition(entry.getKey().getConsumerId());
            if (connectorPos != null) {
                SidedPos sidedPos = new SidedPos(
                        connectorPos.offset(entry.getKey().getSide()),
                        entry.getKey().getSide().getOpposite()
                );
                available.put(sidedPos, entry.getValue());
            }
        }

        int changed = 0;
        int skipped = 0;
        Set<SidedPos> uniqueTargets = new LinkedHashSet<>(requestedTargets);
        for (SidedPos target : uniqueTargets) {
            ConnectorInfo targetInfo = available.get(target);
            if (targetInfo == null) {
                skipped++;
                continue;
            }
            if (applyToConnector(targetInfo, requestedChanges, allowMode)) {
                changed++;
            } else {
                skipped++;
            }
        }

        if (changed > 0) {
            controller.markAsDirty();
        }

        String result = TextFormatting.GREEN + "Batch edited " + changed + " connector"
                + (changed == 1 ? "" : "s");
        if (skipped > 0) {
            result += TextFormatting.YELLOW + " (" + skipped + " skipped)";
        }
        BatchEditNetwork.CHANNEL.sendTo(new PacketBatchEditResult(controllerPos, result), player);
    }

    private static boolean applyToConnector(ConnectorInfo connectorInfo,
                                            Map<String, Object> requestedChanges,
                                            boolean allowMode) {
        IConnectorSettings settings = connectorInfo.getConnectorSettings();
        if (settings == null) {
            return false;
        }

        NBTTagCompound backup = new NBTTagCompound();
        boolean snapshotReady = false;
        try {
            boolean advanced = connectorInfo.isAdvanced();
            settings.writeToNBT(backup);
            snapshotReady = true;

            Map<String, Object> full = collect(settings, advanced);
            boolean changed = false;

            Object requestedMode = requestedChanges.get("mode");
            if (allowMode && requestedMode instanceof String
                    && ("INS".equalsIgnoreCase((String) requestedMode) || "EXT".equalsIgnoreCase((String) requestedMode))
                    && full.get("mode") instanceof String
                    && !((String) full.get("mode")).equalsIgnoreCase((String) requestedMode)) {
                full.put("mode", requestedMode);
                settings.update(full);
                settings.sanitizeSettings(advanced);
                full = collect(settings, advanced);
                changed = true;
            }

            boolean valuesChanged = false;
            for (Map.Entry<String, Object> change : requestedChanges.entrySet()) {
                String tag = change.getKey();
                if ("mode".equals(tag) || !full.containsKey(tag)
                        || !settings.isEnabled(tag)
                        || !compatible(full.get(tag), change.getValue())) {
                    continue;
                }

                Object replacement = copyValue(change.getValue());
                if (sameValue(full.get(tag), replacement)) continue;
                full.put(tag, replacement);
                valuesChanged = true;
            }

            if (valuesChanged) {
                settings.update(full);
                settings.sanitizeSettings(advanced);
                changed = true;
            }

            if (!changed) {
                restore(settings, backup, advanced);
                return false;
            }

            return true;
        } catch (RuntimeException | LinkageError ex) {
            if (snapshotReady) {
                restore(settings, backup, connectorInfo.isAdvanced());
            }
            return false;
        }
    }

    private static void restore(IConnectorSettings settings, NBTTagCompound backup, boolean advanced) {
        try {
            settings.readFromNBT(backup);
            settings.sanitizeSettings(advanced);
        } catch (RuntimeException | LinkageError ignored) {
            // A failed restore still means this connector must be skipped.
        }
    }

    private static Map<String, Object> collect(IConnectorSettings settings, boolean advanced) {
        DataCollectorEditorGui collector = new DataCollectorEditorGui(advanced);
        settings.createGui(collector);
        return collector.copyValues();
    }

    private static Object copyValue(Object value) {
        return value instanceof ItemStack ? ((ItemStack) value).copy() : value;
    }

    private static boolean sameValue(Object first, Object second) {
        if (first instanceof ItemStack && second instanceof ItemStack) {
            ItemStack a = (ItemStack) first;
            ItemStack b = (ItemStack) second;
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
            return a.getCount() == b.getCount() && ItemStack.areItemsEqual(a, b) && ItemStack.areItemStackTagsEqual(a, b);
        }
        return Objects.equals(first, second);
    }

    private static boolean compatible(Object current, Object replacement) {
        if (replacement == null) {
            return current == null || current instanceof Integer || current instanceof Double;
        }
        if (current == null) {
            return replacement instanceof Integer || replacement instanceof Double;
        }
        return current.getClass().isInstance(replacement);
    }
}

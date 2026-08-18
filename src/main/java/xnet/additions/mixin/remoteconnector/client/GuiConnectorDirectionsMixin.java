package xnet.additions.mixin.remoteconnector.client;

import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.lib.gui.widgets.ToggleButton;
import mcjty.xnet.blocks.cables.ConnectorTileEntity;
import mcjty.xnet.blocks.cables.GuiConnector;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.TextFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xnet.additions.mixin.client.GenericGuiContainerAccessor;
import xnet.additions.powertools.remoteconnector.ConnectorDirectionNames;
import xnet.additions.powertools.remoteconnector.client.RemoteGuiConnector;

@Mixin(value = GuiConnector.class, remap = false)
public abstract class GuiConnectorDirectionsMixin {
    @Shadow(remap = false) private ToggleButton[] toggleButtons;

    @Inject(method = "initGui", at = @At("RETURN"), remap = false)
    private void xnetadditions$addDirectionTooltips(CallbackInfo ci) {
        GuiConnector gui = (GuiConnector) (Object) this;
        String[] directionNames;
        int connectedMask;
        EnumFacing openedFace = null;
        if (gui instanceof RemoteGuiConnector) {
            RemoteGuiConnector remote = (RemoteGuiConnector) gui;
            directionNames = remote.xnetadditions$getDirectionNames();
            connectedMask = remote.xnetadditions$getConnectedMask();
            openedFace = remote.xnetadditions$getOpenedFace();
        } else {
            GenericTileEntity tile = ((GenericGuiContainerAccessor) this).xnetadditions$getTileEntity();
            if (!(tile instanceof ConnectorTileEntity) || tile.getWorld() == null) {return;}
            directionNames = ConnectorDirectionNames.snapshot(tile.getWorld(), tile.getPos());
            connectedMask = ConnectorDirectionNames.connectedMask(tile.getWorld(), tile.getPos());
        }
        if (toggleButtons == null || toggleButtons.length < EnumFacing.VALUES.length
                || directionNames == null || directionNames.length < EnumFacing.VALUES.length) {return;}
        for (EnumFacing facing : EnumFacing.VALUES) {
            ToggleButton button = toggleButtons[facing.ordinal()];
            if (button == null) {continue;}
            String adjacent = xnetadditions$displayName(directionNames[facing.ordinal()]);
            TextFormatting adjacentColor = (connectedMask & (1 << facing.ordinal())) != 0
                    ? TextFormatting.AQUA : TextFormatting.GRAY;
            String direction = xnetadditions$directionName(facing) + " " + TextFormatting.GRAY
                    + "(" + xnetadditions$axis(facing) + ")";
            if (facing == openedFace) {
                button.setTooltips(TextFormatting.GREEN + direction,
                        TextFormatting.GREEN + "Adjacent: " + adjacentColor + adjacent,
                        TextFormatting.GOLD + "Opened from this Controller entry");
            } else {
                button.setTooltips(TextFormatting.GREEN + direction,
                        TextFormatting.GREEN + "Adjacent: " + adjacentColor + adjacent);
            }
        }
    }

    @Unique
    private static String xnetadditions$displayName(String name) {
        if (name == null) {return "Unloaded";}
        if (name.isEmpty()) {return "Empty";}
        String displayName = I18n.format(name).trim();
        return displayName.isEmpty() ? "Unknown block" : displayName;
    }

    @Unique
    private static String xnetadditions$directionName(EnumFacing facing) {
        String name = facing.getName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Unique
    private static String xnetadditions$axis(EnumFacing facing) {
        switch (facing) {
            case DOWN: return "-Y";
            case UP: return "+Y";
            case NORTH: return "-Z";
            case SOUTH: return "+Z";
            case WEST: return "-X";
            case EAST: return "+X";
            default: throw new IllegalArgumentException("Unknown facing: " + facing);
        }
    }
}
package xnet.additions.powertools.remoteconnector.client;

import mcjty.lib.container.EmptyContainer;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.lib.typed.TypedMap;
import mcjty.lib.varia.Logging;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.blocks.cables.ConnectorTileEntity;
import mcjty.xnet.blocks.cables.GuiConnector;
import mcjty.xnet.blocks.controller.gui.GuiController;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import xnet.additions.powertools.remoteconnector.network.RemoteConnectorNetwork;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public final class RemoteGuiConnector extends GuiConnector
        implements RemoteConnectorNetwork.Receiver {
    private final GuiController controllerGui;
    private final SidedPos target;
    private final int requestId;
    private final BlockPos controllerPos;

    public RemoteGuiConnector(GuiController controllerGui,
                              RemoteConnectorNetwork.Response response) {
        super(snapshot(response),
                new EmptyContainer(Minecraft.getMinecraft().player, null));
        this.controllerGui = controllerGui;
        controllerPos = response.getControllerPos();
        target = response.getTarget();
        requestId = response.getRequestId();
        inventorySlots.windowId = controllerGui.inventorySlots.windowId;
    }

    private static ConnectorTileEntity snapshot(
            RemoteConnectorNetwork.Response response) {
        ConnectorTileEntity connector = new ConnectorTileEntity();
        connector.setPos(response.getTarget().getPos()
                .offset(response.getTarget().getSide()));
        connector.setConnectorName(response.getName());
        for (EnumFacing facing : EnumFacing.VALUES) {
            connector.setEnabled(facing,
                    (response.getEnabledMask()
                            & 1 << facing.ordinal()) != 0);
        }
        return connector;
    }

    @Override
    public void sendServerCommand(SimpleNetworkWrapper network,
                                  String command, TypedMap params) {
        if (GenericTileEntity.COMMAND_SYNC_BINDING.equals(command)
                && params.size() == 1) {
            String name = params.get(ConnectorTileEntity.VALUE_NAME);
            if (name == null) {
                return;
            }

            tileEntity.setConnectorName(name);
            RemoteConnectorNetwork.CHANNEL.sendToServer(
                    RemoteConnectorNetwork.Request.name(
                            controllerPos, target, requestId, name));
        } else if (ConnectorTileEntity.CMD_ENABLE.equals(command)
                && params.size() == 2) {
            Integer facing =
                    params.get(ConnectorTileEntity.PARAM_FACING);
            Boolean enabled =
                    params.get(ConnectorTileEntity.PARAM_ENABLED);
            if (facing == null || facing < 0
                    || facing >= EnumFacing.VALUES.length
                    || enabled == null) {
                return;
            }

            tileEntity.setEnabled(EnumFacing.VALUES[facing], enabled);
            RemoteConnectorNetwork.CHANNEL.sendToServer(
                    RemoteConnectorNetwork.Request.side(
                            controllerPos, target, requestId,
                            facing, enabled));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE
                || mc.gameSettings.keyBindInventory
                .isActiveAndMatches(keyCode)) {
            mc.displayGuiScreen(controllerGui);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void xnetadditions$receiveRemoteConnector(
            RemoteConnectorNetwork.Response response) {
        if (response.getRequestId() != requestId
                || !controllerPos.equals(response.getControllerPos())
                || !target.equals(response.getTarget())
                || response.getKind() == RemoteConnectorNetwork.OPEN) {
            return;
        }

        if (!response.getMessage().isEmpty()) {
            Logging.warn(mc.player, response.getMessage());
        }

        if (response.getKind() == RemoteConnectorNetwork.CLOSE) {
            mc.player.closeScreen();
        } else {
            mc.displayGuiScreen(controllerGui);
        }
    }
}
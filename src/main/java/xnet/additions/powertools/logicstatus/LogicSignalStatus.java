package xnet.additions.powertools.logicstatus;

import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.logic.ChannelInfo;

public final class LogicSignalStatus {

    public static final String LOGIC_CHANNEL_ID = "xnet.logic";
    public static final int NO_LOGIC_CHANNEL = -1;

    private LogicSignalStatus() {}

    public static int getActiveMask(TileEntityController controller) {
        int activeMask = 0;
        boolean hasLogic = false;
        for (ChannelInfo channel : controller.getChannels()) {
            if (channel == null || !LOGIC_CHANNEL_ID.equals(channel.getType().getID())) {continue;}
            hasLogic = true;
            if (channel.isEnabled()) {activeMask |= channel.getChannelSettings().getColors();}
        }
        return hasLogic ? activeMask & 0xffff : NO_LOGIC_CHANNEL;
    }
}
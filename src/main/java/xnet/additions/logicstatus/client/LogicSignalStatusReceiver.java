package xnet.additions.logicstatus.client;

import net.minecraft.util.math.BlockPos;

public interface LogicSignalStatusReceiver {
    void xnetadditions$setActiveSignalMask(BlockPos controllerPos, int activeMask);
}
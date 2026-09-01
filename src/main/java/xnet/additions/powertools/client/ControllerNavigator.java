package xnet.additions.powertools.client;

import mcjty.xnet.api.channels.Color;
import mcjty.xnet.api.keys.SidedPos;
import net.minecraft.util.EnumFacing;
import xnet.additions.powertools.probe.SideProbe;

public interface ControllerNavigator {
    boolean xnetadditions$isNavigationReady();
    boolean xnetadditions$navigate(SidedPos connector, int channel);
    void xnetadditions$inspectLogicColor(Color color);
    void xnetadditions$inspectSides(SidedPos target, int channel, SideProbe.Type type, EnumFacing configuredSide);
}
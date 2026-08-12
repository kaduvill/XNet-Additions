package xnet.additions.powertools.client;

import mcjty.xnet.api.keys.SidedPos;

public interface ControllerNavigator {
    boolean xnetadditions$isNavigationReady();
    boolean xnetadditions$navigate(SidedPos connector, int channel);
}
package xnet.additions.compat.jei;

import mcjty.xnet.blocks.cables.GuiConnector;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IIngredientRegistry;
import xnet.additions.powertools.remoteconnector.client.RemoteGuiConnector;

@JEIPlugin
public final class XNetAdditionsJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        IIngredientRegistry ingredients = registry.getIngredientRegistry();
        registry.addGhostIngredientHandler(GuiController.class, new XNetGhostIngredientHandler<>(ingredients));
        registry.addGhostIngredientHandler(GuiConnector.class, new XNetGhostIngredientHandler<>(ingredients));
        registry.addGhostIngredientHandler(RemoteGuiConnector.class, new XNetGhostIngredientHandler<>(ingredients));
    }
}
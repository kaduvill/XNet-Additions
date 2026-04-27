package xnet.additions;

import mcjty.xnet.api.IXNet;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInterModComms;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import xnet.additions.botania.BotaniaCompat;
import xnet.additions.config.XNetAdditionsConfig;
import xnet.additions.mekanism.MekanismCompat;
import xnet.additions.thaumcraft.ThaumcraftCompat;
import xnet.additions.industrialcraft2.IC2Compat;
import xnet.additions.util.ConnectableAdapter;

import java.util.function.Function;

@Mod(
		modid = "xnetadditions",
		name = "XNetAdditions",
		version = "0.1.4",
		dependencies = "required-after:xnet@[1.8.0,);after:mekanism;after:botania;after:thaumcraft;after:ic2",
		updateJSON = ""
)
public class XNetAdditions implements Function<IXNet, Void> {

	public static final ResourceLocation ICON_GUIELEMENTS =
			new ResourceLocation("xnetadditions", "textures/gui/guielements.png");

	private Configuration config;

	@Override
	public Void apply(IXNet xNet) {
		ConnectableAdapter adapter = new ConnectableAdapter();

		registerMekanism(xNet, adapter);
		registerBotania(xNet, adapter);
		registerThaumcraft(xNet, adapter);
		registerIC2(xNet, adapter);

		if (!adapter.isEmpty()) {
			xNet.registerConnectable(adapter);
		}

		return null;
	}

	private void registerMekanism(IXNet xNet, ConnectableAdapter adapter) {
		if (!XNetAdditionsConfig.enableMekanismGas) {
			return;
		}
		if (!Loader.isModLoaded("mekanism")) {
			return;
		}
		MekanismCompat.register(xNet, adapter);
	}

	private void registerBotania(IXNet xNet, ConnectableAdapter adapter) {
		if (!XNetAdditionsConfig.enableBotaniaMana) {
			return;
		}
		if (!Loader.isModLoaded("botania")) {
			return;
		}
		BotaniaCompat.register(xNet, adapter);
	}

	private void registerThaumcraft(IXNet xNet, ConnectableAdapter adapter) {
		if (!XNetAdditionsConfig.enableThaumcraftEssentia) {
			return;
		}
		if (!Loader.isModLoaded("thaumcraft")) {
			return;
		}
		ThaumcraftCompat.register(xNet, adapter);
	}

	private void registerIC2(IXNet xNet, ConnectableAdapter adapter) {
		if (!XNetAdditionsConfig.enableIC2EU) {
			return;
		}
		if (!Loader.isModLoaded("ic2")) {
			return;
		}
		IC2Compat.register(xNet, adapter);
	}

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		config = new Configuration(event.getSuggestedConfigurationFile());
		XNetAdditionsConfig.load(config);

		if (config.hasChanged()) {
			config.save();
		}
	}

	@Mod.EventHandler
	public void onInit(FMLInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
		FMLInterModComms.sendFunctionMessage("xnet", "getXNet", "xnet.additions.XNetAdditions");
	}
}

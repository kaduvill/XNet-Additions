package xnet.additions.powertools.health;

import mcjty.lib.varia.WorldTools;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.apiimpl.energy.EnergyChannelSettings;
import mcjty.xnet.apiimpl.energy.EnergyConnectorSettings;
import mcjty.xnet.apiimpl.fluids.FluidChannelSettings;
import mcjty.xnet.apiimpl.fluids.FluidConnectorSettings;
import mcjty.xnet.apiimpl.items.ItemChannelSettings;
import mcjty.xnet.apiimpl.items.ItemConnectorSettings;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.compat.RFToolsSupport;
import mcjty.xnet.logic.ChannelInfo;
import mcjty.xnet.setup.ModSetup;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandler;
import xnet.additions.channel.advancedenergy.AdvancedEnergyChannelSettings;
import xnet.additions.channel.advancedenergy.AdvancedEnergyConnectorSettings;
import xnet.additions.channel.botania.ManaChannelSettings;
import xnet.additions.channel.botania.ManaConnectorSettings;
import xnet.additions.channel.industrialcraft2.EUChannelSettings;
import xnet.additions.channel.industrialcraft2.EUConnectorSettings;
import xnet.additions.channel.mekanism.GasChannelSettings;
import xnet.additions.channel.mekanism.GasConnectorSettings;
import xnet.additions.channel.thaumcraft.EssentiaChannelSettings;
import xnet.additions.channel.thaumcraft.EssentiaConnectorSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HealthScanner {
    private static final int ROLE_UNKNOWN = -1;
    private static final int ROLE_SOURCE = 0;
    private static final int ROLE_DESTINATION = 1;

    private HealthScanner() {}

    public static List<HealthFinding> scan(TileEntityController controller) {
        List<HealthFinding> findings = new ArrayList<>();
        ChannelInfo[] channels = controller.getChannels();
        for (int channel = 0; channel < channels.length; channel++) {
            ChannelInfo info = channels[channel];
            if (info == null) {continue;}

            Map<SidedConsumer, IConnectorSettings> connectors = controller.getConnectors(channel);
            checkStaleConnectors(controller, channel, info, connectors, findings);
            if (!info.isEnabled()) {continue;}

            String type = info.getType().getID();
            checkTargets(controller, channel, type, connectors, findings);
            checkTransferPath(controller, channel, type, connectors, findings);
        }
        return findings;
    }

    private static void checkStaleConnectors(TileEntityController controller, int channel, ChannelInfo info,
                                             Map<SidedConsumer, IConnectorSettings> connectors, List<HealthFinding> findings) {
        for (SidedConsumer configured : info.getConnectors().keySet()) {
            if (connectors.containsKey(configured)) {continue;}
            int id = configured.getConsumerId().getId();
            BlockPos pos = controller.findConsumerPosition(configured.getConsumerId());
            findings.add(HealthFinding.channel(HealthFinding.Severity.ERROR, channel, pos == null
                    ? "Configured connector #" + id + " no longer resolves"
                    : "Configured connector #" + id + " is no longer on this network"));
        }
    }

    private static void checkTargets(TileEntityController controller, int channel, String type,
                                     Map<SidedConsumer, IConnectorSettings> connectors, List<HealthFinding> findings) {
        World world = controller.getWorld();
        for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
            SidedConsumer consumer = entry.getKey();
            BlockPos connectorPos = controller.findConsumerPosition(consumer.getConsumerId());
            if (connectorPos == null) {continue;}

            BlockPos targetPos = connectorPos.offset(consumer.getSide());
            if (!WorldTools.chunkLoaded(world, targetPos)) {continue;}

            TileEntity target = world.getTileEntity(targetPos);
            SidedPos navigation = new SidedPos(targetPos, consumer.getSide().getOpposite());
            IConnectorSettings settings = entry.getValue();

            switch (type) {
                case "xnet.item":
                    if (settings instanceof ItemConnectorSettings) {
                        checkItemTarget(channel, navigation, target, (ItemConnectorSettings) settings, findings);
                    }
                    break;
                case "xnet.fluid":
                    if (settings instanceof FluidConnectorSettings) {
                        checkFluidTarget(channel, navigation, target, (FluidConnectorSettings) settings, findings);
                    }
                    break;
                case "xnet.energy":
                    if (settings instanceof EnergyConnectorSettings) {
                        checkEnergyTarget(channel, navigation, target, (EnergyConnectorSettings) settings, findings);
                    }
                    break;
                case "advanced.energy":
                    if (settings instanceof AdvancedEnergyConnectorSettings) {
                        checkAdvancedEnergyTarget(channel, navigation, target, (AdvancedEnergyConnectorSettings) settings, findings);
                    }
                    break;
                case "mekanism.gas":
                    if (Loader.isModLoaded("mekanism") && settings instanceof GasConnectorSettings
                            && GasChannelSettings.getGasHandlerAt(target, ((GasConnectorSettings) settings).getFacing()) == null) {
                        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No gas target"));
                    }
                    break;
                case "botania.mana":
                    if (Loader.isModLoaded("botania") && settings instanceof ManaConnectorSettings
                            && ManaChannelSettings.getManaNode(target, ((ManaConnectorSettings) settings).getFacing()) == null) {
                        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No mana target"));
                    }
                    break;
                case "tc.essentia":
                    if (Loader.isModLoaded("thaumcraft") && settings instanceof EssentiaConnectorSettings
                            && EssentiaChannelSettings.getEssentiaNode(target) == null) {
                        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No essentia target"));
                    }
                    break;
                case "ic2.eu":
                    if (Loader.isModLoaded("ic2") && settings instanceof EUConnectorSettings) {
                        EUConnectorSettings eu = (EUConnectorSettings) settings;
                        boolean valid = eu.getEuMode() == EUConnectorSettings.EUMode.EXT
                                ? EUChannelSettings.getEnergySourceAt(world, targetPos) != null
                                : EUChannelSettings.getEnergySinkAt(world, targetPos) != null;
                        if (!valid) {
                            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation,
                                    eu.getEuMode() == EUConnectorSettings.EUMode.EXT ? "No EU source" : "No EU destination"));
                        }
                    }
                    break;
            }
        }
    }

    private static void checkItemTarget(int channel, SidedPos navigation, TileEntity target,
                                        ItemConnectorSettings settings, List<HealthFinding> findings) {
        if (ModSetup.rftools && RFToolsSupport.isStorageScanner(target)) {return;}
        IItemHandler handler = ItemChannelSettings.getItemHandlerAt(target, settings.getFacing());
        if (handler == null) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No item target"));
            return;
        }

        Integer slot = settings.getSlot();
        if (slot == null || slot < 0) {return;}
        if ((settings.getItemMode() == ItemConnectorSettings.ItemMode.INS
                || settings.getExtractMode() == ItemConnectorSettings.ExtractMode.SLOT)
                && slot >= handler.getSlots()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation,
                    "Configured slot " + slot + " is currently unavailable"));
        }
    }

    private static void checkFluidTarget(int channel, SidedPos navigation, TileEntity target,
                                         FluidConnectorSettings settings, List<HealthFinding> findings) {
        IFluidHandler handler = FluidChannelSettings.getFluidHandlerAt(target, settings.getFacing());
        if (handler == null) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No fluid target"));
            return;
        }

        Integer tank = settings.getExtractTank();
        if (settings.getFluidMode() == FluidConnectorSettings.FluidMode.EXT
                && settings.getExtractMode() == FluidConnectorSettings.ExtractMode.SLOT
                && tank != null && tank >= 0 && tank >= handler.getTankProperties().length) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation,
                    "Configured tank " + tank + " is currently unavailable"));
        }
    }

    private static void checkEnergyTarget(int channel, SidedPos navigation, TileEntity target,
                                          EnergyConnectorSettings settings, List<HealthFinding> findings) {
        IEnergyStorage handler = EnergyChannelSettings.getEnergyHandlerAt(target, settings.getFacing());
        if (handler == null) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No energy target"));
        } else if (settings.getEnergyMode() == EnergyConnectorSettings.EnergyMode.EXT && !handler.canExtract()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Energy target does not support extraction"));
        } else if (settings.getEnergyMode() == EnergyConnectorSettings.EnergyMode.INS && !handler.canReceive()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Energy target does not support insertion"));
        }
    }

    private static void checkAdvancedEnergyTarget(int channel, SidedPos navigation, TileEntity target,
                                                  AdvancedEnergyConnectorSettings settings, List<HealthFinding> findings) {
        if (!AdvancedEnergyChannelSettings.canUseTarget(target, settings.getFacing(), settings.getEnergyMode())) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation,
                    settings.getEnergyMode() == AdvancedEnergyConnectorSettings.EnergyMode.EXT
                            ? "Target cannot supply energy"
                            : "Target cannot receive energy"));
        }
    }

    private static void checkTransferPath(TileEntityController controller, int channel, String type,
                                          Map<SidedConsumer, IConnectorSettings> connectors, List<HealthFinding> findings) {
        if (!isTransferType(type)) {return;}

        boolean source = false;
        boolean destination = false;
        for (IConnectorSettings settings : connectors.values()) {
            int role = getRole(type, settings);
            if (role == ROLE_UNKNOWN) {return;}
            if (role == ROLE_SOURCE) {source = true;}
            else {destination = true;}
        }

        for (IConnectorSettings settings : controller.getRoutedConnectors(channel).values()) {
            int role = getRole(type, settings);
            if (role == ROLE_UNKNOWN) {return;}
            if (role == ROLE_DESTINATION) {destination = true;}
        }

        if (source && !destination) {
            findings.add(HealthFinding.channel(HealthFinding.Severity.WARN, channel, "No destinations"));
        } else if (destination && !source) {
            findings.add(HealthFinding.channel(HealthFinding.Severity.WARN, channel, "No sources"));
        }
    }

    private static boolean isTransferType(String type) {
        switch (type) {
            case "xnet.item":
            case "xnet.fluid":
            case "xnet.energy":
            case "advanced.energy":
                return true;
            case "mekanism.gas":
                return Loader.isModLoaded("mekanism");
            case "botania.mana":
                return Loader.isModLoaded("botania");
            case "tc.essentia":
                return Loader.isModLoaded("thaumcraft");
            case "ic2.eu":
                return Loader.isModLoaded("ic2");
            default:
                return false;
        }
    }

    private static int getRole(String type, IConnectorSettings settings) {
        switch (type) {
            case "xnet.item":
                if (!(settings instanceof ItemConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((ItemConnectorSettings) settings).getItemMode() == ItemConnectorSettings.ItemMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "xnet.fluid":
                if (!(settings instanceof FluidConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((FluidConnectorSettings) settings).getFluidMode() == FluidConnectorSettings.FluidMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "xnet.energy":
                if (!(settings instanceof EnergyConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((EnergyConnectorSettings) settings).getEnergyMode() == EnergyConnectorSettings.EnergyMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "advanced.energy":
                if (!(settings instanceof AdvancedEnergyConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((AdvancedEnergyConnectorSettings) settings).getEnergyMode() == AdvancedEnergyConnectorSettings.EnergyMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "mekanism.gas":
                if (!(settings instanceof GasConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((GasConnectorSettings) settings).getGasMode() == GasConnectorSettings.GasMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "botania.mana":
                if (!(settings instanceof ManaConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((ManaConnectorSettings) settings).getManaMode() == ManaConnectorSettings.ManaMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "tc.essentia":
                if (!(settings instanceof EssentiaConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((EssentiaConnectorSettings) settings).getEssentiaMode() == EssentiaConnectorSettings.EssentiaMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            case "ic2.eu":
                if (!(settings instanceof EUConnectorSettings)) {return ROLE_UNKNOWN;}
                return ((EUConnectorSettings) settings).getEuMode() == EUConnectorSettings.EUMode.EXT ? ROLE_SOURCE : ROLE_DESTINATION;
            default:
                return ROLE_UNKNOWN;
        }
    }
}
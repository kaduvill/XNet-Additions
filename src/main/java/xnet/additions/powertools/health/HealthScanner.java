package xnet.additions.powertools.health;

import mcjty.lib.varia.FluidTools;
import mcjty.lib.varia.WorldTools;
import mcjty.xnet.api.channels.Color;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.api.keys.SidedPos;
import mcjty.xnet.apiimpl.energy.EnergyChannelSettings;
import mcjty.xnet.apiimpl.energy.EnergyConnectorSettings;
import mcjty.xnet.apiimpl.fluids.FluidChannelSettings;
import mcjty.xnet.apiimpl.fluids.FluidConnectorSettings;
import mcjty.xnet.apiimpl.items.ItemChannelSettings;
import mcjty.xnet.apiimpl.items.ItemConnectorSettings;
import mcjty.xnet.apiimpl.logic.LogicConnectorSettings;
import mcjty.xnet.apiimpl.logic.Sensor;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.compat.RFToolsSupport;
import mcjty.xnet.config.ConfigSetup;
import mcjty.xnet.logic.ChannelInfo;
import mcjty.xnet.setup.ModSetup;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.common.Loader;
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
import xnet.additions.powertools.probe.SideProbe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HealthScanner {
    private static final int ROLE_UNKNOWN = -1;
    private static final int ROLE_SOURCE = 0;
    private static final int ROLE_DESTINATION = 1;

    private HealthScanner() {}

    public static List<HealthFinding> scan(TileEntityController controller) {
        List<HealthFinding> findings = new ArrayList<>();
        ChannelInfo[] channels = controller.getChannels();
        Set<SidedPos> connectedPositions = new HashSet<>(controller.getConnectedBlockPositions());
        List<Map<SidedConsumer, IConnectorSettings>> connectorsByChannel = new ArrayList<>(channels.length);

        for (int channel = 0; channel < channels.length; channel++) {
            ChannelInfo info = channels[channel];
            if (info == null) {
                connectorsByChannel.add(null);
                continue;
            }

            Map<SidedConsumer, IConnectorSettings> connected = new HashMap<>();
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : controller.getConnectors(channel).entrySet()) {
                SidedConsumer consumer = entry.getKey();
                BlockPos connectorPos = controller.findConsumerPosition(consumer.getConsumerId());
                if (connectorPos == null) {continue;}
                SidedPos target = new SidedPos(connectorPos.offset(consumer.getSide()), consumer.getSide().getOpposite());
                if (connectedPositions.contains(target)) {connected.put(consumer, entry.getValue());}
            }
            connectorsByChannel.add(connected);
        }

        int producibleColors = getProducibleColors(controller, channels, connectorsByChannel);
        for (int channel = 0; channel < channels.length; channel++) {
            ChannelInfo info = channels[channel];
            if (info == null) {continue;}

            Map<SidedConsumer, IConnectorSettings> connectors = connectorsByChannel.get(channel);
            if (!info.isEnabled()) {continue;}

            String type = info.getType().getID();
            checkConnectorSemantics(controller, channel, type, connectors, producibleColors, findings);
            checkTargets(controller, channel, type, connectors, findings);
            checkTransferPath(controller, channel, type, connectors, findings);
        }
        return findings;
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
                case "xnet.logic":
                    if (settings instanceof LogicConnectorSettings) {
                        checkLogicSensorTargets(channel, navigation, target, (LogicConnectorSettings) settings, findings);
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
                        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No gas target", SideProbe.Type.GAS));
                    }
                    break;
                case "botania.mana":
                    if (Loader.isModLoaded("botania") && settings instanceof ManaConnectorSettings
                            && ManaChannelSettings.getManaNode(target, ((ManaConnectorSettings) settings).getFacing()) == null) {
                        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No mana target", SideProbe.Type.MANA));
                    }
                    break;
                case "tc.essentia":
                    if (Loader.isModLoaded("thaumcraft") && settings instanceof EssentiaConnectorSettings
                            && EssentiaChannelSettings.getEssentiaNode(target) == null) {
                        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No essentia target", SideProbe.Type.ESSENTIA));
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
                                    eu.getEuMode() == EUConnectorSettings.EUMode.EXT ? "No EU source" : "No EU destination", SideProbe.Type.EU));
                        }
                    }
                    break;
            }
        }
    }
    private static int getProducibleColors(TileEntityController controller, ChannelInfo[] channels, List<Map<SidedConsumer, IConnectorSettings>> connectorsByChannel) {
        int colors = 0;
        for (Color color : Color.values()) {
            if (color == Color.OFF) {continue;}
            int bit = 1 << color.ordinal();
            if (controller.matchColor(bit)) {colors |= bit;}
        }

        for (int channel = 0; channel < channels.length; channel++) {
            ChannelInfo info = channels[channel];
            if (info == null || !info.isEnabled() || !"xnet.logic".equals(info.getType().getID())) {continue;}
            for (IConnectorSettings raw : connectorsByChannel.get(channel).values()) {
                if (!(raw instanceof LogicConnectorSettings)) {continue;}
                LogicConnectorSettings settings = (LogicConnectorSettings) raw;
                if (settings.getLogicMode() != LogicConnectorSettings.LogicMode.SENSOR) {continue;}
                for (Sensor sensor : settings.getSensors()) {
                    if (sensorCanProduceColor(sensor)) {colors |= 1 << sensor.getOutputColor().ordinal();}
                }
            }
        }
        return colors;
    }

    private static boolean sensorCanProduceColor(Sensor sensor) {
        Sensor.SensorMode mode = sensor.getSensorMode();
        Color color = sensor.getOutputColor();
        return mode != null && mode != Sensor.SensorMode.OFF && color != null && color != Color.OFF && !isSensorStatementImpossible(sensor);
    }
    private static void checkConnectorSemantics(TileEntityController controller, int channel, String type, Map<SidedConsumer, IConnectorSettings> connectors, int producibleColors, List<HealthFinding> findings) {
        for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
            SidedConsumer consumer = entry.getKey();
            BlockPos connectorPos = controller.findConsumerPosition(consumer.getConsumerId());
            if (connectorPos == null) {continue;}

            SidedPos navigation = new SidedPos(connectorPos.offset(consumer.getSide()), consumer.getSide().getOpposite());
            IConnectorSettings raw = entry.getValue();
            if (raw instanceof AbstractConnectorSettings) {
                checkColorSemantics(channel, navigation, (AbstractConnectorSettings) raw, producibleColors, findings);
            }

            switch (type) {
                case "xnet.item":
                    if (raw instanceof ItemConnectorSettings) {checkItemSemantics(channel, navigation, (ItemConnectorSettings) raw, findings);}
                    break;
                case "xnet.fluid":
                    if (raw instanceof FluidConnectorSettings) {checkFluidSemantics(channel, navigation, (FluidConnectorSettings) raw, findings);}
                    break;
                case "xnet.energy":
                    if (raw instanceof EnergyConnectorSettings) {checkEnergySemantics(channel, navigation, (EnergyConnectorSettings) raw, findings);}
                    break;
                case "xnet.logic":
                    if (raw instanceof LogicConnectorSettings) {checkLogicSemantics(channel, navigation, (LogicConnectorSettings) raw, findings);}
                    break;
                case "advanced.energy":
                    if (raw instanceof AdvancedEnergyConnectorSettings) {checkAdvancedEnergySemantics(channel, navigation, (AdvancedEnergyConnectorSettings) raw, findings);}
                    break;
            }
        }
    }
    private static void checkColorSemantics(int channel, SidedPos navigation, AbstractConnectorSettings settings, int producibleColors, List<HealthFinding> findings) {
        int required = settings.getColorsMask();
        if (required == 0) {return;}

        AbstractConnectorSettings.ColorOperator operator = settings.getColorOperator();

        boolean impossible = false;
        boolean alwaysTrue = false;
        switch (operator) {
            case AND:
                impossible = (required & ~producibleColors) != 0;
                break;
            case OR:
                impossible = (required & producibleColors) == 0;
                break;
            case NAND:
                alwaysTrue = (required & ~producibleColors) != 0;
                break;
            case NOR:
                alwaysTrue = (required & producibleColors) == 0;
                break;
        }

        if (impossible) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Color condition is always false"));
        } else if (alwaysTrue) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Color condition is always true"));
        }
    }

    private static void checkItemSemantics(int channel, SidedPos navigation, ItemConnectorSettings settings, List<HealthFinding> findings) {
        boolean hasFilters = hasFilterEntries(settings.getFilters());

        if (settings.isBlacklist() && !hasFilters) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Blacklist enabled with empty filter; blacklist has no entries to exclude"));
        }

        if (settings.isCountMode()) {
            if (settings.isBlacklist()) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Count is ignored in blacklist mode"));
            } else if (!hasFilters) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Count enabled with empty filter; Count has no entries to apply to"));
            }
        }

        if (settings.getItemMode() == ItemConnectorSettings.ItemMode.EXT
                && (settings.getStackMode() == ItemConnectorSettings.StackMode.COUNTM || settings.getStackMode() == ItemConnectorSettings.StackMode.COUNTE)) {
            Integer amount = settings.getExtractAmountSetting();
            if (amount != null && amount == 0) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Extract amount is 0"));
            }
        }

        if (settings.getItemMode() == ItemConnectorSettings.ItemMode.EXT && settings.getStackMode() == ItemConnectorSettings.StackMode.COUNTE) {
            int transferCap = settings.isAdvanced() ? ConfigSetup.maxItemTransferAdvancedCached : ConfigSetup.maxItemTransferNormalCached;
            if (settings.getExtractAmount() > transferCap) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Exact extract amount " + settings.getExtractAmount() + " exceeds transfer cap " + transferCap));
            }
        }

        Integer count = settings.getCount();
        if (settings.getItemMode() == ItemConnectorSettings.ItemMode.INS && count != null && count < 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Insertion maximum is negative"));
        }
    }

    private static void checkFluidSemantics(int channel, SidedPos navigation, FluidConnectorSettings settings, List<HealthFinding> findings) {
        int entries = 0;
        int invalid = 0;
        for (ItemStack filter : settings.getFilters()) {
            if (filter.isEmpty()) {continue;}
            entries++;
            if (FluidTools.convertBucketToFluid(filter) == null) {invalid++;}
        }

        if (settings.isBlacklist() && entries == 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Blacklist enabled with empty filter; blacklist has no entries to exclude"));
        }

        if (invalid > 0) {
            if (invalid == entries && settings.isBlacklist()) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Blacklist has no valid fluid entries; it filters nothing"));
            } else if (invalid == entries) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Fluid filter can never match"));
            } else {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Fluid filter contains invalid entries that are ignored"));
            }
        }

        if (settings.getFluidMode() == FluidConnectorSettings.FluidMode.EXT && settings.getPriority() != 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Priority is ignored on Fluid extractors"));
        }

        Integer rate = settings.getRate();
        if (settings.getFluidMode() == FluidConnectorSettings.FluidMode.INS) {
            if (rate != null && rate <= 0) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Fluid insertion rate is 0"));
            }
            Integer max = settings.getMinmax();
            if (max != null && max < 0) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Insertion maximum is negative"));
            }
        } else if (settings.getAmountMode() == FluidConnectorSettings.AmountMode.RATE && rate != null && rate <= 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Fluid extraction rate is 0"));
        }
    }

    private static boolean hasFilterEntries(Iterable<ItemStack> filters) {
        for (ItemStack filter : filters) {
            if (filter != null && !filter.isEmpty()) {return true;}
        }
        return false;
    }
    private static void checkEnergySemantics(int channel, SidedPos navigation, EnergyConnectorSettings settings, List<HealthFinding> findings) {
        Integer rate = settings.getRate();
        if (rate != null && rate <= 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Energy transfer rate is 0"));
        }

        Integer max = settings.getMinmax();
        if (settings.getEnergyMode() == EnergyConnectorSettings.EnergyMode.INS && max != null && max < 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Insertion maximum is negative"));
        }
    }
    private static void checkAdvancedEnergySemantics(int channel, SidedPos navigation, AdvancedEnergyConnectorSettings settings, List<HealthFinding> findings) {
        Integer max = settings.getMinmax();
        if (settings.getEnergyMode() == AdvancedEnergyConnectorSettings.EnergyMode.INS && max != null && max < 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Insertion maximum is negative"));
        }
    }
    private static void checkLogicSemantics(int channel, SidedPos navigation, LogicConnectorSettings settings, List<HealthFinding> findings) {
        if (settings.getLogicMode() == LogicConnectorSettings.LogicMode.OUTPUT) {
            Integer strength = settings.getRedstoneOut();
            if (strength == null || strength == 0) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Logic output strength is 0"));
            } else if (strength > 15) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Logic output strength " + strength + " is clamped to 15"));
            }
            return;
        }

        if (settings.getLogicMode() != LogicConnectorSettings.LogicMode.SENSOR) {return;}
        for (int i = 0; i < settings.getSensors().size(); i++) {
            Sensor sensor = settings.getSensors().get(i);
            Sensor.SensorMode mode = sensor.getSensorMode();
            if (mode == null || mode == Sensor.SensorMode.OFF) {continue;}

            String prefix = "Sensor " + (i + 1) + ": ";
            if (sensor.getOutputColor() == null || sensor.getOutputColor() == Color.OFF) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, prefix + "output color is OFF"));
            }

            if (mode == Sensor.SensorMode.FLUID && !sensor.getFilter().isEmpty() && FluidTools.convertBucketToFluid(sensor.getFilter()) == null) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, prefix + "invalid fluid filter; counting all fluids"));
            }

            checkLogicNumericSemantics(channel, navigation, sensor, i + 1, findings);
        }
    }
    private static void checkLogicNumericSemantics(int channel, SidedPos navigation, Sensor sensor, int sensorNumber, List<HealthFinding> findings) {
        Sensor.Operator operator = sensor.getOperator();
        Sensor.SensorMode mode = sensor.getSensorMode();
        if (operator == null || mode == null) {return;}

        if (mode == Sensor.SensorMode.RS) {
            boolean canBeTrue = false;
            boolean canBeFalse = false;
            for (int power = 0; power <= 15; power++) {
                if (operator.match(power, sensor.getAmount())) {canBeTrue = true;}
                else {canBeFalse = true;}
            }
            if (!canBeTrue) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Sensor " + sensorNumber + ": redstone condition is never true"));
            } else if (!canBeFalse) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.WARN, channel, navigation, "Sensor " + sensorNumber + ": redstone condition is always true"));
            }
            return;
        }

        if (!isImpossibleNonNegativeCondition(operator, sensor.getAmount())) {return;}
        String value;
        switch (mode) {
            case ITEM:
                value = "item";
                break;
            case FLUID:
                value = "fluid";
                break;
            case ENERGY:
                value = "energy";
                break;
            default:
                return;
        }
        findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Sensor " + sensorNumber + ": " + value + " condition is never true"));
    }
    private static boolean isSensorStatementImpossible(Sensor sensor) {
        Sensor.Operator operator = sensor.getOperator();
        Sensor.SensorMode mode = sensor.getSensorMode();
        if (operator == null || mode == null || mode == Sensor.SensorMode.OFF) {return false;}

        if (mode == Sensor.SensorMode.RS) {
            for (int power = 0; power <= 15; power++) {
                if (operator.match(power, sensor.getAmount())) {return false;}
            }
            return true;
        }

        return (mode == Sensor.SensorMode.ITEM || mode == Sensor.SensorMode.FLUID || mode == Sensor.SensorMode.ENERGY)
                && isImpossibleNonNegativeCondition(operator, sensor.getAmount());
    }

    private static boolean isImpossibleNonNegativeCondition(Sensor.Operator operator, int amount) {
        switch (operator) {
            case EQUAL:
                return amount < 0;
            case LESS:
                return amount <= 0;
            case LESSOREQUAL:
                return amount < 0;
            default:
                return false;
        }
    }
    private static void checkItemTarget(int channel, SidedPos navigation, TileEntity target,
                                        ItemConnectorSettings settings, List<HealthFinding> findings) {
        SideProbe.Fact fact = SideProbe.probe(target, SideProbe.Type.ITEM, settings.getFacing());
        if (!fact.hasAccess()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No item target", SideProbe.Type.ITEM));
            return;
        }

        int slots = fact.getCount();
        if (slots < 0) {return;}
        if (slots == 0) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No item slots accessible on configured side", SideProbe.Type.ITEM));
            return;
        }

        Integer slot = settings.getSlot();
        if (slot == null || slot < 0) {return;}
        if ((settings.getItemMode() == ItemConnectorSettings.ItemMode.INS
                || settings.getExtractMode() == ItemConnectorSettings.ExtractMode.SLOT)
                && slot >= slots) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation,
                    "Configured slot " + slot + " is currently unavailable", SideProbe.Type.ITEM));
        }
    }

    private static void checkFluidTarget(int channel, SidedPos navigation, TileEntity target, FluidConnectorSettings settings, List<HealthFinding> findings) {
        IFluidHandler handler = FluidChannelSettings.getFluidHandlerAt(target, settings.getFacing());
        if (handler == null) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No fluid target", SideProbe.Type.FLUID));
            return;
        }

        IFluidTankProperties[] properties = handler.getTankProperties();
        if (settings.getFluidMode() == FluidConnectorSettings.FluidMode.EXT) {
            if (settings.getExtractMode() == FluidConnectorSettings.ExtractMode.SLOT) {
                Integer tank = settings.getExtractTank();
                int selected = tank == null ? 0 : tank;
                if (tank != null && tank >= 0 && tank >= properties.length) {
                    findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Configured tank " + tank + " is currently unavailable", SideProbe.Type.FLUID));
                    return;
                }
                if (properties.length > 0 && selected >= 0 && selected < properties.length && !properties[selected].canDrain()) {
                    findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Configured tank does not support extraction", SideProbe.Type.FLUID));
                }
                return;
            }

            if (properties.length > 0) {
                boolean canDrain = false;
                for (IFluidTankProperties property : properties) {
                    if (property.canDrain()) {
                        canDrain = true;
                        break;
                    }
                }
                if (!canDrain) {
                    findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Fluid target does not support extraction", SideProbe.Type.FLUID));
                }
            }
        } else if (properties.length > 0) {
            boolean canFill = false;
            for (IFluidTankProperties property : properties) {
                if (property.canFill()) {
                    canFill = true;
                    break;
                }
            }
            if (!canFill) {
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Fluid target does not support insertion", SideProbe.Type.FLUID));
            }
        }
    }

    private static void checkEnergyTarget(int channel, SidedPos navigation, TileEntity target,
                                          EnergyConnectorSettings settings, List<HealthFinding> findings) {
        SideProbe.Fact fact = SideProbe.probe(target, SideProbe.Type.ENERGY, settings.getFacing());
        if (!fact.hasAccess()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "No energy target", SideProbe.Type.ENERGY));
        } else if (settings.getEnergyMode() == EnergyConnectorSettings.EnergyMode.EXT && !fact.canOutput()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Energy target does not support extraction", SideProbe.Type.ENERGY));
        } else if (settings.getEnergyMode() == EnergyConnectorSettings.EnergyMode.INS && !fact.canInput()) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Energy target does not support insertion", SideProbe.Type.ENERGY));
        }
    }
    private static void checkLogicSensorTargets(int channel, SidedPos navigation, TileEntity target, LogicConnectorSettings settings, List<HealthFinding> findings) {
        if (settings.getLogicMode() != LogicConnectorSettings.LogicMode.SENSOR) {return;}
        for (int i = 0; i < settings.getSensors().size(); i++) {
            Sensor sensor = settings.getSensors().get(i);
            Sensor.SensorMode mode = sensor.getSensorMode();
            if (mode == null || mode == Sensor.SensorMode.OFF || mode == Sensor.SensorMode.RS) {continue;}

            boolean valid;
            switch (mode) {
                case ITEM:
                    valid = (ModSetup.rftools && RFToolsSupport.isStorageScanner(target)) || ItemChannelSettings.getItemHandlerAt(target, settings.getFacing()) != null;
                    break;
                case FLUID:
                    valid = FluidChannelSettings.getFluidHandlerAt(target, settings.getFacing()) != null;
                    break;
                case ENERGY:
                    valid = EnergyChannelSettings.isEnergyTE(target, settings.getFacing());
                    break;
                default:
                    continue;
            }

            if (!valid) {
                String targetType = mode == Sensor.SensorMode.ITEM ? "item" : mode == Sensor.SensorMode.FLUID ? "fluid" : "energy";
                SideProbe.Type probeType = mode == Sensor.SensorMode.ITEM ? SideProbe.Type.ITEM : mode == Sensor.SensorMode.FLUID ? SideProbe.Type.FLUID : SideProbe.Type.ENERGY;
                findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation, "Sensor " + (i + 1) + ": no " + targetType + " target", probeType));
            }
        }
    }
    private static void checkAdvancedEnergyTarget(int channel, SidedPos navigation, TileEntity target,
                                                  AdvancedEnergyConnectorSettings settings, List<HealthFinding> findings) {
        if (!AdvancedEnergyChannelSettings.canUseTarget(target, settings.getFacing(), settings.getEnergyMode(), settings.usesConnectorBuffer())) {
            findings.add(HealthFinding.connector(HealthFinding.Severity.ERROR, channel, navigation,
                    settings.getEnergyMode() == AdvancedEnergyConnectorSettings.EnergyMode.EXT
                            ? "No energy extraction"
                            : "No energy insertion", SideProbe.Type.ADVANCED_ENERGY));
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
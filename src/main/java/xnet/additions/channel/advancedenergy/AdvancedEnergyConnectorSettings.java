package xnet.additions.channel.advancedenergy;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcjty.xnet.XNet;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import xnet.additions.config.XNetAdditionsConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public class AdvancedEnergyConnectorSettings extends AbstractConnectorSettings {

    public static final ResourceLocation iconGuiElements = new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";
    public static final String TAG_RATE = "rate";
    public static final String TAG_MINMAX = "minmax";
    public static final String TAG_PRIORITY = "priority";
    public static final String TAG_SPEED = "speed";
    public static final String TAG_ADAPTIVE = "adaptive";
    public static final String TAG_BUFFER = "buffer";
    private static final int[] SPEEDS = { 1, 2, 4, 5, 10, 20, 40, 60, 100, 200, 600 };
    private static String[] getSpeedChoices() {
        String[] choices = new String[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) {
            choices[i] = Integer.toString(SPEEDS[i]);
        }
        return choices;
    }

    public enum EnergyMode {
        INS,
        EXT
    }

    private EnergyMode energyMode = EnergyMode.INS;

    @Nullable private Integer priority = 0;
    @Nullable private Integer rate = null;
    @Nullable private Integer minmax = null;
    @Nullable private Integer speed = 1;
    private boolean adaptive = true;
    private boolean connectorBuffer = false;

    public AdvancedEnergyConnectorSettings(@Nonnull EnumFacing side) {super(side);}
    public EnergyMode getEnergyMode() {return energyMode;}
    @Nonnull
    public Integer getPriority() {return priority == null ? 0 : priority;}
    @Nullable
    public Integer getRate() {return rate;}
    @Nullable
    public Integer getMinmax() {return minmax;}
    public boolean isAdaptive() {return adaptive;}
    public boolean usesConnectorBuffer() {return energyMode == EnergyMode.EXT && connectorBuffer;}
    private static boolean isValidSpeed(int value) {
        for (int s : SPEEDS) {if (s == value) {return true;}}
        return false;
    }

    public int getSpeed() {
        // Extractors are always ticked normally. Speed only belongs to insert connectors.
        if (energyMode == EnergyMode.EXT) {
            return 1;
        }
        if (speed == null) {
            return 1;
        }
        return isValidSpeed(speed) ? speed : 1;
    }

    /*
    @Override
    public void sanitizeSettings(boolean advanced) {
        super.sanitizeSettings(advanced);
        // Extractors do not use timing.
        if (energyMode == EnergyMode.EXT) {
            speed = 1;
            return;
        }
        if (speed == null || !isValidSpeed(speed)) {
            speed = 1;
        }
    }
*/

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        switch (energyMode) {
            case INS:
                return new IndicatorIcon(iconGuiElements, 0, 70, 13, 10);
            case EXT:
                return new IndicatorIcon(iconGuiElements, 13, 70, 13, 10);
        }
        return null;
    }

    @Override
    @Nullable
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        advanced = gui.isAdvanced();
        sideGui(gui);
        colorsGui(gui);
        redstoneGui(gui);

        gui.nl()
                .choices(TAG_MODE, "Insert or extract mode", energyMode, EnergyMode.values());

        if (energyMode == EnergyMode.INS) {
            gui.shift(5)
                    .choices(TAG_SPEED,
                            "Number of ticks between insertion attempts",
                            Integer.toString(getSpeed()),
                            getSpeedChoices())
                    .shift(5)
                    .toggleText(TAG_ADAPTIVE, "Adaptive idle fallback|Falls back when there is no demand|Only applies to 1-20 timing", "Adaptive", adaptive);
        } else {
            gui.shift(5).toggleText(TAG_BUFFER, "Drain energy pushed into this connector|For push-only sources|Limited by XNet's native buffer size|Off only stops this channel from draining it", "Buffer", connectorBuffer);
        }

        gui.nl()
                .label("Pri").integer(TAG_PRIORITY, "Insertion priority", priority, 36)
                .shift(5)
                .label("Rate")
                .integer(TAG_RATE,
                        (energyMode == EnergyMode.EXT ? "Max energy extraction rate" : "Max energy insertion rate") +
                                "|(limited to " + (advanced
                                ? XNetAdditionsConfig.maxAdvancedEnergyRateAdvanced
                                : XNetAdditionsConfig.maxAdvancedEnergyRateNormal) + " per tick)",
                        rate, 40)
                .nl()
                .label(energyMode == EnergyMode.EXT ? "Min" : "Max")
                .integer(TAG_MINMAX,
                        energyMode == EnergyMode.EXT ? "Disable extraction if energy|is too low" : "Disable insertion if energy|is too high",
                        minmax, 50);
    }

    private static final Set<String> INSERT_TAGS = ImmutableSet.of(
            TAG_MODE, TAG_RS, TAG_COLOR+"0", TAG_COLOR+"1", TAG_COLOR+"2", TAG_COLOR+"3",
            TAG_RATE, TAG_MINMAX, TAG_PRIORITY, TAG_SPEED, TAG_ADAPTIVE);

    private static final Set<String> EXTRACT_TAGS = ImmutableSet.of(
            TAG_MODE, TAG_RS, TAG_COLOR+"0", TAG_COLOR+"1", TAG_COLOR+"2", TAG_COLOR+"3",
            TAG_RATE, TAG_MINMAX, TAG_PRIORITY, TAG_BUFFER);

    @Override
    public boolean isEnabled(String tag) {
        if (tag.equals(TAG_FACING)) {
            return advanced;
        }
        return (energyMode == EnergyMode.INS ? INSERT_TAGS : EXTRACT_TAGS).contains(tag);
    }

    @Override
    public void update(Map<String, Object> data) {
        super.update(data);
        energyMode = EnergyMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
        rate = (Integer) data.get(TAG_RATE);
        minmax = (Integer) data.get(TAG_MINMAX);
        priority = (Integer) data.get(TAG_PRIORITY);
        if (data.containsKey(TAG_ADAPTIVE)) {adaptive = Boolean.TRUE.equals(data.get(TAG_ADAPTIVE));}
        if (data.containsKey(TAG_BUFFER)) {connectorBuffer = Boolean.TRUE.equals(data.get(TAG_BUFFER));}
        if (energyMode == EnergyMode.EXT) {
            speed = 1;
        } else {
            Object s = data.get(TAG_SPEED);
            if (s instanceof String) {
                try {
                    speed = Integer.parseInt((String) s);
                } catch (NumberFormatException e) {
                    speed = 1;
                }
            } else if (s instanceof Integer) {
                speed = (Integer) s;
            } else {
                speed = 1;
            }

            if (!isValidSpeed(speed)) {
                speed = 1;
            }
        }
    }

    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();
        super.writeToJsonInternal(object);
        setEnumSafe(object, "energymode", energyMode);
        setIntegerSafe(object, "priority", priority);
        setIntegerSafe(object, "rate", rate);
        setIntegerSafe(object, "minmax", minmax);
        setIntegerSafe(object, "speed", getSpeed());
        object.add("adaptive", new JsonPrimitive(adaptive));
        object.add("connectorBuffer", new JsonPrimitive(connectorBuffer));
        if (rate != null && rate > XNetAdditionsConfig.maxAdvancedEnergyRateNormal) {
            object.add("advancedneeded", new JsonPrimitive(true));
        }
        return object;
    }

    @Override
    public void readFromJson(JsonObject object) {
        super.readFromJsonInternal(object);
        energyMode = getEnumSafe(object, "energymode", s -> EnergyMode.valueOf(s.toUpperCase()));
        priority = getIntegerSafe(object, "priority");
        rate = getIntegerSafe(object, "rate");
        minmax = getIntegerSafe(object, "minmax");
        speed = getIntegerSafe(object, "speed");
        adaptive = !object.has("adaptive") || object.get("adaptive").getAsBoolean();
        connectorBuffer = object.has("connectorBuffer") && object.get("connectorBuffer").getAsBoolean();
        if (energyMode == null) {
            energyMode = EnergyMode.INS;
        }
        if (energyMode == EnergyMode.EXT || speed == null || !isValidSpeed(speed)) {
            speed = 1;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("energyMode")) {
            energyMode = EnergyMode.values()[tag.getByte("energyMode")];
        } else {
            energyMode = EnergyMode.values()[tag.getByte("itemMode")];}
        if (tag.hasKey("priority")) {priority = tag.getInteger("priority");
        } else {priority = null;}
        if (tag.hasKey("rate")) {rate = tag.getInteger("rate");
        } else {rate = null;}
        if (tag.hasKey("minmax")) {minmax = tag.getInteger("minmax");
        } else {minmax = null;}
        if (tag.hasKey("speed")) {speed = tag.getInteger("speed");
        } else {speed = 1;}
        adaptive = !tag.hasKey("adaptive") || tag.getBoolean("adaptive");
        if (energyMode == EnergyMode.EXT || !isValidSpeed(speed)) {
            speed = 1;
        }
        connectorBuffer = tag.getBoolean("connectorBuffer");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setByte("energyMode", (byte) energyMode.ordinal());
        if (priority != null) {tag.setInteger("priority", priority);}
        if (rate != null) {tag.setInteger("rate", rate);}
        if (minmax != null) {tag.setInteger("minmax", minmax);}
        if (speed != null) {tag.setInteger("speed", getSpeed());}
        tag.setBoolean("adaptive", adaptive);
        tag.setBoolean("connectorBuffer", connectorBuffer);
    }
}
package xnet.additions.botania;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcjty.xnet.XNet;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import xnet.additions.config.XNetAdditionsConfig;
import xnet.additions.util.ConnectorSpeedHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public class ManaConnectorSettings extends AbstractConnectorSettings {

    public static final ResourceLocation iconGuiElements =
            new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";
    public static final String TAG_RATE = "rate";
    public static final String TAG_MINMAX = "minmax";
    public static final String TAG_PRIORITY = "priority";
    public static final String TAG_SPEED = "speed";

    public enum ManaMode {
        INS,
        EXT
    }

    private ManaMode manaMode = ManaMode.INS;
    @Nullable private Integer priority = 0;
    @Nullable private Integer rate = null;
    @Nullable private Integer minmax = null;
    private int speed = 2;

    public ManaConnectorSettings(@Nonnull EnumFacing side) {
        super(side);
    }

    public ManaMode getManaMode() {
        return manaMode;
    }
    private int getMaxRate(boolean advanced) {
        return advanced ? XNetAdditionsConfig.maxManaRateAdvanced
                : XNetAdditionsConfig.maxManaRateNormal;
    }

    private int getMaxRate() {
        return getMaxRate(advanced);
    }

    private void sanitizeRate(boolean advanced) {
        int maxRate = getMaxRate(advanced);
        if (rate != null) {
            if (rate > maxRate) {
                rate = maxRate;
            }
            if (rate < 0) {
                rate = 0;
            }
        }
    }

    private void sanitizeRate() {
        sanitizeRate(advanced);
    }

    private void sanitizeSpeed(boolean advanced) {
        speed = ConnectorSpeedHelper.sanitizeSpeed(speed, advanced);
    }
    public int getSpeed() {
        return speed;
    }

    @Nonnull
    public Integer getPriority() {
        return priority == null ? 0 : priority;
    }

    @Nullable
    public Integer getRate() {
        return rate;
    }

    @Nullable
    public Integer getMinmax() {
        return minmax;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        switch (manaMode) {
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
        sanitizeRate();
        sanitizeSpeed(advanced);

        String[] speeds = ConnectorSpeedHelper.getSpeedChoices(advanced);
        int maxrate = getMaxRate();

        sideGui(gui);
        colorsGui(gui);
        redstoneGui(gui);
        gui.nl()
                .choices(TAG_MODE, "Insert or extract mode", manaMode, ManaMode.values());

        if (manaMode == ManaMode.EXT) {
            gui.choices(TAG_SPEED, "Number of ticks for each operation", Integer.toString(speed * 10), speeds);
        }

        gui.nl()
                .label("Pri").integer(TAG_PRIORITY, "Insertion priority", priority, 36)
                .nl()

                .label("Rate")
                .integer(TAG_RATE,
                        manaMode == ManaMode.EXT
                                ? "Mana extraction rate|(max " + maxrate + ")"
                                : "Mana insertion rate|(max " + maxrate + ")",
                        rate, 36, maxrate)
                .shift(10)
                .label(manaMode == ManaMode.EXT ? "Min" : "Max")
                .integer(TAG_MINMAX,
                        manaMode == ManaMode.EXT
                                ? "Keep this amount of|mana in source"
                                : "Disable insertion if|mana level is too high",
                        minmax, 36)
                .nl();
    }

    private static final Set<String> INSERT_TAGS = ImmutableSet.of(
            TAG_MODE, TAG_RS, TAG_COLOR + "0", TAG_COLOR + "1", TAG_COLOR + "2", TAG_COLOR + "3",
            TAG_RATE, TAG_MINMAX, TAG_PRIORITY
    );

    private static final Set<String> EXTRACT_TAGS = ImmutableSet.of(
            TAG_MODE, TAG_RS, TAG_COLOR + "0", TAG_COLOR + "1", TAG_COLOR + "2", TAG_COLOR + "3",
            TAG_RATE, TAG_MINMAX, TAG_PRIORITY, TAG_SPEED
    );

    @Override
    public boolean isEnabled(String tag) {
        if (manaMode == ManaMode.INS) {
            if (tag.equals(TAG_FACING)) {
                return advanced;
            }
            return INSERT_TAGS.contains(tag);
        } else {
            if (tag.equals(TAG_FACING)) {
                return advanced;
            }
            return EXTRACT_TAGS.contains(tag);
        }
    }

    @Override
    public void update(Map<String, Object> data) {
        super.update(data);

        if (data.containsKey(TAG_MODE)) {
            manaMode = ManaMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
        } else {
            manaMode = ManaMode.INS;
        }

        rate = (Integer) data.get(TAG_RATE);
        minmax = (Integer) data.get(TAG_MINMAX);
        priority = (Integer) data.get(TAG_PRIORITY);

        if (data.containsKey(TAG_SPEED) && data.get(TAG_SPEED) != null) {
            speed = Integer.parseInt((String) data.get(TAG_SPEED)) / 10;
            if (speed == 0) {
                speed = 2;
            }
        } else if (manaMode == ManaMode.EXT) {
            speed = 2;
        }
    }

    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();
        super.writeToJsonInternal(object);
        setEnumSafe(object, "manamode", manaMode);
        setIntegerSafe(object, "priority", priority);
        setIntegerSafe(object, "rate", rate);
        setIntegerSafe(object, "minmax", minmax);
        setIntegerSafe(object, "speed", speed);
        if (rate != null && rate > XNetAdditionsConfig.maxManaRateNormal) {
            object.add("advancedneeded", new JsonPrimitive(true));
        }
        if (speed == 1) {
            object.add("advancedneeded", new JsonPrimitive(true));
        }
        return object;
    }

    @Override
    public void readFromJson(JsonObject object) {
        super.readFromJsonInternal(object);
        if (object.has("manamode")) {
            manaMode = ManaMode.valueOf(object.get("manamode").getAsString().toUpperCase());
        }
        priority = object.has("priority") ? object.get("priority").getAsInt() : null;
        rate = object.has("rate") ? object.get("rate").getAsInt() : null;
        minmax = object.has("minmax") ? object.get("minmax").getAsInt() : null;
        speed = object.has("speed") ? object.get("speed").getAsInt() : 2;
        if (speed == 0) {
            speed = 2;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        manaMode = ManaMode.values()[tag.getByte("manaMode")];

        if (tag.hasKey("priority")) {
            priority = tag.getInteger("priority");
        } else {
            priority = null;
        }

        if (tag.hasKey("rate")) {
            rate = tag.getInteger("rate");
        } else {
            rate = null;
        }

        if (tag.hasKey("minmax")) {
            minmax = tag.getInteger("minmax");
        } else {
            minmax = null;
        }

        speed = tag.getInteger("speed");
        if (speed == 0) {
            speed = 2;
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setByte("manaMode", (byte) manaMode.ordinal());
        if (priority != null) {
            tag.setInteger("priority", priority);
        }
        if (rate != null) {
            tag.setInteger("rate", rate);
        }
        if (minmax != null) {
            tag.setInteger("minmax", minmax);
        }
        tag.setInteger("speed", speed);
    }
}
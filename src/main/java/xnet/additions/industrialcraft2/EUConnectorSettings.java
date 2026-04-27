package xnet.additions.industrialcraft2;

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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public class EUConnectorSettings extends AbstractConnectorSettings {

    public static final ResourceLocation iconGuiElements = new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";
    public static final String TAG_RATE = "rate";
    public static final String TAG_PRIORITY = "priority";

    public enum EUMode {
        INS,
        EXT
    }

    private static final Set<String> INSERT_TAGS = ImmutableSet.of(
            TAG_MODE,
            TAG_RS,
            TAG_COLOR + "0",
            TAG_COLOR + "1",
            TAG_COLOR + "2",
            TAG_COLOR + "3",
            TAG_RATE,
            TAG_PRIORITY
    );

    private static final Set<String> EXTRACT_TAGS = ImmutableSet.of(
            TAG_MODE,
            TAG_RS,
            TAG_COLOR + "0",
            TAG_COLOR + "1",
            TAG_COLOR + "2",
            TAG_COLOR + "3",
            TAG_RATE,
            TAG_PRIORITY
    );

    private EUMode euMode = EUMode.INS;

    @Nullable
    private Integer priority = 0;

    @Nullable
    private Integer rate = null;

    public EUConnectorSettings(@Nonnull EnumFacing side) {
        super(side);
    }

    public EUMode getEuMode() {
        return euMode;
    }

    @Nonnull
    public Integer getPriority() {
        return priority == null ? 0 : priority;
    }

    @Nullable
    public Integer getRate() {
        return rate;
    }

    private int getMaxRate(boolean advanced) {
        return advanced ? XNetAdditionsConfig.maxEuRateAdvanced : XNetAdditionsConfig.maxEuRateNormal;
    }

    private int getMaxRate() {
        return getMaxRate(advanced);
    }

    private void sanitizeRate(boolean advanced) {
        if (rate == null) {
            return;
        }

        int maxRate = getMaxRate(advanced);

        if (rate < 0) {
            rate = 0;
        } else if (rate > maxRate) {
            rate = maxRate;
        }
    }

    private void sanitizeRate() {
        sanitizeRate(advanced);
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        switch (euMode) {
            case INS:
                return new IndicatorIcon(iconGuiElements, 0, 70, 13, 10);
            case EXT:
            default:
                return new IndicatorIcon(iconGuiElements, 13, 70, 13, 10);
        }
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        advanced = gui.isAdvanced();
        sanitizeRate();

        int maxRate = getMaxRate();

        sideGui(gui);
        colorsGui(gui);
        redstoneGui(gui);

        gui.nl()
                .choices(TAG_MODE, "Insert or extract mode", euMode, EUMode.values())
                .nl()

                .label("Pri")
                .integer(TAG_PRIORITY, "Insertion priority", priority, 36)
                .nl()

                .label("Rate")
                .integer(
                        TAG_RATE,
                        (euMode == EUMode.EXT ? "EU extraction rate" : "EU insertion rate") + "|(max " + maxRate + " EU/t)",
                        rate,
                        60
                )
                .nl();
    }

    @Override
    public boolean isEnabled(String tag) {
        /*
         * IC2 EU direct machine IO should use the physical side touching the connector.
         * Do not expose XNet advanced side override here.
         */
        if (tag.equals(TAG_FACING)) {
            return false;
        }

        switch (euMode) {
            case INS:
                return INSERT_TAGS.contains(tag);
            case EXT:
            default:
                return EXTRACT_TAGS.contains(tag);
        }
    }

    @Override
    public void update(Map<String, Object> data) {
        super.update(data);

        Object modeObj = data.get(TAG_MODE);
        if (modeObj instanceof String) {
            try {
                euMode = EUMode.valueOf(((String) modeObj).toUpperCase());
            } catch (IllegalArgumentException e) {
                euMode = EUMode.INS;
            }
        } else {
            euMode = EUMode.INS;
        }

        priority = data.get(TAG_PRIORITY) instanceof Integer ? (Integer) data.get(TAG_PRIORITY) : 0;
        rate = data.get(TAG_RATE) instanceof Integer ? (Integer) data.get(TAG_RATE) : null;
    }

    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();

        super.writeToJsonInternal(object);
        setEnumSafe(object, "eumode", euMode);
        setIntegerSafe(object, "priority", priority);
        setIntegerSafe(object, "rate", rate);

        if (rate != null && rate > XNetAdditionsConfig.maxEuRateNormal) {
            object.add("advancedneeded", new JsonPrimitive(true));
        }

        return object;
    }

    @Override
    public void readFromJson(JsonObject object) {
        super.readFromJsonInternal(object);

        try {
            euMode = getEnumSafe(object, "eumode", s -> EUMode.valueOf(s.toUpperCase()));
        } catch (RuntimeException e) {
            euMode = EUMode.INS;
        }

        if (euMode == null) {
            euMode = EUMode.INS;
        }

        priority = getIntegerSafe(object, "priority");
        rate = getIntegerSafe(object, "rate");

        if (rate != null && rate < 0) {
            rate = 0;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);

        if (tag.hasKey("euMode")) {
            int mode = tag.getByte("euMode");
            if (mode >= 0 && mode < EUMode.values().length) {
                euMode = EUMode.values()[mode];
            } else {
                euMode = EUMode.INS;
            }
        } else {
            euMode = EUMode.INS;
        }

        if (tag.hasKey("priority")) {
            priority = tag.getInteger("priority");
        } else {
            priority = null;
        }

        if (tag.hasKey("rate")) {
            rate = tag.getInteger("rate");
            if (rate < 0) {
                rate = 0;
            }
        } else {
            rate = null;
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);

        tag.setByte("euMode", (byte) euMode.ordinal());

        if (priority != null) {
            tag.setInteger("priority", priority);
        }

        if (rate != null) {
            tag.setInteger("rate", rate);
        }
    }
}
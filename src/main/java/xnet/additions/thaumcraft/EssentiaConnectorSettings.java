package xnet.additions.thaumcraft;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcjty.lib.varia.ItemStackList;
import mcjty.lib.varia.ItemStackTools;
import mcjty.xnet.XNet;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.aspects.IEssentiaContainerItem;
import xnet.additions.config.XNetAdditionsConfig;
import xnet.additions.util.ConnectorSpeedHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class EssentiaConnectorSettings extends AbstractConnectorSettings {

    public static final ResourceLocation iconGuiElements =
            new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";
    public static final String TAG_RATE = "rate";
    public static final String TAG_MINMAX = "minmax";
    public static final String TAG_PRIORITY = "priority";
    public static final String TAG_FILTER = "flt";
    public static final String TAG_SPEED = "speed";
    public static final String TAG_BLACKLIST = "blacklist";

    public static final int FILTER_SIZE = 18;

    public enum EssentiaMode {
        INS,
        EXT
    }

    private static final Set<String> INSERT_TAGS = ImmutableSet.of(
            TAG_MODE, TAG_RS, TAG_COLOR + "0", TAG_COLOR + "1", TAG_COLOR + "2", TAG_COLOR + "3",
            TAG_RATE, TAG_MINMAX, TAG_PRIORITY, TAG_BLACKLIST
    );

    private static final Set<String> EXTRACT_TAGS = ImmutableSet.of(
            TAG_MODE, TAG_RS, TAG_COLOR + "0", TAG_COLOR + "1", TAG_COLOR + "2", TAG_COLOR + "3",
            TAG_RATE, TAG_MINMAX, TAG_PRIORITY, TAG_SPEED, TAG_BLACKLIST
    );

    protected EssentiaMode essentiaMode = EssentiaMode.INS;
    @Nullable protected Integer priority = 0;
    @Nullable protected Integer rate = null;
    @Nullable protected Integer minmax = null;
    protected int speed = 2;
    protected boolean blacklist = false;

    protected ItemStackList filters = ItemStackList.create(FILTER_SIZE);
    @Nullable private Predicate<Aspect> matcher = null;

    public EssentiaConnectorSettings(@Nonnull EnumFacing side) {
        super(side);
    }

    public boolean isBlacklist() {
        return blacklist;
    }
    private int getMaxRate(boolean advanced) {
        return advanced ? XNetAdditionsConfig.maxEssentiaRateAdvanced : XNetAdditionsConfig.maxEssentiaRateNormal;
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

    public EssentiaMode getEssentiaMode() {
        return essentiaMode;
    }

    @Nullable
    public Integer getRate() {
        return rate;
    }

    @Nullable
    public Integer getMinmax() {
        return minmax;
    }

    public int getPriority() {
        return priority == null ? 0 : priority;
    }

    public int getSpeed() {
        return speed;
    }

    public ItemStackList getFilters() {
        return filters;
    }

    public boolean matches(@Nullable Aspect aspect) {
        return getMatcher().test(aspect);
    }

    @Nonnull
    public Predicate<Aspect> getMatcher() {
        if (matcher != null) {
            return matcher;
        }

        boolean hasConfiguredFilter = false;
        Set<String> allowedAspectTags = new HashSet<>();

        for (ItemStack filterStack : filters) {
            if (filterStack.isEmpty()) {
                continue;
            }
            hasConfiguredFilter = true;

            if (filterStack.getItem() instanceof IEssentiaContainerItem) {
                IEssentiaContainerItem containerItem = (IEssentiaContainerItem) filterStack.getItem();
                AspectList aspectList = containerItem.getAspects(filterStack);
                if (aspectList != null) {
                    Aspect[] aspects = aspectList.getAspects();
                    if (aspects != null) {
                        for (Aspect aspect : aspects) {
                            if (aspect != null) {
                                allowedAspectTags.add(aspect.getTag());
                            }
                        }
                    }
                }
            }
        }

        if (!hasConfiguredFilter) {
            matcher = aspect -> true;
        } else if (allowedAspectTags.isEmpty()) {
            matcher = aspect -> false;
        } else {
            matcher = aspect -> {
                boolean match = aspect != null && allowedAspectTags.contains(aspect.getTag());
                return blacklist ? !match : match;
            };
        }

        return matcher;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        switch (essentiaMode) {
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
        sanitizeSpeed(advanced);

        int maxRate = getMaxRate();
        String[] speeds = ConnectorSpeedHelper.getSpeedChoices(advanced);

        sideGui(gui);
        colorsGui(gui);
        redstoneGui(gui);

        gui.nl()
                .choices(TAG_MODE, "Insert or extract mode", essentiaMode, EssentiaMode.values())
                .choices(TAG_SPEED, "Number of ticks for each operation", Integer.toString(speed * 10), speeds)
                .nl()

                .label("Pri")
                .integer(TAG_PRIORITY, "Insertion priority", priority, 36)
                .shift(5)
                .label("Rate")
                .integer(
                        TAG_RATE,
                        (essentiaMode == EssentiaMode.EXT ? "Essentia extraction rate" : "Essentia insertion rate")
                                + "|(max " + maxRate + ")",
                        rate,
                        36,
                        maxRate
                )
                .nl()
                .toggleText(TAG_BLACKLIST, "Enable blacklist mode", "BL", blacklist)
                .shift(10)
                .label(essentiaMode == EssentiaMode.EXT ? "Min" : "Max")
                .integer(
                        TAG_MINMAX,
                        essentiaMode == EssentiaMode.EXT
                                ? "Keep this amount of|essentia in the source"
                                : "Disable insertion if|essentia level is too high",
                        minmax,
                        36
                )
                .nl();

        for (int i = 0; i < FILTER_SIZE; i++) {
            gui.ghostSlot(TAG_FILTER + i, filters.get(i));
        }
    }

    @Override
    public boolean isEnabled(String tag) {
        if (tag.startsWith(TAG_FILTER)) {
            return true;
        }
        if (tag.equals(TAG_FACING)) {
            return advanced;
        }

        switch (essentiaMode) {
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

        if (data.containsKey(TAG_MODE)) {
            essentiaMode = EssentiaMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
        } else {
            essentiaMode = EssentiaMode.INS;
        }

        priority = (Integer) data.get(TAG_PRIORITY);
        rate = (Integer) data.get(TAG_RATE);
        minmax = (Integer) data.get(TAG_MINMAX);
        blacklist = Boolean.TRUE.equals(data.get(TAG_BLACKLIST));

        if (data.containsKey(TAG_SPEED) && data.get(TAG_SPEED) != null) {
            speed = Integer.parseInt((String) data.get(TAG_SPEED)) / 10;
            if (speed == 0) {
                speed = 2;
            }
        } else {
            speed = 2;
        }

        for (int i = 0; i < FILTER_SIZE; i++) {
            Object o = data.get(TAG_FILTER + i);
            if (o instanceof ItemStack) {
                filters.set(i, (ItemStack) o);
            } else {
                filters.set(i, ItemStack.EMPTY);
            }
        }

        matcher = null;
    }
/*
    @Nullable
    private EnumFacing forcedFacingOverride = null;

    @Override
    @Nonnull
    public EnumFacing getFacing() {
        return forcedFacingOverride == null ? super.getFacing() : forcedFacingOverride;
    }

    public void setForcedFacingOverride(@Nullable EnumFacing facing) {
        this.forcedFacingOverride = facing;
    }

    @Nullable
    public EnumFacing getForcedFacingOverride() {
        return forcedFacingOverride;
    }

    public boolean hasForcedFacingOverride() {
        return forcedFacingOverride != null;
    }
    */

    /*
    public void sanitizeSettings(boolean advanced) {
        super.sanitizeSettings(advanced);
        sanitizeRate(advanced);
        sanitizeSpeed(advanced);
    }
     */

    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();
        super.writeToJsonInternal(object);

        setEnumSafe(object, "essentiamode", essentiaMode);
        setIntegerSafe(object, "priority", priority);
        setIntegerSafe(object, "rate", rate);
        setIntegerSafe(object, "minmax", minmax);
        setIntegerSafe(object, "speed", speed);
        object.add("blacklist", new JsonPrimitive(blacklist));

        for (int i = 0; i < FILTER_SIZE; i++) {
            if (!filters.get(i).isEmpty()) {
                object.add("filter" + i, ItemStackTools.itemStackToJson(filters.get(i)));
            }
        }

        if (rate != null && rate > XNetAdditionsConfig.maxEssentiaRateNormal) {
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

        essentiaMode = getEnumSafe(object, "essentiamode", s -> EssentiaMode.valueOf(s.toUpperCase()));
        if (essentiaMode == null) {
            essentiaMode = EssentiaMode.INS;
        }

        priority = getIntegerSafe(object, "priority");
        rate = getIntegerSafe(object, "rate");
        minmax = getIntegerSafe(object, "minmax");
        blacklist = getBoolSafe(object, "blacklist");

        speed = getIntegerNotNull(object, "speed");
        if (speed == 0) {
            speed = 2;
        }

        for (int i = 0; i < FILTER_SIZE; i++) {
            if (object.has("filter" + i)) {
                filters.set(i, ItemStackTools.jsonToItemStack(object.get("filter" + i).getAsJsonObject()));
            } else {
                filters.set(i, ItemStack.EMPTY);
            }
        }

        matcher = null;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);

        if (tag.hasKey("essentiaMode")) {
            essentiaMode = EssentiaMode.values()[tag.getByte("essentiaMode")];
        } else {
            essentiaMode = EssentiaMode.INS;
        }

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

        blacklist = tag.getBoolean("blacklist");
        speed = tag.getInteger("speed");
        if (speed == 0) {
            speed = 2;
        }

        for (int i = 0; i < FILTER_SIZE; i++) {
            if (tag.hasKey("filter" + i)) {
                filters.set(i, new ItemStack(tag.getCompoundTag("filter" + i)));
            } else {
                filters.set(i, ItemStack.EMPTY);
            }
        }

        matcher = null;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);

        tag.setByte("essentiaMode", (byte) essentiaMode.ordinal());

        if (priority != null) {
            tag.setInteger("priority", priority);
        }
        if (rate != null) {
            tag.setInteger("rate", rate);
        }
        if (minmax != null) {
            tag.setInteger("minmax", minmax);
        }

        tag.setBoolean("blacklist", blacklist);
        tag.setInteger("speed", speed);

        for (int i = 0; i < FILTER_SIZE; i++) {
            if (!filters.get(i).isEmpty()) {
                NBTTagCompound itemTag = new NBTTagCompound();
                filters.get(i).writeToNBT(itemTag);
                tag.setTag("filter" + i, itemTag);
            }
        }
    }
}
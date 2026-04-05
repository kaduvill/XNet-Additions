package xnet.additions.mekanism;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcjty.lib.varia.ItemStackTools;
import mcjty.xnet.XNet;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.AbstractConnectorSettings;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import xnet.additions.config.XNetAdditionsConfig;
import xnet.additions.util.ConnectorSpeedHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public class GasConnectorSettings extends AbstractConnectorSettings {



	public static final ResourceLocation iconGuiElements =
			new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

	public static final String TAG_MODE = "mode";
	public static final String TAG_FILTER = "filter";
	public static final String TAG_PRIORITY = "priority";
	public static final String TAG_RATE = "rate";
	public static final String TAG_SPEED = "speed";

	private static final Set<String> INSERT_TAGS = ImmutableSet.of(
			TAG_MODE, TAG_FILTER, TAG_PRIORITY, TAG_RATE,
			TAG_RS, TAG_COLOR + "0", TAG_COLOR + "1", TAG_COLOR + "2", TAG_COLOR + "3"
	);

	private static final Set<String> EXTRACT_TAGS = ImmutableSet.of(
			TAG_MODE, TAG_FILTER, TAG_PRIORITY, TAG_RATE, TAG_SPEED,
			TAG_RS, TAG_COLOR + "0", TAG_COLOR + "1", TAG_COLOR + "2", TAG_COLOR + "3"
	);

	public enum GasMode {
		INS,
		EXT
	}

	protected GasMode gasMode = GasMode.INS;
		@Nullable protected Integer priority = 0;
	@Nullable protected Integer rate = null;
	protected int speed = 2;
	protected ItemStack filterStack = ItemStack.EMPTY;

	public GasConnectorSettings(@Nonnull EnumFacing side) {
		super(side);
	}

	private int getMaxRate(boolean advanced) {
		return advanced ? XNetAdditionsConfig.maxGasRateAdvanced : XNetAdditionsConfig.maxGasRateNormal;
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

	@Nullable
	public Integer getRate() {
		return rate;
	}

	public int getPriority() {
		return priority == null ? 0 : priority;
	}

	public int getSpeed() {
		return speed;
	}

	public GasMode getGasMode() {
		return gasMode;
	}

	/*
	public ItemStack getFilterStack() {
		return filterStack;
	}
	*/

	@Nullable
	public GasStack getFilter() {
		if (filterStack.isEmpty()) {
			return null;
		}
		if (filterStack.getItem() instanceof IGasItem) {
			IGasItem item = (IGasItem) filterStack.getItem();
			return item.getGas(filterStack);
		}
		return null;
	}

	@Nullable
	@Override
	public IndicatorIcon getIndicatorIcon() {
		switch (gasMode) {
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
				.choices(TAG_MODE, "Insert or extract mode", gasMode, GasMode.values());

		if (gasMode == GasMode.EXT) {
			gui.choices(TAG_SPEED, "Number of ticks for each operation", Integer.toString(speed * 10), speeds);
		}

		gui.nl()
				.label("Pri")
				.integer(TAG_PRIORITY, "Insertion priority", priority, 36)
				.nl()
				.label("Rate")
				.integer(
						TAG_RATE,
						(gasMode == GasMode.EXT ? "Gas extraction rate" : "Gas insertion rate") + "|(max " + maxRate + ")",
						rate,
						36,
						maxRate
				)
				.nl()
				.label("Filter");

		gui.ghostSlot(TAG_FILTER, filterStack);
	}

	@Override
	public boolean isEnabled(String tag) {
		if (tag.equals(TAG_FACING)) {
			return advanced;
		}
		switch (gasMode) {
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
			gasMode = GasMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
		} else {
			gasMode = GasMode.INS;
		}

		priority = (Integer) data.get(TAG_PRIORITY);
		rate = (Integer) data.get(TAG_RATE);

		if (data.containsKey(TAG_SPEED) && data.get(TAG_SPEED) != null) {
			speed = Integer.parseInt((String) data.get(TAG_SPEED)) / 10;
			if (speed == 0) {
				speed = 2;
			}
		} else if (gasMode == GasMode.EXT) {
			speed = 2;
		}

		Object filterObj = data.get(TAG_FILTER);
		if (filterObj instanceof ItemStack) {
			filterStack = (ItemStack) filterObj;
		} else {
			filterStack = ItemStack.EMPTY;
		}
	}

	private void sanitizeSpeed(boolean advanced) {
		speed = ConnectorSpeedHelper.sanitizeSpeed(speed, advanced);
	}

	@Override
	public JsonObject writeToJson() {
		JsonObject object = new JsonObject();
		super.writeToJsonInternal(object);

		setEnumSafe(object, "insertionmode", gasMode);
		setIntegerSafe(object, "priority", priority);
		setIntegerSafe(object, "rate", rate);
		setIntegerSafe(object, "speed", speed);

		if (!filterStack.isEmpty()) {
			object.add("filter", ItemStackTools.itemStackToJson(filterStack));
		}

		if (rate != null && rate > XNetAdditionsConfig.maxGasRateNormal) {
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

		gasMode = getEnumSafe(object, "insertionmode", s -> GasMode.valueOf(s.toUpperCase()));
		if (gasMode == null) {
			gasMode = GasMode.INS;
		}

		priority = getIntegerSafe(object, "priority");
		rate = getIntegerSafe(object, "rate");

		speed = getIntegerNotNull(object, "speed");
		if (speed == 0) {
			speed = 2;
		}

		if (object.has("filter")) {
			filterStack = ItemStackTools.jsonToItemStack(object.get("filter").getAsJsonObject());
		} else {
			filterStack = ItemStack.EMPTY;
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);

		if (tag.hasKey("mode")) {
			gasMode = GasMode.values()[tag.getByte("mode")];
		} else {
			gasMode = GasMode.INS;
		}

		if (tag.hasKey("filter")) {
			filterStack = new ItemStack(tag.getCompoundTag("filter"));
		} else {
			filterStack = ItemStack.EMPTY;
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

		speed = tag.getInteger("speed");
		if (speed == 0) {
			speed = 2;
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);

		tag.setByte("mode", (byte) gasMode.ordinal());

		if (!filterStack.isEmpty()) {
			NBTTagCompound itemTag = new NBTTagCompound();
			filterStack.writeToNBT(itemTag);
			tag.setTag("filter", itemTag);
		}

		if (priority != null) {
			tag.setInteger("priority", priority);
		}

		if (rate != null) {
			tag.setInteger("rate", rate);
		}

		tag.setInteger("speed", speed);
	}
}
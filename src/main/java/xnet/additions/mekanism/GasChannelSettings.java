package xnet.additions.mekanism;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcjty.lib.varia.WorldTools;
import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IControllerContext;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.DefaultChannelSettings;
import mcjty.xnet.api.keys.ConsumerId;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.cables.ConnectorBlock;
import mcjty.xnet.config.ConfigSetup;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasHandler;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import org.apache.commons.lang3.tuple.Pair;
import xnet.additions.XNetAdditions;
import xnet.additions.config.XNetAdditionsConfig;
import xnet.additions.util.ConnectorSpeedHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class GasChannelSettings extends DefaultChannelSettings implements IChannelSettings {

	public static final String TAG_MODE = "mode";

	public enum ChannelMode {
		PRIORITY,
		ROUNDROBIN,
		DISTRIBUTE
	}

	@CapabilityInject(IGasHandler.class)
	private static Capability<IGasHandler> GAS_HANDLER_CAPABILITY;

	private ChannelMode channelMode = ChannelMode.PRIORITY;
	private int delay = 0;
	private int roundRobinOffset = 0;

	// Cache data
	private Map<SidedConsumer, GasConnectorSettings> gasExtractors = null;
	private List<Pair<SidedConsumer, GasConnectorSettings>> gasConsumers = null;

	public ChannelMode getChannelMode() {
		return channelMode;
	}

	private static int getEffectiveSpeed(GasConnectorSettings connector, World world, BlockPos pos) {
		boolean advanced = ConnectorBlock.isAdvancedConnector(world, pos);
		int speed = connector.getSpeed();
		return ConnectorSpeedHelper.isValidSpeed(speed, advanced) ? speed : 2;
	}

	@Override
	public JsonObject writeToJson() {
		JsonObject object = new JsonObject();
		object.add(TAG_MODE, new JsonPrimitive(channelMode.name()));
		return object;
	}

	@Override
	public void readFromJson(JsonObject data) {
		channelMode = ChannelMode.PRIORITY;

		if (data == null || !data.has(TAG_MODE) || data.get(TAG_MODE).isJsonNull()) {
			return;
		}

		try {
			channelMode = ChannelMode.valueOf(data.get(TAG_MODE).getAsString().toUpperCase());
		} catch (IllegalArgumentException e) {
			channelMode = ChannelMode.PRIORITY;
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		if (tag.hasKey(TAG_MODE)) {
			int mode = tag.getByte(TAG_MODE);
			if (mode >= 0 && mode < ChannelMode.values().length) {
				channelMode = ChannelMode.values()[mode];
			} else {
				channelMode = ChannelMode.PRIORITY;
			}
		} else {
			channelMode = ChannelMode.PRIORITY;
		}

		delay = tag.getInteger("delay");
		roundRobinOffset = tag.getInteger("offset");
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		tag.setByte(TAG_MODE, (byte) channelMode.ordinal());
		tag.setInteger("delay", delay);
		tag.setInteger("offset", roundRobinOffset);
	}

	@Override
	public void tick(int channel, IControllerContext context) {
		delay--;
		if (delay <= 0) {
			delay = 200 * 6;
		}
		if (delay % 10 != 0) {
			return;
		}
		int d = delay / 10;

		updateCache(channel, context);

		World world = context.getControllerWorld();
		for (Map.Entry<SidedConsumer, GasConnectorSettings> entry : gasExtractors.entrySet()) {
			GasConnectorSettings settings = entry.getValue();
			ConsumerId consumerId = entry.getKey().getConsumerId();

			BlockPos extractorPos = context.findConsumerPosition(consumerId);
			if (extractorPos == null) {
				continue;
			}

			int speed = getEffectiveSpeed(settings, world, extractorPos);
			int phase = Math.floorMod(consumerId.getId(), speed);
			if (d % speed != phase) {
				continue;
			}

			EnumFacing side = entry.getKey().getSide();
			BlockPos pos = extractorPos.offset(side);

			if (!WorldTools.chunkLoaded(world, pos)) {
				continue;
			}
			if (checkRedstone(world, settings, extractorPos)) {
				continue;
			}
			if (!context.matchColor(settings.getColorsMask())) {
				continue;
			}

			TileEntity te = world.getTileEntity(pos);
			IGasHandler handler = getGasHandlerAt(te, settings.getFacing());
			if (handler == null) {
				continue;
			}

			tickGasHandler(context, settings, extractorPos, handler);
		}
	}

	private void tickGasHandler(IControllerContext context, GasConnectorSettings settings, BlockPos extractorPos, IGasHandler handler) {
		int rate = getRate(settings, context.getControllerWorld(), extractorPos);
		GasStack stack = fetchGas(handler, true, settings.getMatcher(), settings.getFacing(), rate);
		if (stack == null) {
			return;
		}

		if (context.checkAndConsumeRF(ConfigSetup.controllerOperationRFT.get())) {
			transferGas(handler, stack, settings, context);
		}
	}

	private static int getRate(GasConnectorSettings connector, World world, BlockPos pos) {
		int maxRate = ConnectorBlock.isAdvancedConnector(world, pos)
				? XNetAdditionsConfig.maxGasRateAdvanced
				: XNetAdditionsConfig.maxGasRateNormal;

		Integer rate = connector.getRate();
		if (rate != null) {
			return Math.max(0, Math.min(rate, maxRate));
		}

		return maxRate;
	}

	@Override
	public void cleanCache() {
		gasExtractors = null;
		gasConsumers = null;
	}

	@Nullable
	private GasStack fetchGas(IGasHandler handler, boolean simulate, Predicate<GasStack> matcher, EnumFacing side, int extractAmount) {
		if (extractAmount <= 0) {
			return null;
		}

		GasStack stack = handler.drawGas(side, extractAmount, !simulate);
		if (stack == null || stack.amount <= 0) {
			return null;
		}

		if (matcher != null && !matcher.test(stack)) {
			return null;
		}

		return stack;
	}

	public boolean transferGas(@Nonnull IGasHandler from,
							   @Nonnull GasStack stack,
							   GasConnectorSettings extractSettings,
							   @Nonnull IControllerContext context) {
		if (gasConsumers == null || gasConsumers.isEmpty()) {
			return false;
		}

		if (channelMode == ChannelMode.DISTRIBUTE) {
			return transferGasDistribute(from, stack, extractSettings, context);
		}

		World world = context.getControllerWorld();

		if (channelMode == ChannelMode.PRIORITY) {
			roundRobinOffset = 0;
		}

		int originalAmount = stack.amount;

		for (int j = 0; j < gasConsumers.size(); j++) {
			roundRobinOffset = roundRobinOffset % gasConsumers.size();
			int i = roundRobinOffset;
			roundRobinOffset++;

			Pair<SidedConsumer, GasConnectorSettings> entry = gasConsumers.get(i);
			GasConnectorSettings insertSettings = entry.getValue();

			if (!insertSettings.getMatcher().test(stack)) {
				continue;
			}

			BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
			if (consumerPos == null) {
				continue;
			}

			if (checkRedstone(world, insertSettings, consumerPos)) {
				continue;
			}
			if (!context.matchColor(insertSettings.getColorsMask())) {
				continue;
			}

			EnumFacing side = entry.getKey().getSide();
			BlockPos pos = consumerPos.offset(side);
			if (!WorldTools.chunkLoaded(world, pos)) {
				continue;
			}

			TileEntity te = world.getTileEntity(pos);
			IGasHandler handler = getGasHandlerAt(te, insertSettings.getFacing());
			if (handler == null) {
				continue;
			}

			int remaining = insertGasToHandler(from, handler, stack, extractSettings, insertSettings, world, consumerPos);

			if (channelMode == ChannelMode.ROUNDROBIN && originalAmount != remaining) {
				return true;
			}
			if (remaining <= 0) {
				return true;
			}

			stack.amount = remaining;
		}

		return originalAmount != stack.amount;
	}

	private boolean transferGasDistribute(@Nonnull IGasHandler from,
										  @Nonnull GasStack stack,
										  GasConnectorSettings extractSettings,
										  @Nonnull IControllerContext context) {
		Map<Pair<SidedConsumer, GasConnectorSettings>, Integer> distribution = new LinkedHashMap<>();
		int planned = getOverallAndDistribution(distribution, context, stack);
		if (planned <= 0) {
			return false;
		}

		World world = context.getControllerWorld();
		int originalAmount = stack.amount;

		for (Map.Entry<Pair<SidedConsumer, GasConnectorSettings>, Integer> plannedEntry : distribution.entrySet()) {
			if (stack.amount <= 0) {
				break;
			}

			Pair<SidedConsumer, GasConnectorSettings> entry = plannedEntry.getKey();
			GasConnectorSettings insertSettings = entry.getValue();

			BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
			if (consumerPos == null) {
				continue;
			}

			if (checkRedstone(world, insertSettings, consumerPos)) {
				continue;
			}
			if (!context.matchColor(insertSettings.getColorsMask())) {
				continue;
			}

			EnumFacing side = entry.getKey().getSide();
			BlockPos pos = consumerPos.offset(side);
			if (!WorldTools.chunkLoaded(world, pos)) {
				continue;
			}

			TileEntity te = world.getTileEntity(pos);
			IGasHandler handler = getGasHandlerAt(te, insertSettings.getFacing());
			if (handler == null) {
				continue;
			}

			int desired = Math.min(plannedEntry.getValue(), stack.amount);
			int remaining = insertGasToHandler(from, handler, stack, extractSettings, insertSettings, world, consumerPos, desired);
			stack.amount = remaining;
		}

		return originalAmount != stack.amount;
	}

	private int getOverallAndDistribution(Map<Pair<SidedConsumer, GasConnectorSettings>, Integer> distribution,
										  @Nonnull IControllerContext context,
										  @Nonnull GasStack stack) {
		if (gasConsumers == null || gasConsumers.isEmpty()) {
			return 0;
		}

		World world = context.getControllerWorld();
		Map<Pair<SidedConsumer, GasConnectorSettings>, Integer> fillPossible = new LinkedHashMap<>();
		int filledOverall = 0;
		int total = stack.amount;

		for (Pair<SidedConsumer, GasConnectorSettings> entry : gasConsumers) {
			GasConnectorSettings settings = entry.getValue();

			if (!settings.getMatcher().test(stack)) {
				continue;
			}

			BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
			if (consumerPos == null) {
				continue;
			}

			if (checkRedstone(world, settings, consumerPos)) {
				continue;
			}
			if (!context.matchColor(settings.getColorsMask())) {
				continue;
			}

			EnumFacing side = entry.getKey().getSide();
			BlockPos pos = consumerPos.offset(side);
			if (!WorldTools.chunkLoaded(world, pos)) {
				continue;
			}

			TileEntity te = world.getTileEntity(pos);
			IGasHandler handler = getGasHandlerAt(te, settings.getFacing());
			if (handler == null) {
				continue;
			}

			int rate = getRate(settings, world, consumerPos);
			int toInsert = Math.min(rate, total);
			if (toInsert <= 0) {
				continue;
			}

			GasStack copy = stack.copy();
			copy.amount = toInsert;

			if (copy.getGas() == null || !handler.canReceiveGas(settings.getFacing(), copy.getGas())) {
				continue;
			}

			int possible = handler.receiveGas(settings.getFacing(), copy, false);
			if (possible <= 0) {
				continue;
			}

			filledOverall += possible;
			fillPossible.put(entry, possible);
		}

		if (filledOverall <= 0) {
			return 0;
		}

		int plannedOverall = 0;
		for (Map.Entry<Pair<SidedConsumer, GasConnectorSettings>, Integer> entry : fillPossible.entrySet()) {
			int share = (int) Math.ceil(total * ((double) entry.getValue() / filledOverall));
			if (share > total - plannedOverall) {
				share = total - plannedOverall;
			}
			if (share <= 0) {
				continue;
			}

			distribution.put(entry.getKey(), share);
			plannedOverall += share;

			if (plannedOverall >= total) {
				break;
			}
		}

		return plannedOverall;
	}

	public int insertGasToHandler(@Nonnull IGasHandler from,
								  @Nonnull IGasHandler to,
								  @Nonnull GasStack stack,
								  GasConnectorSettings extractSettings,
								  GasConnectorSettings insertSettings,
								  World world,
								  BlockPos consumerPos) {
		return insertGasToHandler(from, to, stack, extractSettings, insertSettings, world, consumerPos, stack.amount);
	}

	public int insertGasToHandler(@Nonnull IGasHandler from,
								  @Nonnull IGasHandler to,
								  @Nonnull GasStack stack,
								  GasConnectorSettings extractSettings,
								  GasConnectorSettings insertSettings,
								  World world,
								  BlockPos consumerPos,
								  int maxToInsert) {
		int total = stack.amount;
		int rate = getRate(insertSettings, world, consumerPos);
		int toInsert = Math.min(rate, Math.min(total, maxToInsert));

		if (toInsert <= 0) {
			return total;
		}

		GasStack stackToInsert = stack.copy();
		stackToInsert.amount = toInsert;

		if (stackToInsert.getGas() == null || !to.canReceiveGas(insertSettings.getFacing(), stackToInsert.getGas())) {
			return total;
		}

		int filled = to.receiveGas(insertSettings.getFacing(), stackToInsert, false);
		if (filled > 0) {
			GasStack realExtract = fetchGas(from, false, extractSettings.getMatcher(), extractSettings.getFacing(), filled);
			if (realExtract == null || realExtract.amount <= 0) {
				return total;
			}

			if (realExtract.amount < filled) {
				filled = realExtract.amount;
				realExtract.amount = filled;
			}

			int accepted = to.receiveGas(insertSettings.getFacing(), realExtract, true);
			if (accepted > 0) {
				return total - accepted;
			}
		}

		return total;
	}

	private void updateCache(int channel, IControllerContext context) {
		if (gasExtractors == null) {
			gasExtractors = new HashMap<>();
			gasConsumers = new ArrayList<>();

			Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
			for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
				GasConnectorSettings con = (GasConnectorSettings) entry.getValue();
				if (con.getGasMode() == GasConnectorSettings.GasMode.EXT) {
					gasExtractors.put(entry.getKey(), con);
				} else {
					gasConsumers.add(Pair.of(entry.getKey(), con));
				}
			}

			connectors = context.getRoutedConnectors(channel);
			for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
				GasConnectorSettings con = (GasConnectorSettings) entry.getValue();
				if (con.getGasMode() == GasConnectorSettings.GasMode.INS) {
					gasConsumers.add(Pair.of(entry.getKey(), con));
				}
			}

			gasConsumers.sort((o1, o2) ->
					Integer.compare(o2.getRight().getPriority(), o1.getRight().getPriority()));
		}
	}

	@Override
	public boolean isEnabled(String tag) {
		return true;
	}

	@Nullable
	@Override
	public IndicatorIcon getIndicatorIcon() {
		return new IndicatorIcon(XNetAdditions.ICON_GUIELEMENTS, 0, 0, 11, 10);
	}

	@Nullable
	@Override
	public String getIndicator() {
		return null;
	}

	@Override
	public void createGui(IEditorGui gui) {
		gui.nl().choices(TAG_MODE, "Gas distribution mode", channelMode, ChannelMode.values());
	}

	@Override
	public void update(Map<String, Object> data) {
		if (data.containsKey(TAG_MODE) && data.get(TAG_MODE) instanceof String) {
			try {
				channelMode = ChannelMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
			} catch (IllegalArgumentException e) {
				channelMode = ChannelMode.PRIORITY;
			}
		} else {
			channelMode = ChannelMode.PRIORITY;
		}
	}

	@Override
	public int getColors() {
		return 0;
	}

	@Nullable
	public static IGasHandler getGasHandlerAt(@Nullable TileEntity te, EnumFacing intSide) {
		if (te instanceof IGasHandler) {
			return (IGasHandler) te;
		}
		if (te != null && te.hasCapability(GAS_HANDLER_CAPABILITY, intSide)) {
			IGasHandler handler = te.getCapability(GAS_HANDLER_CAPABILITY, intSide);
			if (handler != null) {
				return handler;
			}
		}
		return null;
	}

	// Temporary compatibility helper so existing callers still compile.
	@Nullable
	public static IGasHandler getGasHandler(@Nullable TileEntity te, EnumFacing intSide) {
		return getGasHandlerAt(te, intSide);
	}
}
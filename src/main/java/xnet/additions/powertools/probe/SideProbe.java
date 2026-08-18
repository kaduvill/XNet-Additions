package xnet.additions.powertools.probe;

import io.netty.buffer.ByteBuf;
import mcjty.xnet.XNet;
import mcjty.xnet.apiimpl.energy.EnergyChannelSettings;
import mcjty.xnet.apiimpl.fluids.FluidChannelSettings;
import mcjty.xnet.apiimpl.items.ItemChannelSettings;
import mcjty.xnet.compat.RFToolsSupport;
import mcjty.xnet.setup.ModSetup;
import mekanism.api.gas.GasTankInfo;
import mekanism.api.gas.IGasHandler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.IItemHandler;
import xnet.additions.channel.advancedenergy.AdvancedEnergyChannelSettings;
import xnet.additions.channel.advancedenergy.AdvancedEnergyConnectorSettings;
import xnet.additions.channel.botania.ManaChannelSettings;
import xnet.additions.channel.industrialcraft2.EUChannelSettings;
import xnet.additions.channel.mekanism.GasChannelSettings;
import xnet.additions.channel.thaumcraft.EssentiaChannelSettings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public final class SideProbe {
    private static final int FLAG_ACCESS = 1;
    private static final int FLAG_INPUT = 2;
    private static final int FLAG_OUTPUT = 4;
    private static final int FLAG_FAILED = 8;
    private static final int VALID_FLAGS = FLAG_ACCESS | FLAG_INPUT | FLAG_OUTPUT | FLAG_FAILED;

    public enum Type {
        ITEM("Item", "xnet.item", true),
        FLUID("Fluid", "xnet.fluid", true),
        ENERGY("Energy", "xnet.energy", true),
        ADVANCED_ENERGY("Adv Energy", "advanced.energy", false),
        GAS("Gas", "mekanism.gas", true),
        MANA("Mana", "botania.mana", false),
        ESSENTIA("Essentia", "tc.essentia", false),
        EU("EU", "ic2.eu", false);

        private final String name;
        private final String channelId;
        private final boolean sided;

        Type(String name, String channelId, boolean sided) {
            this.name = name;
            this.channelId = channelId;
            this.sided = sided;
        }

        public String getName() {return name;}
        public boolean isSided() {return sided;}

        @Nullable
        public static Type fromChannelId(String channelId) {
            for (Type type : values()) {
                if (type.channelId.equals(channelId)) {return type;}
            }
            return null;
        }
    }

    public static final class Fact {
        private final int flags;
        private final int count;

        private Fact(int flags, int count) {
            this.flags = flags;
            this.count = count;
        }

        public boolean hasAccess() {return (flags & FLAG_ACCESS) != 0;}
        public boolean canInput() {return (flags & FLAG_INPUT) != 0;}
        public boolean canOutput() {return (flags & FLAG_OUTPUT) != 0;}
        public boolean failed() {return (flags & FLAG_FAILED) != 0;}
        public int getCount() {return count;}

        private void toBytes(ByteBuf buf) {
            buf.writeByte(flags);
            buf.writeInt(count);
        }

        private static Fact fromBytes(ByteBuf buf) {
            int flags = buf.readUnsignedByte();
            int count = buf.readInt();
            if ((flags & ~VALID_FLAGS) != 0 || count < -1) {throw new IllegalArgumentException("Invalid Side Probe fact");}
            return new Fact(flags, count);
        }
    }

    public static final class Snapshot {
        private final EnumMap<Type, Fact[]> facts;

        private Snapshot(EnumMap<Type, Fact[]> facts) {
            this.facts = facts;
        }

        public List<Type> getTypes() {return new ArrayList<>(facts.keySet());}
        public boolean has(Type type) {return facts.containsKey(type);}

        @Nonnull
        public Fact get(Type type, @Nonnull EnumFacing side) {
            Fact[] values = facts.get(type);
            if (values == null || !type.isSided()) {throw new IllegalArgumentException("No sided facts for " + type);}
            return values[side.ordinal()];
        }

        @Nonnull
        public Fact getAllSides(Type type) {
            Fact[] values = facts.get(type);
            if (values == null || type.isSided()) {throw new IllegalArgumentException("No all-sides fact for " + type);}
            return values[0];
        }

        public void toBytes(ByteBuf buf) {
            buf.writeByte(facts.size());
            for (Type type : facts.keySet()) {
                Fact[] values = facts.get(type);
                buf.writeByte(type.ordinal());
                for (Fact fact : values) {fact.toBytes(buf);}
            }
        }

        public static Snapshot fromBytes(ByteBuf buf) {
            int count = buf.readUnsignedByte();
            if (count > Type.values().length) {throw new IllegalArgumentException("Invalid Side Probe type count: " + count);}
            EnumMap<Type, Fact[]> facts = new EnumMap<>(Type.class);
            for (int i = 0; i < count; i++) {
                int ordinal = buf.readUnsignedByte();
                if (ordinal >= Type.values().length) {throw new IllegalArgumentException("Invalid Side Probe type: " + ordinal);}
                Type type = Type.values()[ordinal];
                if (facts.containsKey(type)) {throw new IllegalArgumentException("Duplicate Side Probe type: " + type);}
                Fact[] values = new Fact[type.isSided() ? EnumFacing.VALUES.length : 1];
                for (int j = 0; j < values.length; j++) {values[j] = Fact.fromBytes(buf);}
                facts.put(type, values);
            }
            return new Snapshot(facts);
        }
    }

    private SideProbe() {}

    @Nonnull
    public static Snapshot scan(@Nonnull TileEntity target, @Nonnull EnumFacing connectedSide) {
        EnumMap<Type, Fact[]> facts = new EnumMap<>(Type.class);
        for (Type type : Type.values()) {
            if (XNet.xNetApi.findType(type.channelId) == null) {continue;}
            if (type == Type.ADVANCED_ENERGY && !AdvancedEnergyChannelSettings.hasSpecialTargetAccess(target)) {continue;}
            Fact[] values = new Fact[type.isSided() ? EnumFacing.VALUES.length : 1];
            if (type.isSided()) {
                for (EnumFacing side : EnumFacing.VALUES) {values[side.ordinal()] = safeProbe(target, type, side);}
            } else {
                values[0] = safeProbe(target, type, connectedSide);
            }
            facts.put(type, values);
        }
        return new Snapshot(facts);
    }

    @Nonnull
    public static Fact probe(@Nullable TileEntity target, @Nonnull Type type, @Nonnull EnumFacing side) {
        switch (type) {
            case ITEM: {
                if (ModSetup.rftools && RFToolsSupport.isStorageScanner(target)) {return access(-1, false, false);}
                IItemHandler handler = ItemChannelSettings.getItemHandlerAt(target, side);
                return handler == null ? noAccess() : access(Math.max(0, handler.getSlots()), false, false);
            }
            case FLUID: {
                IFluidHandler handler = FluidChannelSettings.getFluidHandlerAt(target, side);
                if (handler == null) {return noAccess();}
                IFluidTankProperties[] properties = handler.getTankProperties();
                if (properties == null) {throw new IllegalStateException("Fluid handler returned null tank properties");}
                boolean fill = false;
                boolean drain = false;
                for (IFluidTankProperties property : properties) {
                    if (property == null) {continue;}
                    fill |= property.canFill();
                    drain |= property.canDrain();
                }
                return access(properties.length, fill, drain);
            }
            case ENERGY: {
                IEnergyStorage handler = EnergyChannelSettings.getEnergyHandlerAt(target, side);
                return handler == null ? noAccess() : access(-1, handler.canReceive(), handler.canExtract());
            }
            case ADVANCED_ENERGY: {
                if (target == null) {return noAccess();}
                boolean input = AdvancedEnergyChannelSettings.canUseTarget(target, side, AdvancedEnergyConnectorSettings.EnergyMode.INS, false);
                boolean output = AdvancedEnergyChannelSettings.canUseTarget(target, side, AdvancedEnergyConnectorSettings.EnergyMode.EXT, false);
                return input || output ? access(-1, input, output) : noAccess();
            }
            case GAS: {
                IGasHandler handler = GasChannelSettings.getGasHandlerAt(target, side);
                if (handler == null) {return noAccess();}
                GasTankInfo[] tanks = handler.getTankInfo();
                int count = tanks == null ? 0 : tanks.length;
                return access(count, handler.canReceiveGas(side, null), handler.canDrawGas(side, null));
            }
            case MANA:
                return ManaChannelSettings.getManaNode(target, side) == null ? noAccess() : access(-1, false, false);
            case ESSENTIA:
                return EssentiaChannelSettings.getEssentiaNode(target) == null ? noAccess() : access(-1, false, false);
            case EU:
                if (target == null || target.getWorld() == null) {return noAccess();}
                boolean sink = EUChannelSettings.getEnergySinkAt(target.getWorld(), target.getPos()) != null;
                boolean source = EUChannelSettings.getEnergySourceAt(target.getWorld(), target.getPos()) != null;
                return sink || source ? access(-1, sink, source) : noAccess();
            default:
                throw new IllegalArgumentException("Unsupported Side Probe type: " + type);
        }
    }

    private static Fact safeProbe(TileEntity target, Type type, EnumFacing side) {
        try {
            return probe(target, type, side);
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            return new Fact(FLAG_FAILED, -1);
        }
    }

    private static Fact noAccess() {return new Fact(0, -1);}

    private static Fact access(int count, boolean input, boolean output) {
        return new Fact(FLAG_ACCESS | (input ? FLAG_INPUT : 0) | (output ? FLAG_OUTPUT : 0), count);
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof ThreadDeath) {throw (ThreadDeath) throwable;}
        if (throwable instanceof VirtualMachineError) {throw (VirtualMachineError) throwable;}
    }
}
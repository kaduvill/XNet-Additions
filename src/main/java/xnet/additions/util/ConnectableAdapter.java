package xnet.additions.util;

import mcjty.xnet.api.channels.IConnectable;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.capabilities.Capability;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ConnectableAdapter implements IConnectable {

    private final List<Class<?>> connectableTypes = new ArrayList<>();
    private final List<Capability<?>> connectableCapabilities = new ArrayList<>();

    public void addType(Class<?> type) {
        connectableTypes.add(Objects.requireNonNull(type));
    }

    public void addCapability(Capability<?> capability) {
        connectableCapabilities.add(Objects.requireNonNull(capability));
    }

    public boolean isEmpty() {
        return connectableTypes.isEmpty() && connectableCapabilities.isEmpty();
    }

    @Override
    public ConnectResult canConnect(@Nonnull IBlockAccess access,
                                    @Nonnull BlockPos connectorPos,
                                    @Nonnull BlockPos blockPos,
                                    @Nullable TileEntity tile,
                                    @Nonnull EnumFacing facing) {
        if (tile == null) {
            return ConnectResult.DEFAULT;
        }

        Class<?> tileClass = tile.getClass();

        for (Class<?> type : connectableTypes) {
            if (type.isAssignableFrom(tileClass)) {
                return ConnectResult.YES;
            }
        }

        for (Capability<?> capability : connectableCapabilities) {
            if (tile.hasCapability(capability, facing)) {
                return ConnectResult.YES;
            }
        }

        return ConnectResult.DEFAULT;
    }
}
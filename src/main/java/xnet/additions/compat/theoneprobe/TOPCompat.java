package xnet.additions.compat.theoneprobe;

import mcjty.theoneprobe.api.ElementAlignment;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.IProgressStyle;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import mcjty.theoneprobe.api.TextStyleClass;
import mcjty.xnet.api.channels.Color;
import mcjty.xnet.blocks.controller.TileEntityController;
import mcjty.xnet.init.ModBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.event.FMLInterModComms;
import xnet.additions.powertools.logicstatus.LogicSignalStatus;

import java.util.function.Function;

public final class TOPCompat implements Function<ITheOneProbe, Void>, IProbeInfoProvider {

    public static void register() {
        FMLInterModComms.sendFunctionMessage("theoneprobe", "getTheOneProbe", "xnet.additions.compat.theoneprobe.TOPCompat");
    }

    @Override
    public Void apply(ITheOneProbe probe) {
        probe.registerProvider(this);
        return null;
    }

    @Override
    public String getID() {
        return "xnetadditions:logic_status";
    }

    @Override
    public void addProbeInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world, IBlockState blockState, IProbeHitData data) {
        if (blockState.getBlock() != ModBlocks.controllerBlock) {return;}
        TileEntity tile = world.getTileEntity(data.getPos());
        if (!(tile instanceof TileEntityController)) {return;}
        int activeMask = LogicSignalStatus.getActiveMask((TileEntityController) tile);
        if (activeMask == LogicSignalStatus.NO_LOGIC_CHANNEL) {return;}

        IProbeInfo row = probeInfo.horizontal(probeInfo.defaultLayoutStyle().spacing(2).alignment(ElementAlignment.ALIGN_CENTER));
        row.text(TextStyleClass.LABEL + "Logic:");
        if (activeMask == 0) {
            row.text(TextStyleClass.INFO + "None");
            return;
        }

        for (Color color : Color.values()) {
            if (color == Color.OFF || (activeMask & (1 << color.ordinal())) == 0) {continue;}
            int argb = color == Color.WHITE ? 0xfffffffe : (0xff000000 | color.getColor());
            IProgressStyle style = probeInfo.defaultProgressStyle().width(7).height(7).showText(false).borderColor(0xff202020).backgroundColor(0xff000000).filledColor(argb).alternateFilledColor(argb);
            row.progress(1, 1, style);
        }
    }
}
package xnet.additions.thaumcraft;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import thaumcraft.api.aspects.Aspect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class EssentiaTilePolicy {

    enum SinkPolicy {
        GENERIC_OK,
        MUST_ASK,
        NEVER_GENERIC
    }

    static final class TilePolicy {
        private final SinkPolicy sinkPolicy;
        @Nullable
        private final EnumFacing forcedInputSide;
        @Nullable
        private final EnumFacing forcedOutputSide;

        private TilePolicy(@Nonnull SinkPolicy sinkPolicy,
                           @Nullable EnumFacing forcedInputSide,
                           @Nullable EnumFacing forcedOutputSide) {
            this.sinkPolicy = sinkPolicy;
            this.forcedInputSide = forcedInputSide;
            this.forcedOutputSide = forcedOutputSide;
        }

        @Nonnull
        static TilePolicy generic() {
            return new TilePolicy(SinkPolicy.GENERIC_OK, null, null);
        }

        @Nonnull
        static TilePolicy mustAsk() {
            return new TilePolicy(SinkPolicy.MUST_ASK, null, null);
        }

        @Nonnull
        static TilePolicy neverGeneric() {
            return new TilePolicy(SinkPolicy.NEVER_GENERIC, null, null);
        }

        @Nonnull
        TilePolicy withForcedInputSide(@Nullable EnumFacing side) {
            return new TilePolicy(sinkPolicy, side, forcedOutputSide);
        }

        @Nonnull
        TilePolicy withForcedOutputSide(@Nullable EnumFacing side) {
            return new TilePolicy(sinkPolicy, forcedInputSide, side);
        }

        @Nonnull
        SinkPolicy getSinkPolicy() {
            return sinkPolicy;
        }

        @Nullable
        EnumFacing getForcedInputSide() {
            return forcedInputSide;
        }

        @Nullable
        EnumFacing getForcedOutputSide() {
            return forcedOutputSide;
        }
    }

    // Tiles that should only receive essentia when they are actively requesting it.
    private static final Set<String> MUST_ASK_SINKS = new HashSet<>(Arrays.asList(
            "thaumcraft.common.tiles.crafting.TileThaumatorium",
            "thaumcraft.common.tiles.crafting.TileThaumatoriumTop"
    ));

    // Tiles that should never be treated as generic sinks through addEssentia().
    private static final Set<String> UNSAFE_GENERIC_SINKS = new HashSet<>(Arrays.asList(
            // Add confirmed bad targets here.
    ));

    // Add exact jar class names here once you confirm them in your environment.
    // Example idea later:
    // "thaumcraft.common.tiles.essentia.TileJarFillable"
    private static final Set<String> FORCED_UP_INPUT_TILES = new HashSet<>(Arrays.asList(
            "thaumcraft.common.tiles.essentia.TileJarFillable",
            "thaumcraft.common.tiles.essentia.TileJarFillableVoid",
            "com.verdantartifice.thaumicwonders.common.tiles.essentia.TileOblivionEssentiaJar",
            "com.verdantartifice.thaumicwonders.common.tiles.essentia.TileCreativeEssentiaJar"
    ));

    private static final Set<String> FORCED_UP_OUTPUT_TILES = new HashSet<>(Arrays.asList(
            "thaumcraft.common.tiles.essentia.TileJarFillable",
            "com.verdantartifice.thaumicwonders.common.tiles.essentia.TileCreativeEssentiaJar"
    ));

    private EssentiaTilePolicy() {
    }

    @Nonnull
    static TilePolicy classify(@Nullable TileEntity te) {
        if (te == null) {
            return TilePolicy.generic();
        }

        String cn = te.getClass().getName();

        TilePolicy policy;
        if (UNSAFE_GENERIC_SINKS.contains(cn)) {
            policy = TilePolicy.neverGeneric();
        } else if (MUST_ASK_SINKS.contains(cn)) {
            policy = TilePolicy.mustAsk();
        } else {
            policy = TilePolicy.generic();
        }

        if (FORCED_UP_INPUT_TILES.contains(cn)) {
            policy = policy.withForcedInputSide(EnumFacing.UP);
        }
        if (FORCED_UP_OUTPUT_TILES.contains(cn)) {
            policy = policy.withForcedOutputSide(EnumFacing.UP);
        }

        return policy;
    }

    @Nullable
    static EnumFacing getEffectiveInputSide(@Nullable EnumFacing configuredSide,
                                            @Nonnull TilePolicy policy) {
        return policy.getForcedInputSide() != null ? policy.getForcedInputSide() : configuredSide;
    }

    @Nullable
    static EnumFacing getEffectiveOutputSide(@Nullable EnumFacing configuredSide,
                                             @Nonnull TilePolicy policy) {
        return policy.getForcedOutputSide() != null ? policy.getForcedOutputSide() : configuredSide;
    }

    static boolean canInsertInto(@Nonnull EssentiaChannelSettings.EssentiaNode node,
                                 @Nonnull Aspect aspect,
                                 @Nonnull TilePolicy policy) {
        switch (policy.getSinkPolicy()) {
            case NEVER_GENERIC:
                return false;
            case MUST_ASK:
                return isAskingForAspect(node, aspect);
            case GENERIC_OK:
            default:
                // Generic sinks are allowed unless they are explicitly pulling a
                // different typed aspect right now.
                Aspect suctionAspect = node.getSuctionAspect();
                int suction = node.getSuctionAmount();
                return suction <= 0 || suctionAspect == null || sameAspect(suctionAspect, aspect);
        }
    }

    static boolean sameAspect(@Nullable Aspect a, @Nullable Aspect b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.getTag(), b.getTag());
    }

    private static boolean isAskingForAspect(@Nonnull EssentiaChannelSettings.EssentiaNode node,
                                             @Nonnull Aspect aspect) {
        int suction = node.getSuctionAmount();
        if (suction <= 0) {
            return false;
        }

        Aspect suctionAspect = node.getSuctionAspect();
        return suctionAspect != null && sameAspect(suctionAspect, aspect);
    }
}
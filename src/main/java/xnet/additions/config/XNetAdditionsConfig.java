package xnet.additions.config;

import net.minecraftforge.common.config.Configuration;

public final class XNetAdditionsConfig {

    public static final String CATEGORY_COMPAT = "compat";
    public static final String CATEGORY_MEKANISM_GAS = "mekanism_gas";
    public static final String CATEGORY_BOTANIA_MANA = "botania_mana";
    public static final String CATEGORY_THAUMCRAFT_ESSENTIA = "thaumcraft_essentia";
    public static final String CATEGORY_IC2_EU = "ic2_eu";

    // Compat toggles
    public static boolean enableMekanismGas = true;
    public static boolean enableBotaniaMana = true;
    public static boolean enableThaumcraftEssentia = true;
    public static boolean enableIC2EU = true;

    // Mekanism gas rates
    public static int maxGasRateNormal = 256;
    public static int maxGasRateAdvanced = 1280;

    // Botania mana rates
    public static int maxManaRateNormal = 2000;
    public static int maxManaRateAdvanced = 10000;

    // Thaumcraft essentia rates
    public static int maxEssentiaRateNormal = 50;
    public static int maxEssentiaRateAdvanced = 250;

    // IndustrialCraft 2 EU rates
    public static int maxEuRateNormal = 2048;
    public static int maxEuRateAdvanced = 1048576; // 1024 * 1024

    private XNetAdditionsConfig() {
    }

    public static void load(Configuration config) {
        enableMekanismGas = config.getBoolean(
                "enableMekanismGas",
                CATEGORY_COMPAT,
                true,
                "Enable XNet support for Mekanism gas."
        );

        enableBotaniaMana = config.getBoolean(
                "enableBotaniaMana",
                CATEGORY_COMPAT,
                true,
                "Enable XNet support for Botania mana."
        );

        enableThaumcraftEssentia = config.getBoolean(
                "enableThaumcraftEssentia",
                CATEGORY_COMPAT,
                true,
                "Enable XNet support for Thaumcraft essentia."
        );

        enableIC2EU = config.getBoolean(
                "enableIC2EU",
                CATEGORY_COMPAT,
                true,
                "Enable XNet support for IndustrialCraft 2 EU."
        );

        maxGasRateNormal = config.getInt(
                "maxRateNormal",
                CATEGORY_MEKANISM_GAS,
                256,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Mekanism gas on normal connectors."
        );

        maxGasRateAdvanced = config.getInt(
                "maxRateAdvanced",
                CATEGORY_MEKANISM_GAS,
                1280,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Mekanism gas on advanced connectors."
        );

        maxManaRateNormal = config.getInt(
                "maxRateNormal",
                CATEGORY_BOTANIA_MANA,
                2000,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Botania mana on normal connectors."
        );

        maxManaRateAdvanced = config.getInt(
                "maxRateAdvanced",
                CATEGORY_BOTANIA_MANA,
                10000,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Botania mana on advanced connectors."
        );

        maxEssentiaRateNormal = config.getInt(
                "maxRateNormal",
                CATEGORY_THAUMCRAFT_ESSENTIA,
                50,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Thaumcraft essentia on normal connectors."
        );

        maxEssentiaRateAdvanced = config.getInt(
                "maxRateAdvanced",
                CATEGORY_THAUMCRAFT_ESSENTIA,
                250,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Thaumcraft essentia on advanced connectors."
        );

        maxEuRateNormal = config.getInt(
                "maxRateNormal",
                CATEGORY_IC2_EU,
                2048,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for IndustrialCraft 2 EU on normal connectors, in EU/t."
        );

        maxEuRateAdvanced = config.getInt(
                "maxRateAdvanced",
                CATEGORY_IC2_EU,
                1048576,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for IndustrialCraft 2 EU on advanced connectors, in EU/t. "
        );

        config.setCategoryComment(
                CATEGORY_COMPAT,
                "Top-level compatibility toggles for optional mod integrations."
        );

        config.setCategoryComment(
                CATEGORY_MEKANISM_GAS,
                "Settings for the Mekanism gas channel."
        );

        config.setCategoryComment(
                CATEGORY_BOTANIA_MANA,
                "Settings for the Botania mana channel."
        );

        config.setCategoryComment(
                CATEGORY_THAUMCRAFT_ESSENTIA,
                "Settings for the Thaumcraft essentia channel."
        );

        config.setCategoryComment(
                CATEGORY_IC2_EU,
                "Settings for the IndustrialCraft 2 EU channel."
        );
    }
}
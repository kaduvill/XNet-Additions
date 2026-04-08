package xnet.additions.config;

import net.minecraftforge.common.config.Configuration;

public final class XNetAdditionsConfig {

    public static final String CATEGORY_COMPAT = "compat";
    public static final String CATEGORY_MEKANISM_GAS = "mekanism_gas";
    public static final String CATEGORY_BOTANIA_MANA = "botania_mana";
    public static final String CATEGORY_THAUMCRAFT_ESSENTIA = "thaumcraft_essentia";

    // Compat toggles
    public static boolean enableMekanismGas = true;
    public static boolean enableBotaniaMana = true;
    public static boolean enableThaumcraftEssentia = true;

    // Mekanism gas rates
    public static int maxGasRateNormal = 256;
    public static int maxGasRateAdvanced = 256 * 5;

    // Botania mana rates
    public static int maxManaRateNormal = 2000;
    public static int maxManaRateAdvanced = 10000;

    // Thaumcraft essentia rates
    public static int maxEssentiaRateNormal = 25;
    public static int maxEssentiaRateAdvanced = 100;

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
                256 * 5,
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
                25,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Thaumcraft essentia on normal connectors."
        );

        maxEssentiaRateAdvanced = config.getInt(
                "maxRateAdvanced",
                CATEGORY_THAUMCRAFT_ESSENTIA,
                100,
                1,
                Integer.MAX_VALUE,
                "Maximum transfer rate for Thaumcraft essentia on advanced connectors."
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
    }
}
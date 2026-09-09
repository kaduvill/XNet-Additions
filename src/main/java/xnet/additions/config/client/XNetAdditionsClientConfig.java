package xnet.additions.config.client;

import mcjty.xnet.XNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.DummyConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import xnet.additions.XNetAdditions;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@SideOnly(Side.CLIENT)
public final class XNetAdditionsClientConfig implements IModGuiFactory {

    public static final String LEFT_CLOSED = "closed";
    public static final String LEFT_LAST_USED = "last_used";
    public static final String LEFT_DIAGNOSTICS = "diagnostics";
    public static final String LEFT_HEALTH = "health";
    public static final String LEFT_LOGIC = "logic";
    public static final String LEFT_RECENT = "recent";
    public static final String LEFT_SIDE_PROBE = "side_probe";
    public static final String TOP_CLOSED = "closed";
    public static final String TOP_BATCH_EDIT = "batch_edit";
    public static final String TOP_PRESETS = "presets";

    private static final String CATEGORY_POWER_TOOLS = "power_tools";
    private static final String KEY_LEFT_PANEL = "leftPanelOnOpen";
    private static final String KEY_TOP_PANEL = "topPanelOnOpen";
    private static final String KEY_INITIAL_ITEM = "initialArmedPresetItem";
    private static final String KEY_INITIAL_ENERGY = "initialArmedPresetEnergy";
    private static final String KEY_INITIAL_FLUID = "initialArmedPresetFluid";
    private static final String KEY_INITIAL_LOGIC = "initialArmedPresetLogic";
    private static final String KEY_INITIAL_ADVANCED_ENERGY = "initialArmedPresetAdvancedEnergy";
    private static final String KEY_INITIAL_GAS = "initialArmedPresetMekanismGas";
    private static final String KEY_INITIAL_MANA = "initialArmedPresetBotaniaMana";
    private static final String KEY_INITIAL_ESSENTIA = "initialArmedPresetThaumcraftEssentia";
    private static final String KEY_INITIAL_EU = "initialArmedPresetIC2EU";
    private static final String[] LEFT_VALUES = {LEFT_CLOSED, LEFT_LAST_USED, LEFT_DIAGNOSTICS, LEFT_HEALTH, LEFT_LOGIC, LEFT_RECENT, LEFT_SIDE_PROBE};
    private static final String[] LEFT_DISPLAY = {"Closed", "Last used", "Diagnostics", "Health", "Logic", "Recent", "Side Prober"};
    private static final String[] TOP_VALUES = {TOP_CLOSED, TOP_BATCH_EDIT, TOP_PRESETS};
    private static final String[] TOP_DISPLAY = {"Closed", "Batch Edit", "Presets"};
    private static final String[] INITIAL_PRESET_VALUES = {"none", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9"};
    private static final String[] INITIAL_PRESET_DISPLAY = {"None", "P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9"};

    private static Configuration config;
    private static String leftPanelOnOpen = LEFT_RECENT;
    private static String lastUsedLeftPanel = LEFT_RECENT;
    private static String topPanelOnOpen = TOP_CLOSED;
    private static int initialItemPreset = -1;
    private static int initialEnergyPreset = -1;
    private static int initialFluidPreset = -1;
    private static int initialLogicPreset = -1;
    private static int initialAdvancedEnergyPreset = -1;
    private static int initialGasPreset = -1;
    private static int initialManaPreset = -1;
    private static int initialEssentiaPreset = -1;
    private static int initialEuPreset = -1;

    public static String getLeftPanelOnOpen() {return leftPanelOnOpen;}
    public static String getLastUsedLeftPanel() {return lastUsedLeftPanel;}
    public static String getTopPanelOnOpen() {return topPanelOnOpen;}
    public static int getInitialArmedPreset(String typeId) {
        if ("xnet.item".equals(typeId)) return initialItemPreset;
        if ("xnet.energy".equals(typeId)) return initialEnergyPreset;
        if ("xnet.fluid".equals(typeId)) return initialFluidPreset;
        if ("xnet.logic".equals(typeId)) return initialLogicPreset;
        if ("advanced.energy".equals(typeId)) return initialAdvancedEnergyPreset;
        if ("mekanism.gas".equals(typeId)) return initialGasPreset;
        if ("botania.mana".equals(typeId)) return initialManaPreset;
        if ("tc.essentia".equals(typeId)) return initialEssentiaPreset;
        if ("ic2.eu".equals(typeId)) return initialEuPreset;
        return -1;
    }
    public static void rememberLastLeftPanel(String panel) {
        if (!LEFT_LAST_USED.equals(leftPanelOnOpen) || panel == null || panel.equals(lastUsedLeftPanel)) {return;}
        boolean valid = LEFT_DIAGNOSTICS.equals(panel) || LEFT_HEALTH.equals(panel) || LEFT_LOGIC.equals(panel)
                || LEFT_RECENT.equals(panel) || LEFT_SIDE_PROBE.equals(panel);
        if (valid) {lastUsedLeftPanel = panel;}
    }

    private static void sync() {
        leftPanelOnOpen = config.getString(KEY_LEFT_PANEL, CATEGORY_POWER_TOOLS, LEFT_RECENT,
                "Power Tools panel to open when a Controller GUI is opened. Last used is remembered until Minecraft closes.",
                LEFT_VALUES, LEFT_DISPLAY, "config.xnetadditions.leftPanelOnOpen");
        topPanelOnOpen = config.getString(KEY_TOP_PANEL, CATEGORY_POWER_TOOLS, TOP_CLOSED,
                "Top Power Tools toolbar state when a Controller GUI is opened.", TOP_VALUES, TOP_DISPLAY,
                "config.xnetadditions.topPanelOnOpen");

        initialItemPreset = readInitialArmedPreset(KEY_INITIAL_ITEM, "Item");
        initialEnergyPreset = readInitialArmedPreset(KEY_INITIAL_ENERGY, "Energy");
        initialFluidPreset = readInitialArmedPreset(KEY_INITIAL_FLUID, "Fluid");
        initialLogicPreset = readInitialArmedPreset(KEY_INITIAL_LOGIC, "Logic");
        initialAdvancedEnergyPreset = readInitialArmedPreset(KEY_INITIAL_ADVANCED_ENERGY, "Advanced Energy");
        initialGasPreset = readInitialArmedPreset(KEY_INITIAL_GAS, "Mekanism Gas");
        initialManaPreset = readInitialArmedPreset(KEY_INITIAL_MANA, "Botania Mana");
        initialEssentiaPreset = readInitialArmedPreset(KEY_INITIAL_ESSENTIA, "Thaumcraft Essentia");
        initialEuPreset = readInitialArmedPreset(KEY_INITIAL_EU, "IC2 EU");

        config.setCategoryComment(CATEGORY_POWER_TOOLS, "Client-only Controller GUI and preset preferences.");
        config.setCategoryLanguageKey(CATEGORY_POWER_TOOLS, "config.xnetadditions.powerTools");
        config.setCategoryPropertyOrder(CATEGORY_POWER_TOOLS, Arrays.asList(KEY_LEFT_PANEL, KEY_TOP_PANEL,
                KEY_INITIAL_ITEM, KEY_INITIAL_ENERGY, KEY_INITIAL_FLUID, KEY_INITIAL_LOGIC,
                KEY_INITIAL_ADVANCED_ENERGY, KEY_INITIAL_GAS, KEY_INITIAL_MANA, KEY_INITIAL_ESSENTIA, KEY_INITIAL_EU));
        if (config.hasChanged()) config.save();
    }
    private static int readInitialArmedPreset(String key, String channelName) {
        String value = config.getString(key, CATEGORY_POWER_TOOLS, "none",
                "Initial armed preset for " + channelName + " channels. Used once per Minecraft client session if that preset exists; native Create remains blank.\nLeft-click: next; right-click: previous.",
                INITIAL_PRESET_VALUES, INITIAL_PRESET_DISPLAY, "config.xnetadditions." + key);
        config.getCategory(CATEGORY_POWER_TOOLS).get(key).setConfigEntryClass(PresetCycleEntry.class);
        return value != null && value.length() == 2 && value.charAt(0) == 'p'
                && value.charAt(1) >= '1' && value.charAt(1) <= '9' ? value.charAt(1) - '1' : -1;
    }

    private static String getInitialPresetType(String key) {
        if (KEY_INITIAL_ITEM.equals(key)) return "xnet.item";
        if (KEY_INITIAL_ENERGY.equals(key)) return "xnet.energy";
        if (KEY_INITIAL_FLUID.equals(key)) return "xnet.fluid";
        if (KEY_INITIAL_LOGIC.equals(key)) return "xnet.logic";
        if (KEY_INITIAL_ADVANCED_ENERGY.equals(key)) return "advanced.energy";
        if (KEY_INITIAL_GAS.equals(key)) return "mekanism.gas";
        if (KEY_INITIAL_MANA.equals(key)) return "botania.mana";
        if (KEY_INITIAL_ESSENTIA.equals(key)) return "tc.essentia";
        if (KEY_INITIAL_EU.equals(key)) return "ic2.eu";
        return null;
    }
    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (XNetAdditions.MODID.equals(event.getModID())) {sync();}
    }

    @Override
    public void initialize(Minecraft minecraftInstance) {
        config = new Configuration(XNetAdditions.getConfigDirectory().resolve(XNetAdditions.MODID + "-client.cfg").toFile());
        sync();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean hasConfigGui() {return true;}

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        List<IConfigElement> elements = new ArrayList<>();
        List<IConfigElement> presetElements = new ArrayList<>();

        for (IConfigElement element : new ConfigElement(config.getCategory(CATEGORY_POWER_TOOLS)).getChildElements()) {
            String typeId = getInitialPresetType(element.getName());
            if (typeId == null) {
                elements.add(element);
            } else if (XNet.xNetApi.findType(typeId) != null) {
                presetElements.add(element);
            }
        }

        if (!presetElements.isEmpty()) {
            elements.add(0, new DummyConfigElement.DummyCategoryElement(
                    "initialArmedPresets", "config.xnetadditions.initialArmedPresets", presetElements));
        }

        return new GuiConfig(parentScreen, elements, XNetAdditions.MODID,
                false, false, "XNet Additions - Client Preferences");
    }
    public static final class PresetCycleEntry extends GuiConfigEntries.SelectValueEntry {

        public PresetCycleEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement) {
            super(owningScreen, owningEntryList, configElement, Collections.emptyMap());
        }

        private int getIndex() {
            String value = String.valueOf(currentValue);
            for (int i = 0; i < INITIAL_PRESET_VALUES.length; i++) {
                if (INITIAL_PRESET_VALUES[i].equalsIgnoreCase(value)) return i;
            }
            return 0;
        }

        private void cycle(int direction) {
            int index = (getIndex() + direction + INITIAL_PRESET_VALUES.length) % INITIAL_PRESET_VALUES.length;
            currentValue = INITIAL_PRESET_VALUES[index];
        }

        @Override
        public void updateValueButtonText() {
            btnValue.displayString = INITIAL_PRESET_DISPLAY[getIndex()];
        }

        @Override
        public void valueButtonPressed(int slotIndex) {
            if (enabled()) cycle(1);
        }

        @Override
        public void mouseClicked(int x, int y, int mouseEvent) {
            if (mouseEvent == 1 && enabled() && btnValue.mousePressed(mc, x, y)) {
                btnValue.playPressSound(mc.getSoundHandler());
                cycle(-1);
                updateValueButtonText();
            }
        }
    }
    @Nullable
    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {return null;}
}
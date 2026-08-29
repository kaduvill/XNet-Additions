package xnet.additions.config.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import xnet.additions.XNetAdditions;

import javax.annotation.Nullable;
import java.util.Arrays;
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
    private static final String[] LEFT_VALUES = {LEFT_CLOSED, LEFT_LAST_USED, LEFT_DIAGNOSTICS, LEFT_HEALTH, LEFT_LOGIC, LEFT_RECENT, LEFT_SIDE_PROBE};
    private static final String[] LEFT_DISPLAY = {"Closed", "Last used", "Diagnostics", "Health", "Logic", "Recent", "Side Prober"};
    private static final String[] TOP_VALUES = {TOP_CLOSED, TOP_BATCH_EDIT, TOP_PRESETS};
    private static final String[] TOP_DISPLAY = {"Closed", "Batch Edit", "Presets"};

    private static Configuration config;
    private static String leftPanelOnOpen = LEFT_RECENT;
    private static String lastUsedLeftPanel = LEFT_RECENT;
    private static String topPanelOnOpen = TOP_CLOSED;

    public static String getLeftPanelOnOpen() {return leftPanelOnOpen;}
    public static String getLastUsedLeftPanel() {return lastUsedLeftPanel;}
    public static String getTopPanelOnOpen() {return topPanelOnOpen;}

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
        config.setCategoryComment(CATEGORY_POWER_TOOLS, "Client-only Controller GUI preferences.");
        config.setCategoryLanguageKey(CATEGORY_POWER_TOOLS, "config.xnetadditions.powerTools");
        config.setCategoryPropertyOrder(CATEGORY_POWER_TOOLS, Arrays.asList(KEY_LEFT_PANEL, KEY_TOP_PANEL));
        if (config.hasChanged()) {config.save();}
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
        return new GuiConfig(parentScreen, new ConfigElement(config.getCategory(CATEGORY_POWER_TOOLS)).getChildElements(),
                XNetAdditions.MODID, false, false, "XNet Additions - Client Preferences");
    }

    @Nullable
    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {return null;}
}
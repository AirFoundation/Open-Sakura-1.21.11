package dev.sakura.client;

import dev.sakura.client.command.CommandManager;
import dev.sakura.client.config.ConfigManager;
import dev.sakura.client.event.EventBus;
import dev.sakura.client.event.IEventBus;
import dev.sakura.client.huds.clickgui.ClickGuiScreen;
import dev.sakura.client.huds.hudeditor.HudEditorScreen;
import dev.sakura.client.huds.mainmenu.MainMenuScreen;
import dev.sakura.client.manager.Managers;
import dev.sakura.client.module.ModuleManager;
import dev.sakura.client.utils.nanovg.NanoVGRenderer;
import net.minecraft.client.MinecraftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * @author deobf by Naven484 && GPT 5.5 / Claude Opus With Sakura 1.21.4 OpenSrc
 * @version 1.1
 *  Shit Sakura client entry point.
 *  如果你连zkm21都混不明白，你可以去找欣欣哥免费混淆
 *  此端作者为秋奈（王怡博） + Naven Paste
 *  首轮deobf mapping ： mappings/deobf_clean_final.tiny
 *  如果你欣欣哥都不会找，你可以找个楼跳了
 */
public class SakuraClient {
    public static final String CLIENT_NAME = "Sakura";
    public static final String CLIENT_VERSION = "1.1";

    public static final Logger LOGGER = LogManager.getLogger(CLIENT_NAME);
    public static final IEventBus EVENT_BUS = new EventBus();

    public static Executor EXECUTOR;

    public static MinecraftClient minecraftInstance;

    public static ModuleManager MODULES;
    public static ConfigManager CONFIG;
    public static CommandManager COMMAND;
    public static ClickGuiScreen CLICKGUI;
    public static HudEditorScreen HUDEDITOR;

    public static int skipTicks;

    public static void init(MinecraftClient mc) {
        LOGGER.info("Initializing " + CLIENT_NAME);
        NanoVGRenderer.INSTANCE.initNanoVG();
        minecraftInstance = mc;
        EVENT_BUS.registerLambdaFactory(SakuraClient.class.getPackageName(),(lookupInMethod, klass)-> (MethodHandles.Lookup)lookupInMethod.invoke(null, klass, MethodHandles.lookup()));
        EXECUTOR = Executors.newFixedThreadPool(1);
        Managers.init();
        MODULES = new ModuleManager();
        CLICKGUI = new ClickGuiScreen();
        HUDEDITOR = new HudEditorScreen();
        CONFIG = new ConfigManager();
        COMMAND = new CommandManager();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("正在保存配置并且关闭游戏!");
            CONFIG.saveDefaultConfig();
        }));
        LOGGER.info("初始化完成!");
    }

    public static void redirectToMainMenu() {
        minecraftInstance.setScreen(new MainMenuScreen());
    }

    public static boolean startIntro() {
        if (minecraftInstance.currentScreen instanceof MainMenuScreen menu) {
            menu.startIntro();
            return true;
        }
        return false;
    }
}

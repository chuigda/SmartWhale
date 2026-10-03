package dev.smartwhale.bridge;

import com.mojang.logging.LogUtils;
import dev.smartwhale.bridge.action.InputControl;
import dev.smartwhale.bridge.event.AutoConnect;
import dev.smartwhale.bridge.task.TaskManager;
import dev.smartwhale.bridge.util.Ticker;
import dev.smartwhale.bridge.event.GameEvents;
import dev.smartwhale.bridge.rpc.BridgeMethods;
import dev.smartwhale.bridge.rpc.RpcDispatcher;
import dev.smartwhale.bridge.server.BridgeServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = SmartWhaleBridge.MOD_ID, dist = Dist.CLIENT)
public final class SmartWhaleBridge {
    public static final String MOD_ID = "smartwhale_bridge";
    public static final String VERSION = "0.1.0";
    public static final int PROTOCOL = 1;
    public static final Logger LOGGER = LogUtils.getLogger();

    private static BridgeServer server;

    public SmartWhaleBridge(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        BridgeConfig config = BridgeConfig.load();
        if (config == null) {
            LOGGER.warn("SmartWhale bridge disabled: no port/token configured "
                    + "(set -Dsmartwhale.bridge.port/-Dsmartwhale.bridge.token or config/smartwhale-bridge.toml)");
            return;
        }
        RpcDispatcher dispatcher = new RpcDispatcher(config.token());
        server = new BridgeServer(config.port(), dispatcher);
        TaskManager tasks = new TaskManager(server);
        BridgeMethods.registerAll(dispatcher, tasks);
        server.start();
        NeoForge.EVENT_BUS.register(new GameEvents(server));
        NeoForge.EVENT_BUS.register(new Ticker());
        NeoForge.EVENT_BUS.register(new InputControl());
        NeoForge.EVENT_BUS.register(tasks);
        String autoconnect = System.getProperty("smartwhale.bridge.autoconnect");
        if (autoconnect != null && !autoconnect.isBlank()) {
            NeoForge.EVENT_BUS.register(new AutoConnect(autoconnect.strip(), server::notify));
        }
    }

    public static BridgeServer server() {
        return server;
    }
}

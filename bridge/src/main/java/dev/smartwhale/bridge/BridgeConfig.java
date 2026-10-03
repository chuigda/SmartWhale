package dev.smartwhale.bridge;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * System properties take precedence (launch mode); otherwise a minimal
 * {@code config/smartwhale-bridge.toml} with {@code port} and {@code token} keys (attach mode).
 */
public record BridgeConfig(int port, String token) {
    public static BridgeConfig load() {
        String port = System.getProperty("smartwhale.bridge.port");
        String token = System.getProperty("smartwhale.bridge.token");
        if (port == null || token == null) {
            Map<String, String> file = readToml(FMLPaths.CONFIGDIR.get().resolve("smartwhale-bridge.toml"));
            if (port == null) port = file.get("port");
            if (token == null) token = file.get("token");
        }
        if (port == null || token == null || token.isEmpty()) {
            return null;
        }
        return new BridgeConfig(Integer.parseInt(port.trim()), token);
    }

    private static Map<String, String> readToml(Path path) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(path)) return out;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) continue;
                int eq = s.indexOf('=');
                if (eq < 0) continue;
                String key = s.substring(0, eq).strip();
                String value = s.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException e) {
            SmartWhaleBridge.LOGGER.warn("Failed to read {}", path, e);
        }
        return out;
    }
}

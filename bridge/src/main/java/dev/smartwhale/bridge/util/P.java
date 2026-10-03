package dev.smartwhale.bridge.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.rpc.RpcException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** JSON-RPC parameter accessors that fail with INVALID_PARAMS. */
public final class P {
    private P() {
    }

    public static RpcException invalid(String message) {
        return new RpcException(RpcException.INVALID_PARAMS, message);
    }

    public static boolean has(JsonObject p, String key) {
        JsonElement e = p.get(key);
        return e != null && !e.isJsonNull();
    }

    public static String str(JsonObject p, String key) {
        if (!has(p, key)) throw invalid("Missing parameter: " + key);
        try {
            return p.get(key).getAsString();
        } catch (RuntimeException e) {
            throw invalid("Parameter " + key + " must be a string");
        }
    }

    public static String optStr(JsonObject p, String key, String def) {
        return has(p, key) ? str(p, key) : def;
    }

    public static int integer(JsonObject p, String key) {
        if (!has(p, key)) throw invalid("Missing parameter: " + key);
        try {
            return p.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw invalid("Parameter " + key + " must be an integer");
        }
    }

    public static int optInt(JsonObject p, String key, int def) {
        return has(p, key) ? integer(p, key) : def;
    }

    /** Optional integer clamped into [min, max]. */
    public static int clampInt(JsonObject p, String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, optInt(p, key, def)));
    }

    public static double num(JsonObject o, String key) {
        if (!has(o, key)) throw invalid("Missing parameter: " + key);
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw invalid("Parameter " + key + " must be a number");
        }
    }

    public static boolean optBool(JsonObject p, String key, boolean def) {
        if (!has(p, key)) return def;
        try {
            return p.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            throw invalid("Parameter " + key + " must be a boolean");
        }
    }

    public static JsonObject obj(JsonObject p, String key) {
        if (!has(p, key) || !p.get(key).isJsonObject()) throw invalid("Parameter " + key + " must be an object");
        return p.getAsJsonObject(key);
    }

    /** {@code {x,y,z}}; fractional values are floored. */
    public static BlockPos blockPos(JsonObject p, String key) {
        JsonObject o = obj(p, key);
        return BlockPos.containing(num(o, "x"), num(o, "y"), num(o, "z"));
    }

    public static Vec3 vec(JsonObject p, String key) {
        JsonObject o = obj(p, key);
        return new Vec3(num(o, "x"), num(o, "y"), num(o, "z"));
    }

    /** Accepts a single string or an array of strings. */
    public static List<String> strList(JsonObject p, String key) {
        if (!has(p, key)) throw invalid("Missing parameter: " + key);
        JsonElement e = p.get(key);
        List<String> out = new ArrayList<>();
        try {
            if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
            } else {
                out.add(e.getAsString());
            }
        } catch (RuntimeException ex) {
            throw invalid("Parameter " + key + " must be a string or an array of strings");
        }
        if (out.isEmpty()) throw invalid("Parameter " + key + " must not be empty");
        return out;
    }

    public static Direction optDirection(JsonObject p, String key) {
        if (!has(p, key)) return null;
        Direction d = Direction.byName(str(p, key).toLowerCase(Locale.ROOT));
        if (d == null) throw invalid("Parameter " + key + " must be one of down, up, north, south, west, east");
        return d;
    }
}

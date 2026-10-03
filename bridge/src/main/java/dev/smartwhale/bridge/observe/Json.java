package dev.smartwhale.bridge.observe;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

public final class Json {
    private Json() {
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static JsonObject vec(double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", round(x));
        o.addProperty("y", round(y));
        o.addProperty("z", round(z));
        return o;
    }

    public static JsonObject blockPos(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }
}

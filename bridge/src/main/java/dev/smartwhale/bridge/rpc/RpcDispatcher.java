package dev.smartwhale.bridge.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RpcDispatcher {
    @FunctionalInterface
    public interface Handler {
        JsonElement handle(JsonObject params);
    }

    /** Runs on the client thread and completes later, typically from a tick handler. */
    @FunctionalInterface
    public interface AsyncHandler {
        CompletableFuture<JsonElement> handle(JsonObject params);
    }

    private record Entry(Handler handler, AsyncHandler async, boolean mainThread) {
    }

    private static final long MAIN_THREAD_TIMEOUT_MS = 10_000;
    private static final long ASYNC_TIMEOUT_MS = 120_000;

    private final byte[] token;
    private final Map<String, Entry> methods = new ConcurrentHashMap<>();

    public RpcDispatcher(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Registers a handler that runs on the client main thread (anything touching game state). */
    public void onMainThread(String method, Handler handler) {
        methods.put(method, new Entry(handler, null, true));
    }

    /**
     * Registers a multi-tick handler. If the caller gives up (timeout), the future is completed
     * exceptionally so tick code can notice {@code isDone()} and stop.
     */
    public void onMainThreadAsync(String method, AsyncHandler handler) {
        methods.put(method, new Entry(null, handler, true));
    }

    /** Registers a handler that runs on the RPC worker thread. */
    public void onWorker(String method, Handler handler) {
        methods.put(method, new Entry(handler, null, false));
    }

    public List<String> methodNames() {
        List<String> names = new ArrayList<>(methods.keySet());
        Collections.sort(names);
        return names;
    }

    public boolean checkToken(JsonObject params) {
        JsonElement t = params.get("token");
        if (t == null || !t.isJsonPrimitive()) return false;
        return MessageDigest.isEqual(token, t.getAsString().getBytes(StandardCharsets.UTF_8));
    }

    public JsonElement dispatch(String method, JsonObject params) {
        Entry entry = methods.get(method);
        if (entry == null) throw new RpcException(RpcException.METHOD_NOT_FOUND, "Method not found: " + method);
        if (!entry.mainThread()) return entry.handler().handle(params);
        CompletableFuture<JsonElement> pending = null;
        try {
            if (entry.async() != null) {
                pending = Minecraft.getInstance()
                        .submit(() -> entry.async().handle(params))
                        .get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return pending.get(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            return Minecraft.getInstance()
                    .submit(() -> entry.handler().handle(params))
                    .get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (pending != null) pending.completeExceptionally(new RpcException(RpcException.TIMEOUT, "Timed out"));
            throw new RpcException(RpcException.TIMEOUT, "Timed out waiting for the client thread");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcException.INTERNAL_ERROR, "Interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException re) throw re;
            if (cause instanceof RuntimeException re) throw re;
            throw new RpcException(RpcException.INTERNAL_ERROR, String.valueOf(cause));
        }
    }
}

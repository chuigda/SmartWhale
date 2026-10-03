package dev.smartwhale.bridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.smartwhale.bridge.SmartWhaleBridge;
import dev.smartwhale.bridge.rpc.RpcDispatcher;
import dev.smartwhale.bridge.rpc.RpcException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/** One agent connection: JSON-RPC 2.0 framing, authentication gate, request dispatch. */
final class Session {
    private final WebSocketConnection ws;
    private final RpcDispatcher dispatcher;
    private final ExecutorService workers;
    volatile boolean authenticated;

    Session(WebSocketConnection ws, RpcDispatcher dispatcher, ExecutorService workers) {
        this.ws = ws;
        this.dispatcher = dispatcher;
        this.workers = workers;
    }

    void run() {
        try {
            String text;
            while ((text = ws.readMessage()) != null) {
                String message = text;
                workers.execute(() -> handleMessage(message));
            }
        } catch (IOException e) {
            if (ws.isOpen()) SmartWhaleBridge.LOGGER.info("Agent connection closed: {}", e.toString());
        } finally {
            ws.close();
        }
    }

    void close() {
        ws.close();
    }

    void notify(String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        msg.add("params", params);
        send(msg);
    }

    private void handleMessage(String text) {
        JsonElement id = null;
        try {
            JsonObject req;
            try {
                req = JsonParser.parseString(text).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new RpcException(RpcException.PARSE_ERROR, "Parse error");
            }
            id = req.get("id");
            if (!req.has("method") || !req.get("method").isJsonPrimitive()) {
                throw new RpcException(RpcException.INVALID_REQUEST, "Missing method");
            }
            String method = req.get("method").getAsString();
            JsonElement rawParams = req.get("params");
            JsonObject params = rawParams != null && rawParams.isJsonObject() ? rawParams.getAsJsonObject() : new JsonObject();

            if (!authenticated && !"bridge.hello".equals(method)) {
                throw new RpcException(RpcException.UNAUTHORIZED, "Call bridge.hello with the token first");
            }
            JsonElement result;
            if ("bridge.hello".equals(method)) {
                if (!dispatcher.checkToken(params)) {
                    reply(error(id, new RpcException(RpcException.UNAUTHORIZED, "Invalid token")));
                    ws.close();
                    return;
                }
                authenticated = true;
            }
            result = dispatcher.dispatch(method, params);
            if (id != null) {
                JsonObject resp = new JsonObject();
                resp.addProperty("jsonrpc", "2.0");
                resp.add("id", id);
                resp.add("result", result);
                reply(resp);
            }
        } catch (RpcException e) {
            if (id != null || e.code() == RpcException.PARSE_ERROR) reply(error(id, e));
        } catch (Throwable t) {
            SmartWhaleBridge.LOGGER.error("RPC handler failed", t);
            if (id != null) reply(error(id, new RpcException(RpcException.INTERNAL_ERROR, t.toString())));
        }
    }

    private void reply(JsonObject msg) {
        send(msg);
    }

    private void send(JsonObject msg) {
        try {
            ws.sendText(msg.toString());
        } catch (IOException e) {
            ws.close();
        }
    }

    private static JsonObject error(JsonElement id, RpcException e) {
        JsonObject err = new JsonObject();
        err.addProperty("code", e.code());
        err.addProperty("message", e.getMessage());
        if (e.hint() != null) {
            JsonObject data = new JsonObject();
            data.addProperty("hint", e.hint());
            err.add("data", data);
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", err);
        return resp;
    }
}

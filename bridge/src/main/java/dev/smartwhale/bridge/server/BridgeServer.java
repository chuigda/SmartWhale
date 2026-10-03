package dev.smartwhale.bridge.server;

import com.google.gson.JsonObject;
import dev.smartwhale.bridge.SmartWhaleBridge;
import dev.smartwhale.bridge.rpc.RpcDispatcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Listens on 127.0.0.1 only. A single agent connection at a time; a new connection replaces the old one. */
public final class BridgeServer {
    private final int port;
    private final RpcDispatcher dispatcher;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SmartWhale-RPC");
        t.setDaemon(true);
        return t;
    });
    private volatile Session current;

    public BridgeServer(int port, RpcDispatcher dispatcher) {
        this.port = port;
        this.dispatcher = dispatcher;
    }

    public void start() {
        Thread acceptor = new Thread(this::acceptLoop, "SmartWhale-Accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Sends a JSON-RPC notification to the authenticated agent, if any. Safe to call from any thread. */
    public void notify(String method, JsonObject params) {
        Session s = current;
        if (s != null && s.authenticated) {
            s.notify(method, params);
        }
    }

    private void acceptLoop() {
        try (ServerSocket ss = new ServerSocket()) {
            // Not getLoopbackAddress(): Minecraft sets java.net.preferIPv6Addresses, which makes it ::1.
            ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port));
            SmartWhaleBridge.LOGGER.info("SmartWhale bridge listening on ws://127.0.0.1:{}/rpc", ss.getLocalPort());
            while (true) {
                Socket socket = ss.accept();
                workers.execute(() -> handle(socket));
            }
        } catch (IOException e) {
            SmartWhaleBridge.LOGGER.error("SmartWhale bridge server stopped", e);
        }
    }

    private void handle(Socket socket) {
        WebSocketConnection ws;
        try {
            ws = WebSocketConnection.accept(socket, "/rpc");
        } catch (IOException e) {
            SmartWhaleBridge.LOGGER.warn("WebSocket handshake failed: {}", e.toString());
            return;
        }
        if (ws == null) return;

        Session session = new Session(ws, dispatcher, workers);
        Session previous = current;
        current = session;
        if (previous != null) {
            SmartWhaleBridge.LOGGER.info("New agent connection replaces the previous one");
            previous.close();
        }
        SmartWhaleBridge.LOGGER.info("Agent connected from {}", socket.getRemoteSocketAddress());
        try {
            session.run();
        } finally {
            if (current == session) current = null;
            SmartWhaleBridge.LOGGER.info("Agent disconnected");
        }
    }
}

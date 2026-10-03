package dev.smartwhale.bridge.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal RFC 6455 server-side connection: text frames, fragmentation, ping/pong, close.
 * MC does not ship netty-codec-http, so this avoids jar-in-jar dependencies.
 */
public final class WebSocketConnection {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private volatile boolean closed;

    private WebSocketConnection(Socket socket, DataInputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /** Performs the HTTP upgrade handshake; returns null (after replying with an error) if it is not a valid WS request. */
    public static WebSocketConnection accept(Socket socket, String expectedPath) throws IOException {
        socket.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        OutputStream out = socket.getOutputStream();

        String requestLine = readLine(in);
        Map<String, String> headers = new HashMap<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        String[] parts = requestLine.split(" ");
        String key = headers.get("sec-websocket-key");
        boolean upgrade = "websocket".equalsIgnoreCase(headers.get("upgrade"));
        if (parts.length < 2 || !"GET".equals(parts[0]) || !expectedPath.equals(parts[1]) || key == null || !upgrade) {
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            socket.close();
            return null;
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return new WebSocketConnection(socket, in, out);
    }

    /** Blocks until a complete text message arrives; returns null when the connection closes. */
    public String readMessage() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inMessage = false;
        while (true) {
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len < 0 || len > MAX_MESSAGE) throw new IOException("Frame too large: " + len);
            byte[] mask = new byte[4];
            if (masked) in.readFully(mask);
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            switch (opcode) {
                case 0x0, 0x1 -> {
                    if (opcode == 0x1) {
                        message.reset();
                        inMessage = true;
                    } else if (!inMessage) {
                        throw new IOException("Unexpected continuation frame");
                    }
                    if (message.size() + payload.length > MAX_MESSAGE) throw new IOException("Message too large");
                    message.write(payload);
                    if (fin) return message.toString(StandardCharsets.UTF_8);
                }
                case 0x2 -> throw new IOException("Binary frames are not supported");
                case 0x8 -> {
                    close();
                    return null;
                }
                case 0x9 -> writeFrame(0xA, payload);
                case 0xA -> { }
                default -> throw new IOException("Unknown opcode " + opcode);
            }
        }
    }

    public void sendText(String text) throws IOException {
        writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isOpen() {
        return !closed && !socket.isClosed();
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            writeFrame(0x8, new byte[0]);
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
        if (socket.isClosed()) throw new IOException("Socket closed");
        int len = payload.length;
        byte[] header;
        if (len < 126) {
            header = new byte[]{(byte) (0x80 | opcode), (byte) len};
        } else if (len <= 0xFFFF) {
            header = new byte[]{(byte) (0x80 | opcode), 126, (byte) (len >>> 8), (byte) len};
        } else {
            header = new byte[10];
            header[0] = (byte) (0x80 | opcode);
            header[1] = 127;
            for (int i = 0; i < 8; i++) header[9 - i] = (byte) ((long) len >>> (8 * i));
        }
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Header line too long");
        }
        return sb.toString();
    }

    private static String acceptKey(String key) {
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(sha1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

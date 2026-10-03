package dev.smartwhale.bridge.rpc;

/** JSON-RPC error with an optional actionable hint for the LLM (docs/DESIGN.md §6.2). */
public final class RpcException extends RuntimeException {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int NOT_IN_WORLD = 1001;
    public static final int TIMEOUT = 1002;
    public static final int INVALID_TARGET = 1003;
    public static final int NO_RECIPE = 1004;
    public static final int NO_MENU = 1005;
    public static final int TASK_BUSY = 1006;
    public static final int UNAUTHORIZED = 1007;

    private final int code;
    private final String hint;

    public RpcException(int code, String message) {
        this(code, message, null);
    }

    public RpcException(int code, String message, String hint) {
        super(message, null, false, false);
        this.code = code;
        this.hint = hint;
    }

    public int code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}

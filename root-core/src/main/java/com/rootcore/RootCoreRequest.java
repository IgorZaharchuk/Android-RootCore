package com.rootcore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A versioned RootCore protocol request. */
public final class RootCoreRequest {
    public static final int PROTOCOL_VERSION = 1;

    private final int protocolVersion;
    private final String requestId;
    private final String operation;
    private final Map<String, RootCoreValue> arguments;

    public RootCoreRequest(String requestId, String operation,
                           Map<String, RootCoreValue> arguments) {
        this(PROTOCOL_VERSION, requestId, operation, arguments);
    }

    public RootCoreRequest(int protocolVersion, String requestId, String operation,
                           Map<String, RootCoreValue> arguments) {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + protocolVersion);
        }
        this.protocolVersion = protocolVersion;
        this.requestId = requireText(requestId, "requestId");
        this.operation = requireText(operation, "operation");
        Objects.requireNonNull(arguments, "arguments");

        LinkedHashMap<String, RootCoreValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, RootCoreValue> entry : arguments.entrySet()) {
            copy.put(requireText(entry.getKey(), "argument name"),
                    Objects.requireNonNull(entry.getValue(), "argument value"));
        }
        this.arguments = Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }

    public int getProtocolVersion() { return protocolVersion; }
    public String getRequestId() { return requestId; }
    public String getOperation() { return operation; }
    public Map<String, RootCoreValue> getArguments() { return arguments; }
}

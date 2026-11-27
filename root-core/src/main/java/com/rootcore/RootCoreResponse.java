package com.rootcore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A successful result or a structured protocol error. */
public final class RootCoreResponse {
    private final int protocolVersion;
    private final String requestId;
    private final RootCoreValue result;
    private final Error error;

    private RootCoreResponse(int protocolVersion, String requestId,
                             RootCoreValue result, Error error) {
        if (protocolVersion != RootCoreRequest.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.result = result;
        this.error = error;
        if ((result == null) == (error == null)) {
            throw new IllegalArgumentException("Response must contain result or error");
        }
    }

    public static RootCoreResponse success(String requestId, RootCoreValue result) {
        return new RootCoreResponse(RootCoreRequest.PROTOCOL_VERSION, requestId,
                Objects.requireNonNull(result, "result"), null);
    }

    public static RootCoreResponse failure(String requestId, Error error) {
        return new RootCoreResponse(RootCoreRequest.PROTOCOL_VERSION, requestId,
                null, Objects.requireNonNull(error, "error"));
    }

    public int getProtocolVersion() { return protocolVersion; }
    public String getRequestId() { return requestId; }
    public boolean isSuccess() { return error == null; }
    public RootCoreValue getResult() { return result; }
    public Error getError() { return error; }

    public static final class Error {
        private final String code;
        private final String type;
        private final String message;
        private final String operation;
        private final Map<String, RootCoreValue> details;

        public Error(String code, String type, String message, String operation,
                     Map<String, RootCoreValue> details) {
            this.code = Objects.requireNonNull(code, "code");
            this.type = Objects.requireNonNull(type, "type");
            this.message = Objects.requireNonNull(message, "message");
            this.operation = operation == null ? "" : operation;

            LinkedHashMap<String, RootCoreValue> copy = new LinkedHashMap<>();
            if (details != null) {
                for (Map.Entry<String, RootCoreValue> entry : details.entrySet()) {
                    copy.put(Objects.requireNonNull(entry.getKey()),
                            Objects.requireNonNull(entry.getValue()));
                }
            }
            this.details = Collections.unmodifiableMap(copy);
        }

        public String getCode() { return code; }
        public String getType() { return type; }
        public String getMessage() { return message; }
        public String getOperation() { return operation; }
        public Map<String, RootCoreValue> getDetails() { return details; }
    }
}

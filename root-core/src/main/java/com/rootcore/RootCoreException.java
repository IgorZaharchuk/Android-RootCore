package com.rootcore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An error returned by a RootCore operation. */
public final class RootCoreException extends RuntimeException {
    private final String code;
    private final String remoteType;
    private final String operation;
    private final Map<String, RootCoreValue> details;

    public RootCoreException(RootCoreResponse.Error error) {
        super(error == null ? "Unknown RootCore error" : error.getMessage());
        if (error == null) {
            throw new IllegalArgumentException("Error is null");
        }
        this.code = error.getCode();
        this.remoteType = error.getType();
        this.operation = error.getOperation();
        this.details = Collections.unmodifiableMap(
                new LinkedHashMap<>(error.getDetails())
        );
    }

    public String getCode() { return code; }
    public String getRemoteType() { return remoteType; }
    public String getOperation() { return operation; }
    public Map<String, RootCoreValue> getDetails() { return details; }
}

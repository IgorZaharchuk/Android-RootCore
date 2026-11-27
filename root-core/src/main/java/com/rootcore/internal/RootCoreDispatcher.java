package com.rootcore.internal;

import com.rootcore.RootCoreRequest;
import com.rootcore.RootCoreValue;

/**
 * Protocol v1 operation router.
 * The server retains ownership of its registry and object lifecycle.
 */
final class RootCoreDispatcher {
    private RootCoreDispatcher() {}

    static RootCoreValue dispatch(RootCoreServer server, RootCoreRequest request)
            throws Throwable {
        String operation = request.getOperation();
        switch (operation) {
            case "CREATE_OBJECT":
            case "INVOKE_METHOD":
            case "RELEASE_OBJECT":
            case "GET_FIELD":
            case "SET_FIELD":
            case "GET_STATIC_FIELD":
            case "SET_STATIC_FIELD":
                return server.dispatchTypedOperation(request, operation);
            default:
                throw new IllegalArgumentException(
                        "Unknown RootCore operation: " + operation
                );
        }
    }
}

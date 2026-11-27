package com.rootcore.internal;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.rootcore.RootCorePolicy;
import com.rootcore.ipc.IRootCoreManager;
import com.rootcore.ipc.IRootCoreClient;


public final class RootCoreServer extends IRootCoreManager.Stub {

    private static final String TAG = "RootCore";
    private static final long IDLE_TIMEOUT_MS = 15_000L;
    private static final long RELEASE_CLOSE_TIMEOUT_MS = 8_000L;

    private final Context context;
    private final int clientUid;
    private final String sessionId;
    private final RootCorePolicy policy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ObjectRegistry objects = new ObjectRegistry();
    private final ThreadLocal<List<RegisteredObject>> operationObjects =
            new ThreadLocal<>();
    private final AtomicInteger activeOperations = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_OPERATIONS = 16;

    private IBinder clientToken;
    private IBinder.DeathRecipient deathRecipient;
    private volatile boolean connected;
    private volatile boolean closed;

    private final Runnable idleShutdown = this::shutdown;

    public RootCoreServer(
            Context context,
            int clientUid,
            String sessionId,
            String encodedPolicy
    ) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session ID is empty");
        }
        this.context = context;
        this.clientUid = clientUid;
        this.sessionId = sessionId;
        this.policy = RootCorePolicy.decode(encodedPolicy);
    }

    public void start() {
        if (Process.myUid() != 0) {
            throw new SecurityException("Root process has UID " + Process.myUid());
        }

        handler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS);

        Intent intent = new Intent(
                context.getPackageName() + ".ROOTCORE_BIND." + sessionId
        );
        intent.setPackage(context.getPackageName());
        intent.putExtra("extra.session_id", sessionId);
        intent.addFlags(0x00400000); // FLAG_RECEIVER_FROM_SHELL
        Bundle binderBundle = new Bundle();
        binderBundle.putBinder("binder", asBinder());
        intent.putExtra("extra.bundle", binderBundle);

        UserHandle targetUser = UserHandle.getUserHandleForUid(clientUid);
        if (Build.VERSION.SDK_INT >= 34) {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            // RootServerMain supplies a package context for the target user.
            // API 34 exposes the options overload only for sendBroadcast().
            context.sendBroadcast(intent, null, options.toBundle());
        } else {
            context.sendBroadcastAsUser(intent, targetUser);
        }

        android.util.Log.i(TAG, "Binder broadcast sent; root UID=" + Process.myUid());
    }

    @Override
    public int ping() {
        enforceClient();
        return Process.myUid();
    }

    @Override
    public synchronized void connect(IRootCoreClient client)
            throws RemoteException {
        enforceClient();

        if (closed) {
            throw new RemoteException("Root Core session is already closed");
        }

        if (client == null || client.asBinder() == null
                || !client.asBinder().isBinderAlive()) {
            throw new RemoteException("Client callback is not alive");
        }

        if (connected) {
            if (clientToken == client.asBinder()) return;
            throw new RemoteException("A client is already connected");
        }

        // The client callback observes the real Binder caller UID.
        // A forged broadcast/Binder cannot make this callback observe UID 0.
        int peerUid = client.verifyRootPeer();
        if (peerUid != 0) {
            throw new SecurityException(
                    "Root peer authentication failed; callback caller UID="
                            + peerUid
            );
        }

        clientToken = client.asBinder();
        deathRecipient = this::onClientDied;
        clientToken.linkToDeath(deathRecipient, 0);
        connected = true;
        handler.removeCallbacks(idleShutdown);

        android.util.Log.i(TAG, "Client connected; callback peer UID=0");
    }

    private void launchActivity(Intent intent) throws RemoteException {
        enforceConnectedClient();
        RootActivityLauncher.launch(intent, clientUid, policy);
    }

    private static String safeEnvelopeString(
            Bundle bundle, String key, String fallback
    ) {
        if (bundle == null) return fallback;
        try {
            Object value = bundle.get(key);
            if (!(value instanceof String)) return fallback;
            String text = (String) value;
            if (text.trim().isEmpty()
                    || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > RootCoreCodec.MAX_STRING_BYTES) {
                return fallback;
            }
            return text;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Override
    public Bundle execute(Bundle encodedRequest) throws RemoteException {
        String requestId = safeEnvelopeString(
                encodedRequest, "requestId", "unknown"
        );
        String operation = safeEnvelopeString(
                encodedRequest, "operation", "UNKNOWN"
        );

        boolean slotAcquired = false;
        List<RegisteredObject> createdObjects = new ArrayList<>();
        operationObjects.set(createdObjects);

        try {
            enforceConnectedClient();

            com.rootcore.RootCoreRequest request =
                    RootCoreCodec.decodeRequest(encodedRequest);
            requestId = request.getRequestId();
            operation = request.getOperation();

            enforceTypedOperationPermission(operation);

            int active = activeOperations.incrementAndGet();
            if (active > MAX_CONCURRENT_OPERATIONS) {
                activeOperations.decrementAndGet();
                throw new ResourceLimitException(
                        "Concurrent RootCore operation limit reached"
                );
            }
            slotAcquired = true;

            com.rootcore.RootCoreValue result =
                    RootCoreDispatcher.dispatch(this, request);

            Bundle response = RootCoreCodec.encodeResponse(
                    com.rootcore.RootCoreResponse.success(requestId, result)
            );

            // Ownership is now transferred to the client-visible response.
            createdObjects.clear();
            return response;

        } catch (Throwable error) {
            rollbackOperationObjects(createdObjects, error);

            Throwable cause = unwrap(error);
            String code = protocolErrorCode(cause);
            String message = cause.getClass().getSimpleName()
                    + ": " + String.valueOf(cause.getMessage());

            try {
                return RootCoreCodec.encodeResponse(
                        com.rootcore.RootCoreResponse.failure(
                                requestId,
                                new com.rootcore.RootCoreResponse.Error(
                                        code,
                                        cause.getClass().getName(),
                                        message,
                                        operation,
                                        java.util.Collections.emptyMap()
                                )
                        )
                );
            } catch (Throwable encodingError) {
                throw asRemoteException(
                        "RootCore protocol response failed", encodingError
                );
            }
        } finally {
            operationObjects.remove();
            if (slotAcquired) activeOperations.decrementAndGet();
        }
    }

    private void enforceTypedOperationPermission(String operation) {
        if (!policy.allowsOperation(operation)) {
            throw new SecurityException(
                    "Operation denied by RootCorePolicy: " + operation
            );
        }

        switch (operation) {
            case "CREATE_OBJECT":
            case "INVOKE_METHOD":
            case "RELEASE_OBJECT":
            case "GET_FIELD":
            case "SET_FIELD":
            case "GET_STATIC_FIELD":
            case "SET_STATIC_FIELD":
                if (Process.myUid() != 0) {
                    throw new SecurityException(
                            "Client code execution requires UID 0"
                    );
                }
                return;

            case "QUERY_PROVIDER":
            case "LAUNCH_ACTIVITY":
                return;

            default:
                throw new IllegalArgumentException(
                        "Unknown RootCore operation: " + operation
                );
        }
    }

    private static final class ResourceLimitException
            extends RuntimeException {
        ResourceLimitException(String message) {
            super(message);
        }
    }


    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        return current;
    }

    private static String protocolErrorCode(Throwable error) {
        if (error instanceof ResourceLimitException) return "RESOURCE_LIMIT";
        if (error instanceof SecurityException) return "PERMISSION_DENIED";
        if (error instanceof NoSuchFieldException) return "FIELD_NOT_FOUND";
        if (error instanceof NoSuchMethodException) return "METHOD_NOT_FOUND";
        if (error instanceof ClassNotFoundException) return "INVALID_ARGUMENT";
        if (error instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (error instanceof IllegalStateException) return "OBJECT_CLOSED";
        if (error instanceof UnsupportedOperationException) return "TYPE_MISMATCH";
        return "EXECUTION_FAILED";
    }



    com.rootcore.RootCoreValue dispatchTypedOperation(
            com.rootcore.RootCoreRequest request,
            String operation
    ) throws Throwable {
        Map<String, com.rootcore.RootCoreValue> args = request.getArguments();

        switch (operation) {
            case "CREATE_OBJECT":
                return typedCreate(args);
            case "INVOKE_METHOD":
                return typedInvoke(args);
            case "RELEASE_OBJECT":
                return typedRelease(args);
            case "GET_FIELD":
                return typedGetField(args);
            case "SET_FIELD":
                return typedSetField(args);
            case "GET_STATIC_FIELD":
                return typedGetStaticField(args);
            case "SET_STATIC_FIELD":
                return typedSetStaticField(args);
            case "QUERY_PROVIDER":
                return typedQueryProvider(args);
            case "LAUNCH_ACTIVITY":
                return typedLaunchActivity(args);
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }


    private com.rootcore.RootCoreValue extensionPayload(
            com.rootcore.RootCoreValue value,
            String expectedType,
            int expectedVersion
    ) {
        if (value == null
                || value.getKind() != com.rootcore.RootCoreValue.Kind.EXTENSION) {
            throw new IllegalArgumentException("Expected extension " + expectedType);
        }

        Map<String, com.rootcore.RootCoreValue> envelope = value.asFields();
        com.rootcore.RootCoreValue type = envelope.get("typeId");
        com.rootcore.RootCoreValue version = envelope.get("version");
        com.rootcore.RootCoreValue payload = envelope.get("payload");

        if (type == null || version == null || payload == null
                || type.getKind() != com.rootcore.RootCoreValue.Kind.STRING
                || version.getKind() != com.rootcore.RootCoreValue.Kind.INT) {
            throw new IllegalArgumentException("Malformed extension envelope");
        }

        if (!expectedType.equals(type.asString())
                || expectedVersion != version.asInt()) {
            throw new IllegalArgumentException("Unexpected extension type/version");
        }

        if (!policy.allowsExtension(expectedType, expectedVersion)) {
            throw new SecurityException(
                    "Extension denied by RootCorePolicy: " + expectedType
            );
        }
        return payload;
    }

    private String decodeUriExtension(com.rootcore.RootCoreValue value) {
        com.rootcore.RootCoreValue payload =
                extensionPayload(value, "URI", 1);
        if (payload.getKind() != com.rootcore.RootCoreValue.Kind.STRING) {
            throw new IllegalArgumentException("URI payload must be a string");
        }
        return payload.asString();
    }

    private android.content.ComponentName decodeComponentName(
            com.rootcore.RootCoreValue value
    ) {
        com.rootcore.RootCoreValue payload =
                extensionPayload(value, "COMPONENT_NAME", 1);
        if (payload.getKind() != com.rootcore.RootCoreValue.Kind.STRUCT) {
            throw new IllegalArgumentException("ComponentName payload must be a struct");
        }

        Map<String, com.rootcore.RootCoreValue> fields = payload.asFields();
        com.rootcore.RootCoreValue pkg = fields.get("packageName");
        com.rootcore.RootCoreValue cls = fields.get("className");

        if (pkg == null || cls == null
                || pkg.getKind() != com.rootcore.RootCoreValue.Kind.STRING
                || cls.getKind() != com.rootcore.RootCoreValue.Kind.STRING) {
            throw new IllegalArgumentException("Invalid ComponentName payload");
        }
        return new android.content.ComponentName(
                pkg.asString(), cls.asString()
        );
    }

    private com.rootcore.RootCoreValue typedQueryProvider(
            Map<String, com.rootcore.RootCoreValue> args
    ) throws RemoteException {
        String uri = decodeUriExtension(requiredArg(args, "uri"));

        String[] projection = null;
        com.rootcore.RootCoreValue projectionValue = args.get("projection");
        if (projectionValue != null
                && projectionValue.getKind()
                != com.rootcore.RootCoreValue.Kind.NULL) {
            if (projectionValue.getKind()
                    != com.rootcore.RootCoreValue.Kind.ARRAY
                    && projectionValue.getKind()
                    != com.rootcore.RootCoreValue.Kind.LIST) {
                throw new IllegalArgumentException("Projection must be an array/list");
            }

            List<com.rootcore.RootCoreValue> values =
                    projectionValue.asSequence();
            projection = new String[values.size()];
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).getKind()
                        != com.rootcore.RootCoreValue.Kind.STRING) {
                    throw new IllegalArgumentException(
                            "Projection entries must be strings"
                    );
                }
                projection[i] = values.get(i).asString();
            }
        }

        Bundle result = ExternalProviderQuery.query(
                uri, projection, clientUid, policy
        );

        String[] columns = result.getStringArray("columns");
        ArrayList<Bundle> rows = result.getParcelableArrayList("rows");

        List<com.rootcore.RootCoreValue> columnValues = new ArrayList<>();
        if (columns != null) {
            for (String column : columns) {
                columnValues.add(com.rootcore.RootCoreValue.of(column));
            }
        }

        List<com.rootcore.RootCoreValue> rowValues = new ArrayList<>();
        if (rows != null) {
            for (Bundle row : rows) {
                Map<String, com.rootcore.RootCoreValue> cells =
                        new LinkedHashMap<>();
                if (columns != null) {
                    for (int i = 0; i < columns.length; i++) {
                        Object cell = row.get("c" + i);
                        com.rootcore.RootCoreValue value;
                        if (cell == null) {
                            value = com.rootcore.RootCoreValue.nullValue();
                        } else if (cell instanceof String) {
                            value = com.rootcore.RootCoreValue.of((String) cell);
                        } else if (cell instanceof byte[]) {
                            value = com.rootcore.RootCoreValue.of((byte[]) cell);
                        } else if (cell instanceof Long) {
                            value = com.rootcore.RootCoreValue.of((Long) cell);
                        } else if (cell instanceof Integer) {
                            value = com.rootcore.RootCoreValue.of((Integer) cell);
                        } else if (cell instanceof Double) {
                            value = com.rootcore.RootCoreValue.of((Double) cell);
                        } else if (cell instanceof Float) {
                            value = com.rootcore.RootCoreValue.of((Float) cell);
                        } else {
                            throw new IllegalStateException(
                                    "Unsupported Provider cell value: "
                                            + cell.getClass().getName()
                            );
                        }
                        cells.put("c" + i, value);
                    }
                }
                rowValues.add(com.rootcore.RootCoreValue.struct(cells));
            }
        }

        Map<String, com.rootcore.RootCoreValue> output =
                new LinkedHashMap<>();
        output.put("columns", com.rootcore.RootCoreValue.array(columnValues));
        output.put("rows", com.rootcore.RootCoreValue.array(rowValues));
        return com.rootcore.RootCoreValue.struct(output);
    }

    private com.rootcore.RootCoreValue typedLaunchActivity(
            Map<String, com.rootcore.RootCoreValue> args
    ) throws RemoteException {
        com.rootcore.RootCoreValue payload = extensionPayload(
                requiredArg(args, "intent"), "INTENT", 1
        );
        if (payload.getKind() != com.rootcore.RootCoreValue.Kind.STRUCT) {
            throw new IllegalArgumentException("Intent payload must be a struct");
        }

        Map<String, com.rootcore.RootCoreValue> fields = payload.asFields();
        Intent intent = new Intent();

        com.rootcore.RootCoreValue componentValue = fields.get("component");
        if (componentValue == null) {
            throw new IllegalArgumentException("Intent component is required");
        }
        intent.setComponent(decodeComponentName(componentValue));

        com.rootcore.RootCoreValue action = fields.get("action");
        if (action != null
                && action.getKind() != com.rootcore.RootCoreValue.Kind.NULL) {
            if (action.getKind() != com.rootcore.RootCoreValue.Kind.STRING) {
                throw new IllegalArgumentException("Intent action must be a string");
            }
            intent.setAction(action.asString());
        }

        com.rootcore.RootCoreValue flags = fields.get("flags");
        if (flags != null) {
            if (flags.getKind() != com.rootcore.RootCoreValue.Kind.INT) {
                throw new IllegalArgumentException("Intent flags must be INT");
            }
            intent.setFlags(flags.asInt());
        }

        com.rootcore.RootCoreValue extras = fields.get("extras");
        if (extras != null) {
            if (extras.getKind() != com.rootcore.RootCoreValue.Kind.STRUCT
                    && extras.getKind() != com.rootcore.RootCoreValue.Kind.MAP) {
                throw new IllegalArgumentException("Intent extras must be a map");
            }

            for (Map.Entry<String, com.rootcore.RootCoreValue> entry
                    : extras.asFields().entrySet()) {
                String key = entry.getKey();
                com.rootcore.RootCoreValue item = entry.getValue();
                switch (item.getKind()) {
                    case STRING:
                        intent.putExtra(key, item.asString());
                        break;
                    case BOOLEAN:
                        intent.putExtra(key, item.asBoolean());
                        break;
                    case INT:
                        intent.putExtra(key, item.asInt());
                        break;
                    case LONG:
                        intent.putExtra(key, item.asLong());
                        break;
                    case FLOAT:
                        intent.putExtra(key, item.asFloat());
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unsupported Intent extra type: " + key
                        );
                }
            }
        }

        launchActivity(intent);
        return com.rootcore.RootCoreValue.voidValue();
    }

    private static final class RegisteredObject {
        final String id;
        final ObjectEntry entry;

        RegisteredObject(String id, ObjectEntry entry) {
            this.id = id;
            this.entry = entry;
        }
    }

    private void registerObject(String id, ObjectEntry entry)
            throws Throwable {
        try {
            if (!objects.register(id, entry)) {
                throw new IllegalStateException(
                        "RootCore session is closing"
                );
            }
        } catch (Throwable registrationError) {
            closeFailedRegistration(entry, registrationError);
            throw registrationError;
        }

        List<RegisteredObject> current = operationObjects.get();
        if (current == null) {
            ObjectEntry removed = objects.remove(id);
            IllegalStateException registrationError =
                    new IllegalStateException(
                            "Object registration outside an active operation"
                    );
            closeFailedRegistration(
                    removed == null ? entry : removed,
                    registrationError
            );
            throw registrationError;
        }

        try {
            current.add(new RegisteredObject(id, entry));
        } catch (Throwable trackingError) {
            ObjectEntry removed = objects.remove(id);
            closeFailedRegistration(
                    removed == null ? entry : removed,
                    trackingError
            );
            throw trackingError;
        }
    }

    private void closeFailedRegistration(
            ObjectEntry entry,
            Throwable originalError
    ) {
        try {
            entry.requestClose();
        } catch (Throwable closeError) {
            originalError.addSuppressed(closeError);
        }

        try {
            Throwable closeError =
                    entry.closeAndWait(RELEASE_CLOSE_TIMEOUT_MS);
            if (closeError != null) {
                originalError.addSuppressed(closeError);
            }
        } catch (Throwable closeError) {
            originalError.addSuppressed(closeError);
        }
    }

    private void rollbackOperationObjects(
            List<RegisteredObject> created,
            Throwable originalError
    ) {
        List<ObjectEntry> closing = new ArrayList<>();

        for (int i = created.size() - 1; i >= 0; i--) {
            RegisteredObject registered = created.get(i);
            ObjectEntry removed = objects.remove(registered.id);
            ObjectEntry entry = removed == null
                    ? registered.entry : removed;

            try {
                entry.requestClose();
                closing.add(entry);
            } catch (Throwable closeError) {
                originalError.addSuppressed(closeError);
            }
        }

        long deadline = System.nanoTime()
                + RELEASE_CLOSE_TIMEOUT_MS * 1_000_000L;

        for (ObjectEntry entry : closing) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;

            try {
                Throwable closeError = entry.closeAndWait(
                        Math.max(1L, remaining / 1_000_000L)
                );
                if (closeError != null) {
                    originalError.addSuppressed(closeError);
                }
            } catch (Throwable closeError) {
                originalError.addSuppressed(closeError);
            }
        }

        created.clear();
    }

    private com.rootcore.RootCoreValue typedCreate(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    List<ObjectEntry> leases = new ArrayList<>();
    try {
        String className = stringArg(args, "className");
        Class<?> type = loadClientType(className);
        requireClientClassOrigin(type);
        Class<?>[] parameterTypes = typeArray(args.get("parameterTypes"));
        Object[] values = objectArray(args.get("arguments"), parameterTypes, leases);

        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        if (!constructor.isAccessible()) constructor.setAccessible(true);

        Object instance;
        try {
            instance = constructor.newInstance(values);
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }

        String id = UUID.randomUUID().toString();
        ObjectEntry entry = new ObjectEntry(instance);
        registerObject(id, entry);

        return com.rootcore.RootCoreValue.objectRef(
                id, sessionId, instance.getClass().getName()
        );
    } finally {
        releaseLeases(leases);
    }
}

    private com.rootcore.RootCoreValue typedInvoke(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    List<ObjectEntry> leases = new ArrayList<>();
    try {
        ObjectEntry entry = requireObject(args.get("object"));
        Object target = entry.acquireLease();
        leases.add(entry);

        String methodName = stringArg(args, "methodName");
        Class<?>[] parameterTypes = typeArray(args.get("parameterTypes"));
        Object[] values = objectArray(args.get("arguments"), parameterTypes, leases);

        Method method = findMethod(entry.type(), methodName, parameterTypes);
        requireClientClassOrigin(method.getDeclaringClass());
        if (method.getDeclaringClass() == Object.class) {
            throw new SecurityException("Object base methods are not callable");
        }
        if (!method.isAccessible()) method.setAccessible(true);

        Object result;
        try {
            result = method.invoke(target, values);
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
        return toRootValue(result, method.getReturnType());
    } finally {
        releaseLeases(leases);
    }
}

    private com.rootcore.RootCoreValue typedRelease(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    ObjectRef ref = objectRef(args.get("object"));

    if (!sessionId.equals(ref.sessionId)) {
        throw new SecurityException("Object belongs to another session");
    }

    ObjectEntry existing = objects.get(ref.objectId);
    if (existing == null) {
        throw new IllegalArgumentException("Unknown or released object ID");
    }
    if (!existing.type().getName().equals(ref.typeName)) {
        throw new SecurityException("Object reference type does not match registry");
    }

    ObjectEntry removed = objects.remove(ref.objectId);
    if (removed == null) {
        throw new IllegalArgumentException("Unknown or released object ID");
    }
    if (removed != existing) {
        throw new IllegalStateException("Object registry changed during release");
    }

    Throwable closeError = removed.closeAndWait(RELEASE_CLOSE_TIMEOUT_MS);
    if (closeError != null) throw closeError;

    return com.rootcore.RootCoreValue.of(true);
}

    private com.rootcore.RootCoreValue typedGetField(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    ObjectEntry entry = requireObject(args.get("object"));
    Object target = entry.acquireLease();
    try {
        String name = stringArg(args, "fieldName");
        java.lang.reflect.Field field = findField(entry.type(), name);
        requireClientClassOrigin(field.getDeclaringClass());

        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Use GET_STATIC_FIELD for static fields");
        }
        if (!field.isAccessible()) field.setAccessible(true);

        Object value = field.get(target);
        return toRootValue(value, field.getType());
    } finally {
        entry.releaseLease();
    }
}

    private com.rootcore.RootCoreValue typedSetField(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    ObjectEntry entry = requireObject(args.get("object"));
    Object target = entry.acquireLease();
    List<ObjectEntry> leases = new ArrayList<>();

    try {
        String name = stringArg(args, "fieldName");
        java.lang.reflect.Field field = findField(entry.type(), name);
        requireClientClassOrigin(field.getDeclaringClass());

        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Use SET_STATIC_FIELD for static fields");
        }
        if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
            throw new IllegalArgumentException("Final fields cannot be modified");
        }
        if (!field.isAccessible()) field.setAccessible(true);

        Object value = fromRootValue(
                requiredArg(args, "value"), field.getType(), leases
        );
        field.set(target, value);
        return com.rootcore.RootCoreValue.voidValue();
    } finally {
        releaseLeases(leases);
        entry.releaseLease();
    }
}

    private com.rootcore.RootCoreValue typedGetStaticField(
            Map<String, com.rootcore.RootCoreValue> args
    ) throws Throwable {
        Class<?> type = loadClientType(stringArg(args, "className"));
        requireClientClassOrigin(type);

        java.lang.reflect.Field field =
                findField(type, stringArg(args, "fieldName"));
        requireClientClassOrigin(field.getDeclaringClass());
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Field is not static");
        }
        if (!field.isAccessible()) field.setAccessible(true);
        return toRootValue(field.get(null), field.getType());
    }

    private com.rootcore.RootCoreValue typedSetStaticField(
            Map<String, com.rootcore.RootCoreValue> args
    ) throws Throwable {
        List<ObjectEntry> leases = new ArrayList<>();
        try {
            Class<?> type = loadClientType(stringArg(args, "className"));
            requireClientClassOrigin(type);

            java.lang.reflect.Field field =
                    findField(type, stringArg(args, "fieldName"));
            requireClientClassOrigin(field.getDeclaringClass());
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                throw new IllegalArgumentException("Field is not static");
            }
            if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                throw new IllegalArgumentException("Final fields cannot be modified");
            }
            if (!field.isAccessible()) field.setAccessible(true);

            Object value = fromRootValue(
                    requiredArg(args, "value"), field.getType(), leases
            );
            field.set(null, value);
            return com.rootcore.RootCoreValue.voidValue();
        } finally {
            releaseLeases(leases);
        }
    }

    private ObjectEntry requireObject(com.rootcore.RootCoreValue value) {
    ObjectRef ref = objectRef(value);

    if (!sessionId.equals(ref.sessionId)) {
        throw new SecurityException("Object belongs to another session");
    }

    ObjectEntry entry = objects.get(ref.objectId);
    if (entry == null) {
        throw new IllegalArgumentException("Unknown or released object ID");
    }
    if (!entry.type().getName().equals(ref.typeName)) {
        throw new SecurityException("Object reference type does not match registry");
    }
    return entry;
}

    private ObjectRef objectRef(com.rootcore.RootCoreValue value) {
        if (value == null || value.getKind()
                != com.rootcore.RootCoreValue.Kind.OBJECT_REF) {
            throw new IllegalArgumentException("Expected OBJECT_REF");
        }
        Map<String, com.rootcore.RootCoreValue> fields = value.asFields();
        return new ObjectRef(
                stringArg(fields, "objectId"),
                stringArg(fields, "sessionId"),
                stringArg(fields, "typeName")
        );
    }

    private static final class ObjectRef {
        final String objectId;
        final String sessionId;
        final String typeName;
        ObjectRef(String id, String session, String type) {
            objectId = id;
            sessionId = session;
            typeName = type;
        }
    }

    private static com.rootcore.RootCoreValue requiredArg(
            Map<String, com.rootcore.RootCoreValue> args, String key
    ) {
        com.rootcore.RootCoreValue value = args.get(key);
        if (value == null) throw new IllegalArgumentException("Missing argument: " + key);
        return value;
    }

    private static String stringArg(
            Map<String, com.rootcore.RootCoreValue> args, String key
    ) {
        com.rootcore.RootCoreValue value = requiredArg(args, key);
        if (value.getKind() != com.rootcore.RootCoreValue.Kind.STRING) {
            throw new IllegalArgumentException("Expected STRING: " + key);
        }
        return value.asString();
    }

    private static final int MAX_TYPE_ARRAY_DEPTH = 16;
    private static final int MAX_TYPE_NAME_LENGTH = 512;

    private Class<?> loadClientType(String name) throws ClassNotFoundException {
        return loadClientType(name, 0);
    }

    private Class<?> loadClientType(String name, int depth)
            throws ClassNotFoundException {
        if (name == null || name.trim().isEmpty()
                || name.length() > MAX_TYPE_NAME_LENGTH) {
            throw new IllegalArgumentException("Class name is empty or too long");
        }
        if (depth > MAX_TYPE_ARRAY_DEPTH) {
            throw new IllegalArgumentException("Array type nesting is too deep");
        }

        switch (name) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "char": return char.class;
            default:
                if (name.endsWith("[]")) {
                    Class<?> component = loadClientType(
                            name.substring(0, name.length() - 2),
                            depth + 1
                    );
                    return java.lang.reflect.Array.newInstance(component, 0).getClass();
                }
                return context.getClassLoader().loadClass(name);
        }
    }


    private void requireClientClassOrigin(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("Client class is null");
        }

        ClassLoader appLoader = context.getClassLoader();
        ClassLoader actual = type.getClassLoader();

        // Permit classes defined by the application loader and its
        // descendants (for example, application plugin/dependency loaders).
        ClassLoader current = actual;
        while (current != null && current != appLoader) {
            current = current.getParent();
        }
        if (current != appLoader) {
            throw new SecurityException(
                    "Class is not defined by the client application loader: "
                            + type.getName()
            );
        }
    }

    private Class<?>[] typeArray(com.rootcore.RootCoreValue value)
            throws ClassNotFoundException {
        if (value == null || value.getKind() != com.rootcore.RootCoreValue.Kind.ARRAY
                && value.getKind() != com.rootcore.RootCoreValue.Kind.LIST) {
            throw new IllegalArgumentException("parameterTypes must be ARRAY or LIST");
        }
        List<com.rootcore.RootCoreValue> items = value.asSequence();
        Class<?>[] result = new Class<?>[items.size()];
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getKind() != com.rootcore.RootCoreValue.Kind.STRING) {
                throw new IllegalArgumentException("Parameter type must be STRING");
            }
            result[i] = loadClientType(items.get(i).asString());
        }
        return result;
    }

    private Object[] objectArray(
        com.rootcore.RootCoreValue value,
        Class<?>[] types,
        List<ObjectEntry> leases
) throws Throwable {
    if (value == null
            || (value.getKind() != com.rootcore.RootCoreValue.Kind.ARRAY
            && value.getKind() != com.rootcore.RootCoreValue.Kind.LIST)) {
        throw new IllegalArgumentException("arguments must be ARRAY or LIST");
    }

    List<com.rootcore.RootCoreValue> items = value.asSequence();
    if (items.size() != types.length) {
        throw new IllegalArgumentException("Argument count does not match parameter count");
    }

    Object[] result = new Object[items.size()];
    for (int i = 0; i < items.size(); i++) {
        result[i] = fromRootValue(items.get(i), types[i], leases);
    }
    return result;
}

    private Object fromRootValue(
        com.rootcore.RootCoreValue value,
        Class<?> target,
        List<ObjectEntry> leases
) throws Throwable {
    if (value == null) throw new IllegalArgumentException("Value is null");

    switch (value.getKind()) {
        case NULL:
            if (target.isPrimitive()) {
                throw new IllegalArgumentException("NULL for primitive");
            }
            return null;
        case BOOLEAN: return value.asBoolean();
        case BYTE: return value.asByte();
        case SHORT: return value.asShort();
        case INT: return value.asInt();
        case LONG: return value.asLong();
        case FLOAT: return value.asFloat();
        case DOUBLE: return value.asDouble();
        case STRING:
            if (target == char.class || target == Character.class) {
                String s = value.asString();
                if (s.length() != 1) {
                    throw new IllegalArgumentException("Expected one character");
                }
                return s.charAt(0);
            }
            return value.asString();
        case BYTE_ARRAY:
            return value.asByteArray();
        case OBJECT_REF:
            return acquireObjectValue(value, target, leases);
        case ARRAY:
        case LIST: {
            List<com.rootcore.RootCoreValue> items = value.asSequence();

            if (target.isArray()) {
                Class<?> component = target.getComponentType();
                Object array = java.lang.reflect.Array.newInstance(component, items.size());
                for (int i = 0; i < items.size(); i++) {
                    java.lang.reflect.Array.set(
                            array, i, fromRootValue(items.get(i), component, leases)
                    );
                }
                return array;
            }

            if (java.util.Set.class.isAssignableFrom(target)) {
                if (!target.isAssignableFrom(java.util.LinkedHashSet.class)) {
                    throw new UnsupportedOperationException(
                            "Set target cannot accept LinkedHashSet: " + target
                    );
                }
                java.util.LinkedHashSet<Object> set = new java.util.LinkedHashSet<>();
                for (com.rootcore.RootCoreValue item : items) {
                    set.add(fromRootValue(item, Object.class, leases));
                }
                return set;
            }

            if (target == Object.class
                    || target.isAssignableFrom(ArrayList.class)) {
                ArrayList<Object> list = new ArrayList<>();
                for (com.rootcore.RootCoreValue item : items) {
                    list.add(fromRootValue(item, Object.class, leases));
                }
                return list;
            }

            throw new UnsupportedOperationException(
                    "Sequence cannot be converted to " + target
            );
        }
        case MAP:
        case STRUCT: {
            if (target != Object.class && !Map.class.isAssignableFrom(target)) {
                throw new UnsupportedOperationException("MAP/STRUCT target must be Map or Object");
            }

            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, com.rootcore.RootCoreValue> entry
                    : value.asFields().entrySet()) {
                map.put(
                        entry.getKey(),
                        fromRootValue(entry.getValue(), Object.class, leases)
                );
            }
            return map;
        }
        case VOID:
            throw new IllegalArgumentException("VOID cannot be used as an argument");
        case EXTENSION:
            throw new UnsupportedOperationException(
                    "EXTENSION codecs are not available in Stage 2"
            );
        default:
            throw new IllegalArgumentException("Unsupported value kind");
    }
}

    private com.rootcore.RootCoreValue toRootValue(Object value, Class<?> declaredType)
            throws Throwable {
        if (declaredType == void.class || value == null) {
            return declaredType == void.class
                    ? com.rootcore.RootCoreValue.voidValue()
                    : com.rootcore.RootCoreValue.nullValue();
        }
        if (value instanceof Boolean) return com.rootcore.RootCoreValue.of((Boolean) value);
        if (value instanceof Byte) return com.rootcore.RootCoreValue.of((Byte) value);
        if (value instanceof Short) return com.rootcore.RootCoreValue.of((Short) value);
        if (value instanceof Integer) return com.rootcore.RootCoreValue.of((Integer) value);
        if (value instanceof Long) return com.rootcore.RootCoreValue.of((Long) value);
        if (value instanceof Float) return com.rootcore.RootCoreValue.of((Float) value);
        if (value instanceof Double) return com.rootcore.RootCoreValue.of((Double) value);
        if (value instanceof Character) return com.rootcore.RootCoreValue.of(String.valueOf(value));
        if (value instanceof String) return com.rootcore.RootCoreValue.of((String) value);
        if (value instanceof byte[]) return com.rootcore.RootCoreValue.of((byte[]) value);

        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            if (length > RootCoreCodec.MAX_COLLECTION_ITEMS) {
                throw new ResourceLimitException("Result array exceeds item limit");
            }
            List<com.rootcore.RootCoreValue> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                items.add(toRootValue(
                        java.lang.reflect.Array.get(value, i),
                        value.getClass().getComponentType()
                ));
            }
            return com.rootcore.RootCoreValue.array(items);
        }
        if (value instanceof Iterable) {
            List<com.rootcore.RootCoreValue> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                if (items.size() >= RootCoreCodec.MAX_COLLECTION_ITEMS) {
                    throw new IllegalArgumentException("Result collection exceeds item limit");
                }
                items.add(toRootValue(item, item == null ? Object.class : item.getClass()));
            }
            return com.rootcore.RootCoreValue.list(items);
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            if (source.size() > RootCoreCodec.MAX_COLLECTION_ITEMS) {
                throw new ResourceLimitException("Result map exceeds item limit");
            }
            Map<String, com.rootcore.RootCoreValue> fields = new HashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new UnsupportedOperationException("Only String map keys are supported");
                }
                fields.put((String) entry.getKey(),
                        toRootValue(entry.getValue(),
                                entry.getValue() == null ? Object.class : entry.getValue().getClass()));
            }
            return com.rootcore.RootCoreValue.map(fields);
        }

        String id = UUID.randomUUID().toString();
        ObjectEntry entry = new ObjectEntry(value);
        registerObject(id, entry);
        return com.rootcore.RootCoreValue.objectRef(
                id, sessionId, value.getClass().getName()
        );
    }


    private Object acquireObjectValue(
            com.rootcore.RootCoreValue value,
            Class<?> target,
            List<ObjectEntry> leases
    ) {
        ObjectEntry entry = requireObject(value);
        Object instance = entry.acquireLease();
        leases.add(entry);

        if (target != Object.class && !target.isInstance(instance)) {
            throw new IllegalArgumentException(
                    "Object reference is not assignable to " + target.getName()
            );
        }
        return instance;
    }

    private static void releaseLeases(List<ObjectEntry> leases) {
        for (int i = leases.size() - 1; i >= 0; i--) {
            try {
                leases.get(i).releaseLease();
            } catch (Throwable ignored) {
                // Lease release must not hide the original operation error.
            }
        }
        leases.clear();
    }

    private static Method findMethod(
            Class<?> type, String name, Class<?>[] parameterTypes
    ) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name)
            throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static RemoteException asRemoteException(String message, Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }
        RemoteException result = new RemoteException(
                message + ": " + cause.getClass().getSimpleName()
                        + ": " + String.valueOf(cause.getMessage())
        );
        result.initCause(cause);
        return result;
    }

    private static final class ObjectRegistry {
        private final Map<String, ObjectEntry> entries = new HashMap<>();
        private boolean closed;

        synchronized boolean register(String id, ObjectEntry entry) {
            if (closed) return false;
            if (entries.size() >= 256) {
                throw new ResourceLimitException(
                        "RootCore object limit reached (256)"
                );
            }
            entries.put(id, entry);
            return true;
        }

        synchronized ObjectEntry get(String id) {
            return closed || id == null ? null : entries.get(id);
        }

        synchronized ObjectEntry remove(String id) {
            if (closed || id == null) return null;

            ObjectEntry entry = entries.remove(id);
            if (entry != null) {
                entry.markClosing();
            }
            return entry;
        }

        synchronized boolean containsKey(String id) {
            return !closed && id != null && entries.containsKey(id);
        }

        synchronized int size() {
            return entries.size();
        }

        synchronized List<ObjectEntry> detachAll() {
            closed = true;
            List<ObjectEntry> result = new ArrayList<>(entries.values());

            for (ObjectEntry entry : result) {
                entry.markClosing();
            }

            entries.clear();
            return result;
        }
    }

    private static final class ObjectEntry {
        private final Object instance;

        private boolean closing;
        private boolean closeStarted;
        private boolean closeFinished;
        private int activeInvocations;
        private Throwable closeError;

        ObjectEntry(Object instance) {
            this.instance = instance;
        }

        Object instance() {
            return instance;
        }

        Class<?> type() {
            return instance.getClass();
        }

        Object acquireLease() {
            beginInvocation();
            return instance;
        }

        void releaseLease() {
            endInvocation();
        }

        Object invoke(Method method, Object[] arguments) throws Throwable {
            beginInvocation();
            try {
                return method.invoke(instance, arguments);
            } finally {
                endInvocation();
            }
        }

        Object readField(java.lang.reflect.Field field) throws Throwable {
            beginInvocation();
            try {
                return field.get(instance);
            } finally {
                endInvocation();
            }
        }

        void writeField(java.lang.reflect.Field field, Object value) throws Throwable {
            beginInvocation();
            try {
                field.set(instance, value);
            } finally {
                endInvocation();
            }
        }

        private synchronized void beginInvocation() {
            if (closing) {
                throw new IllegalStateException("Object is closing or has been released");
            }

            activeInvocations++;
        }

        private synchronized void endInvocation() {
            if (activeInvocations > 0) {
                activeInvocations--;
            }
            notifyAll();
        }

        synchronized void markClosing() {
            closing = true;
            notifyAll();
        }

        void requestClose() {
            Thread worker = null;

            synchronized (this) {
                closing = true;
                notifyAll();

                if (closeStarted || closeFinished) return;
                closeStarted = true;

                worker = new Thread(this::performClose, "RootCoreObjectClose");
                worker.setDaemon(true);
            }

            try {
                worker.start();
            } catch (Throwable error) {
                synchronized (this) {
                    closeError = error;
                    closeFinished = true;
                    notifyAll();
                }
            }
        }

        private void performClose() {
            boolean interrupted = false;

            try {
                synchronized (this) {
                    while (activeInvocations > 0) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                }

                if (instance instanceof AutoCloseable) {
                    ((AutoCloseable) instance).close();
                }
            } catch (Exception error) {
                closeError = error;
            } catch (Error error) {
                closeError = error;
                throw error;
            } finally {
                synchronized (this) {
                    closeFinished = true;
                    notifyAll();
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        Throwable closeAndWait(long timeoutMs) {
            requestClose();
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

            synchronized (this) {
                while (!closeFinished) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        return new TimeoutException(
                                "Timed out waiting for AutoCloseable.close()"
                        );
                    }

                    try {
                        long waitMs = Math.max(
                                1L,
                                Math.min(remaining / 1_000_000L, 1_000L)
                        );
                        wait(waitMs);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return error;
                    }
                }

                return closeError;
            }
        }
    }

    @Override
    public void close() {
        enforceClient();
        handler.post(this::shutdown);
    }

    private void enforceClient() {
        if (Binder.getCallingUid() != clientUid) {
            throw new SecurityException(
                    "Unexpected Binder caller UID: " + Binder.getCallingUid()
            );
        }
    }

    private void enforceConnectedClient() {
        enforceClient();
        if (!connected || closed) {
            throw new SecurityException("Root Core session is not connected");
        }
    }

    private void onClientDied() {
        handler.post(() -> {
            android.util.Log.w(TAG, "Client Binder died");
            shutdown();
        });
    }

    private synchronized void shutdown() {
        if (closed) return;
        closed = true;

        handler.removeCallbacks(idleShutdown);

        List<ObjectEntry> remainingObjects = objects.detachAll();

        // Start all object close operations before waiting for any of them.
        for (ObjectEntry entry : remainingObjects) {
            entry.requestClose();
        }

        if (clientToken != null && deathRecipient != null) {
            try {
                clientToken.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
        }

        clientToken = null;
        deathRecipient = null;
        connected = false;

        android.util.Log.i(TAG,
                "Root Core session closing; detached objects="
                        + remainingObjects.size());

        // Never block the main Looper while client operations or object
        // cleanup may need it. All waiting happens on a dedicated worker.
        Thread shutdownWorker = new Thread(() -> {
            // One shared deadline prevents 256 objects from each consuming
            // a separate RELEASE_CLOSE_TIMEOUT_MS interval.
            long deadline = System.nanoTime()
                    + RELEASE_CLOSE_TIMEOUT_MS * 1_000_000L;

            while (activeOperations.get() > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    android.util.Log.w(
                            TAG,
                            "Shutdown deadline reached with active operations="
                                    + activeOperations.get()
                    );
                    break;
                }

                try {
                    long sleepMs = Math.max(
                            1L,
                            Math.min(25L, remainingNanos / 1_000_000L)
                    );
                    Thread.sleep(sleepMs);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    android.util.Log.w(
                            TAG,
                            "Interrupted while waiting for active operations"
                    );
                    break;
                }
            }

            for (ObjectEntry entry : remainingObjects) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    android.util.Log.w(
                            TAG,
                            "Shutdown deadline reached while closing objects"
                    );
                    break;
                }

                long remainingMs = Math.max(1L, remainingNanos / 1_000_000L);
                Throwable closeError = entry.closeAndWait(remainingMs);
                if (closeError != null) {
                    android.util.Log.w(
                            TAG,
                            "Object close did not finish cleanly: "
                                    + closeError.getClass().getSimpleName()
                                    + ": " + closeError.getMessage()
                    );
                }
            }

            android.util.Log.i(
                    TAG,
                    "Root Core shutdown complete; exiting root process"
            );
            System.exit(0);
        }, "RootCoreShutdown");

        shutdownWorker.setDaemon(false);
        shutdownWorker.start();
    }
}

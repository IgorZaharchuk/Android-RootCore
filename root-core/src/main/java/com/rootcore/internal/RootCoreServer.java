package com.rootcore.internal;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.rootcore.RootCorePolicy;
import com.rootcore.ipc.IRootCoreManager;


public final class RootCoreServer extends IRootCoreManager.Stub {

    private static final String TAG = "RootCore";
    private static final long IDLE_TIMEOUT_MS = 15_000L;
    private static final long RELEASE_CLOSE_TIMEOUT_MS = 8_000L;
    private static final long SHUTDOWN_GRACE_PERIOD_MS = 2_000L;

    private final Context context;
    private final int clientUid;
    private final String sessionId;
    private final RootCorePolicy policy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ObjectRegistry objects = new ObjectRegistry();

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

        context.sendBroadcastAsUser(
                intent,
                UserHandle.getUserHandleForUid(clientUid)
        );

        android.util.Log.i(TAG, "Binder broadcast sent; root UID=" + Process.myUid());
    }

    @Override
    public int ping() {
        enforceClient();
        return Process.myUid();
    }

    @Override
    public synchronized void connect(IBinder client) throws RemoteException {
        enforceClient();

        if (closed) {
            throw new RemoteException("Root Core session is already closed");
        }

        if (client == null || !client.isBinderAlive()) {
            throw new RemoteException("Client token is not alive");
        }

        if (connected) {
            if (clientToken == client) return;
            throw new RemoteException("A client is already connected");
        }

        clientToken = client;
        deathRecipient = this::onClientDied;
        client.linkToDeath(deathRecipient, 0);
        connected = true;
        handler.removeCallbacks(idleShutdown);

        android.util.Log.i(TAG, "Client connected");
    }

    @Override
    public Bundle query(String uri, String[] projection) throws RemoteException {
        enforceConnectedClient();
        return ExternalProviderQuery.query(
                uri, projection, clientUid, policy
        );
    }

    @Override
    public void launchActivity(Intent intent) throws RemoteException {
        enforceConnectedClient();
        RootActivityLauncher.launch(intent, clientUid, policy);
    }

    @Override
    public Bundle execute(Bundle encodedRequest) throws RemoteException {
        String requestId = "unknown";
        String operation = "UNKNOWN";
        try {
            enforceClientCodePermission();

            com.rootcore.RootCoreRequest request =
                    RootCoreCodec.decodeRequest(encodedRequest);
            requestId = request.getRequestId();
            operation = request.getOperation();

            com.rootcore.RootCoreValue result =
                    RootCoreDispatcher.dispatch(this, request);

            return RootCoreCodec.encodeResponse(
                    com.rootcore.RootCoreResponse.success(requestId, result)
            );
        } catch (Throwable error) {
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
                throw asRemoteException("RootCore protocol response failed", encodingError);
            }
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
        if (error instanceof SecurityException) return "PERMISSION_DENIED";
        if (error instanceof NoSuchFieldException) return "FIELD_NOT_FOUND";
        if (error instanceof NoSuchMethodException) return "METHOD_NOT_FOUND";
        if (error instanceof ClassNotFoundException) return "INVALID_ARGUMENT";
        if (error instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (error instanceof IllegalStateException) return "OBJECT_CLOSED";
        if (error instanceof UnsupportedOperationException) return "TYPE_MISMATCH";
        return "EXECUTION_FAILED";
    }

    private void enforceClientCodePermission() {
        enforceConnectedClient();
        if (!policy.allowsClientCodeExecution()) {
            throw new SecurityException(
                    "Client code execution is not allowed by RootCorePolicy"
            );
        }
        if (Process.myUid() != 0) {
            throw new SecurityException("Client code execution requires UID 0");
        }
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
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }

    private com.rootcore.RootCoreValue typedCreate(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    List<ObjectEntry> leases = new ArrayList<>();
    try {
        String className = stringArg(args, "className");
        Class<?> type = loadClientType(className);
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
        if (!objects.register(id, entry)) {
            entry.requestClose();
            throw new IllegalStateException("RootCore session is closing");
        }

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
        if (method.getDeclaringClass() == Object.class) {
            throw new SecurityException("Object base methods are not callable");
        }
        if (!method.isAccessible()) method.setAccessible(true);

        Object result;
        try {
            result = entry.invoke(method, values);
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
    entry.acquireLease();
    try {
        String name = stringArg(args, "fieldName");
        java.lang.reflect.Field field = findField(entry.type(), name);

        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Use GET_STATIC_FIELD for static fields");
        }
        if (!field.isAccessible()) field.setAccessible(true);

        Object value = entry.readField(field);
        return toRootValue(value, field.getType());
    } finally {
        entry.releaseLease();
    }
}

    private com.rootcore.RootCoreValue typedSetField(
        Map<String, com.rootcore.RootCoreValue> args
) throws Throwable {
    ObjectEntry entry = requireObject(args.get("object"));
    entry.acquireLease();
    List<ObjectEntry> leases = new ArrayList<>();

    try {
        String name = stringArg(args, "fieldName");
        java.lang.reflect.Field field = findField(entry.type(), name);

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
        entry.writeField(field, value);
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
        java.lang.reflect.Field field = findField(type, stringArg(args, "fieldName"));
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
        java.lang.reflect.Field field = findField(type, stringArg(args, "fieldName"));

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

    private Class<?> loadClientType(String name) throws ClassNotFoundException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Class name is empty");
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
                    Class<?> component = loadClientType(name.substring(0, name.length() - 2));
                    return java.lang.reflect.Array.newInstance(component, 0).getClass();
                }
                return context.getClassLoader().loadClass(name);
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

            if (java.util.Collection.class.isAssignableFrom(target)
                    || target == Object.class) {
                ArrayList<Object> list = new ArrayList<>();
                for (com.rootcore.RootCoreValue item : items) {
                    list.add(fromRootValue(item, Object.class, leases));
                }
                return list;
            }

            throw new UnsupportedOperationException("Sequence cannot be converted to " + target);
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
            Map<String, com.rootcore.RootCoreValue> fields = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
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
        if (!objects.register(id, entry)) {
            entry.requestClose();
            throw new IllegalStateException("RootCore session is closing");
        }
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

    private static Class<?> resolveType(String name) throws ClassNotFoundException {
        if (name == null) throw new IllegalArgumentException("Type name is null");
        switch (name) {
            case "boolean": return boolean.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "java.lang.String": return String.class;
            case "android.os.Bundle": return Bundle.class;
            default:
                throw new IllegalArgumentException("Unsupported parameter type: " + name);
        }
    }

    private static Object readArgument(Bundle args, int index, Class<?> type) {
        String key = "arg" + index;
        if (!args.containsKey(key)) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        if (type == boolean.class) return args.getBoolean(key);
        if (type == int.class) return args.getInt(key);
        if (type == long.class) return args.getLong(key);
        if (type == float.class) return args.getFloat(key);
        if (type == double.class) return args.getDouble(key);
        if (type == String.class) return args.getString(key);
        if (type == Bundle.class) return args.getBundle(key);
        throw new IllegalArgumentException("Unsupported argument type: " + type);
    }

    private static Object[] decodeArguments(String[] names, Bundle args)
            throws ClassNotFoundException {
        if (names == null) names = new String[0];
        if (args == null) args = new Bundle();
        Object[] values = new Object[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = readArgument(args, i, resolveType(names[i]));
        }
        return values;
    }

    private static Bundle encodeResult(Object value, Class<?> returnType) {
        Bundle result = new Bundle();
        result.putString("return_type", returnType.getName());
        if (returnType == void.class) {
            result.putString("value_kind", "void");
        } else if (returnType == boolean.class || returnType == Boolean.class) {
            result.putString("value_kind", "boolean");
            result.putBoolean("value_boolean", (Boolean) value);
        } else if (returnType == int.class || returnType == Integer.class) {
            result.putString("value_kind", "int");
            result.putInt("value_int", (Integer) value);
        } else if (returnType == long.class || returnType == Long.class) {
            result.putString("value_kind", "long");
            result.putLong("value_long", (Long) value);
        } else if (returnType == float.class || returnType == Float.class) {
            result.putString("value_kind", "float");
            result.putFloat("value_float", (Float) value);
        } else if (returnType == double.class || returnType == Double.class) {
            result.putString("value_kind", "double");
            result.putDouble("value_double", (Double) value);
        } else if (returnType == String.class) {
            result.putString("value_kind", "string");
            result.putString("value_string", (String) value);
        } else if (returnType == Bundle.class) {
            result.putString("value_kind", "bundle");
            result.putBundle("value_bundle", (Bundle) value);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported return type: " + returnType.getName()
            );
        }
        return result;
    }

    @Override
    public String createObject(
            String className,
            String[] constructorTypes,
            Bundle arguments
    ) throws RemoteException {
        enforceClientCodePermission();

        Object instance = null;
        try {
            if (className == null || !className.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                throw new IllegalArgumentException("Invalid client class name");
            }

            Class<?> type = context.getClassLoader().loadClass(className);
            Class<?>[] parameterTypes = new Class<?>[
                    constructorTypes == null ? 0 : constructorTypes.length
            ];

            for (int i = 0; i < parameterTypes.length; i++) {
                parameterTypes[i] = resolveType(constructorTypes[i]);
            }

            Constructor<?> constructor = type.getConstructor(parameterTypes);
            Object[] values = decodeArguments(constructorTypes, arguments);
            instance = constructor.newInstance(values);

            String id = UUID.randomUUID().toString();
            ObjectEntry entry = new ObjectEntry(instance);

            if (!objects.register(id, entry)) {
                entry.requestClose();
                throw new IllegalStateException(
                        "Root Core session is closing; object was not registered"
                );
            }

            return id;
        } catch (Throwable error) {
            throw asRemoteException("Create client object failed", error);
        }
    }

    @Override
    public Bundle invokeObject(
            String objectId,
            String methodName,
            String[] parameterTypes,
            Bundle arguments
    ) throws RemoteException {
        enforceClientCodePermission();

        android.util.Log.i(TAG,
                "OBJECT_INVOKE_BEGIN session=" + sessionId.substring(0, 8)
                        + " pid=" + Process.myPid()
                        + " id=" + shortObjectId(objectId)
                        + " exists=" + objects.containsKey(objectId));

        try {
            ObjectEntry entry = objects.get(objectId);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown or released object ID");
            }

            if (methodName == null || !methodName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("Invalid method name");
            }

            Class<?>[] types = new Class<?>[
                    parameterTypes == null ? 0 : parameterTypes.length
            ];

            for (int i = 0; i < types.length; i++) {
                types[i] = resolveType(parameterTypes[i]);
            }

            Method method = entry.instance().getClass().getMethod(methodName, types);
            if (method.getDeclaringClass() == Object.class) {
                throw new SecurityException("Object base methods are not callable");
            }

            Object[] values = decodeArguments(parameterTypes, arguments);
            Object value = entry.invoke(method, values);

            Bundle result = encodeResult(value, method.getReturnType());
            result.putString("status", "success");

            android.util.Log.i(TAG,
                    "OBJECT_INVOKE_SUCCESS session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId));
            return result;
        } catch (Throwable error) {
            android.util.Log.e(TAG,
                    "OBJECT_INVOKE_ERROR session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId),
                    error);
            return operationErrorResult("Invoke client object failed", error);
        }
    }

    @Override
    public Bundle releaseObject(String objectId) throws RemoteException {
        enforceClientCodePermission();

        try {
            ObjectEntry removed = objects.remove(objectId);

            android.util.Log.i(TAG,
                    "OBJECT_RELEASE session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId)
                            + " removed=" + (removed != null)
                            + " size=" + objects.size());

            if (removed == null) {
                throw new IllegalArgumentException(
                        "Unknown or already released object ID"
                );
            }

            Throwable closeError = removed.closeAndWait(
                    RELEASE_CLOSE_TIMEOUT_MS
            );

            if (closeError != null) {
                throw new IllegalStateException(
                        "Object was released, but close failed",
                        closeError
                );
            }

            Bundle result = new Bundle();
            result.putString("status", "success");
            result.putBoolean("released", true);
            return result;
        } catch (Throwable error) {
            android.util.Log.e(TAG,
                    "OBJECT_RELEASE_ERROR session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId),
                    error);
            return operationErrorResult("Release client object failed", error);
        }
    }

    private static Bundle operationErrorResult(String operation, Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }

        Bundle result = new Bundle();
        result.putString("status", "error");
        result.putString("error_type", cause.getClass().getName());
        result.putString(
                "error_message",
                operation + ": " + cause.getClass().getSimpleName()
                        + ": " + String.valueOf(cause.getMessage())
        );
        return result;
    }

    private static String shortObjectId(String objectId) {
        if (objectId == null) return "null";
        return objectId.substring(0, Math.min(8, objectId.length()));
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

                if (!(instance instanceof AutoCloseable)) {
                    closeFinished = true;
                    notifyAll();
                    return;
                }

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

                ((AutoCloseable) instance).close();
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

        handler.postDelayed(
                () -> System.exit(0),
                SHUTDOWN_GRACE_PERIOD_MS
        );
    }
}

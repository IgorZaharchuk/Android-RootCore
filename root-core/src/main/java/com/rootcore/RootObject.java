package com.rootcore;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side handle for an object instance living in the RootCore process.
 *
 * This is a remote handle, not a local instance of T.
 */
public final class RootObject<T> implements AutoCloseable {
    private final RootCoreSession session;
    private final String sessionId;
    private final String objectId;
    private final String typeName;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RootObject(RootCoreSession session, RootCoreValue reference) {
        if (session == null || reference == null
                || reference.getKind() != RootCoreValue.Kind.OBJECT_REF) {
            throw new IllegalArgumentException("Invalid remote object reference");
        }

        Map<String, RootCoreValue> fields = reference.asFields();
        this.session = session;
        this.sessionId = session.getSessionId();
        this.objectId = requiredString(fields, "objectId");
        this.typeName = requiredString(fields, "typeName");

        String referenceSession = requiredString(fields, "sessionId");
        if (!sessionId.equals(referenceSession)) {
            throw new IllegalArgumentException(
                    "Object reference belongs to another session"
            );
        }
    }

    private static String requiredString(
            Map<String, RootCoreValue> fields, String name
    ) {
        RootCoreValue value = fields.get(name);
        if (value == null || value.getKind() != RootCoreValue.Kind.STRING) {
            throw new IllegalArgumentException(
                    "Malformed object reference: " + name
            );
        }
        return value.asString();
    }

    String sessionId() {
        return sessionId;
    }

    RootCoreValue referenceValue() {
        ensureOpen();
        return RootCoreValue.objectRef(objectId, sessionId, typeName);
    }

    private void ensureOpen() {
        if (isClosed()) {
            throw new IllegalStateException("RootObject or its session is closed");
        }
    }

    public String getTypeName() {
        return typeName;
    }

    public boolean isClosed() {
        return closed.get() || !session.isOpen();
    }

    /** Invokes a method and decodes its result into Java values/remote handles. */
    @SuppressWarnings("unchecked")
    public <R> R invoke(
            String methodName,
            Class<R> returnType,
            RootCoreArguments arguments
    ) throws android.os.RemoteException {
        ensureOpen();
        if (methodName == null || methodName.trim().isEmpty()) {
            throw new IllegalArgumentException("Method name is empty");
        }
        if (returnType == null) {
            throw new IllegalArgumentException("Return type is null");
        }

        RootCoreValue result = session.invokeObject(
                referenceValue(), methodName,
                arguments == null ? RootCoreArguments.empty() : arguments
        );
        Object decoded = normalizeCharacter(
                session.decodeApiValue(result), returnType
        );
        if (decoded == null) return null;
        if (returnType == Object.class) return (R) decoded;
        if (!box(returnType).isInstance(decoded)) {
            throw new RootCoreException(new RootCoreResponse.Error(
                    "TYPE_MISMATCH",
                    IllegalStateException.class.getName(),
                    "Remote result is " + decoded.getClass().getName()
                            + ", not " + returnType.getName(),
                    "INVOKE_METHOD",
                    null
            ));
        }
        return (R) decoded;
    }

    /** Invokes a method whose result must be another root-side object. */
    public <R> RootObject<R> invokeObject(
            String methodName,
            RootCoreArguments arguments
    ) throws android.os.RemoteException {
        ensureOpen();
        RootCoreValue result = session.invokeObject(
                referenceValue(), methodName,
                arguments == null ? RootCoreArguments.empty() : arguments
        );
        if (result.getKind() == RootCoreValue.Kind.NULL
                || result.getKind() == RootCoreValue.Kind.VOID) {
            return null;
        }
        if (result.getKind() != RootCoreValue.Kind.OBJECT_REF) {
            throw new IllegalStateException(
                    "Method did not return a remote object: " + methodName
            );
        }
        return new RootObject<>(session, result);
    }

    @SuppressWarnings("unchecked")
    public <R> R getField(
            String fieldName, Class<R> fieldType
    ) throws android.os.RemoteException {
        ensureOpen();
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name is empty");
        }
        if (fieldType == null) {
            throw new IllegalArgumentException("Field type is null");
        }

        Object decoded = normalizeCharacter(
                session.decodeApiValue(
                        session.getObjectField(referenceValue(), fieldName)
                ),
                fieldType
        );
        if (decoded == null) return null;
        if (fieldType == Object.class) return (R) decoded;
        if (!box(fieldType).isInstance(decoded)) {
            throw new IllegalStateException(
                    "Field value is not of requested type " + fieldType.getName()
            );
        }
        return (R) decoded;
    }

    public <R> RootObject<R> getObjectField(
            String fieldName
    ) throws android.os.RemoteException {
        ensureOpen();
        RootCoreValue result =
                session.getObjectField(referenceValue(), fieldName);
        if (result.getKind() == RootCoreValue.Kind.NULL
                || result.getKind() == RootCoreValue.Kind.VOID) {
            return null;
        }
        if (result.getKind() != RootCoreValue.Kind.OBJECT_REF) {
            throw new IllegalStateException(
                    "Field is not a remote object: " + fieldName
            );
        }
        return new RootObject<>(session, result);
    }

    public void setField(
            String fieldName, Object value
    ) throws android.os.RemoteException {
        ensureOpen();
        session.setObjectField(referenceValue(), fieldName, value);
    }

    @Override
    public void close() throws android.os.RemoteException {
        if (closed.compareAndSet(false, true)) {
            session.releaseObject(
                    RootCoreValue.objectRef(objectId, sessionId, typeName)
            );
        }
    }

    private static Object normalizeCharacter(
            Object value, Class<?> expectedType
    ) {
        if ((expectedType == char.class || expectedType == Character.class)
                && value instanceof String) {
            String text = (String) value;
            if (text.length() == 1) {
                return text.charAt(0);
            }
        }
        return value;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}

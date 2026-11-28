package com.rootcore;

/**
 * Client-side handle for static members of a client application class
 * loaded in the RootCore process.
 *
 * This is a remote class handle, not a java.lang.Class instance
 * from the root process.
 */
public final class RootClass<T> {
    private final RootCoreSession session;
    private final Class<T> type;
    private final String className;

    RootClass(RootCoreSession session, Class<T> type) {
        if (session == null || type == null) {
            throw new IllegalArgumentException("Session or class is null");
        }
        this.session = session;
        this.type = type;
        this.className = RootCoreArguments.typeName(type);
    }

    public String getTypeName() {
        return className;
    }

    private void ensureOpen() {
        if (!session.isOpen()) {
            throw new IllegalStateException("RootCore session is closed");
        }
    }

    @SuppressWarnings("unchecked")
    public <R> R getStaticField(
            String fieldName, Class<R> fieldType
    ) throws android.os.RemoteException {
        ensureOpen();
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name is empty");
        }
        if (fieldType == null) {
            throw new IllegalArgumentException("Field type is null");
        }

        Object value = normalizeCharacter(
                session.decodeApiValue(
                        session.getStaticField(className, fieldName)
                ),
                fieldType
        );

        if (value == null) return null;
        if (fieldType == Object.class) return (R) value;
        if (!box(fieldType).isInstance(value)) {
            throw new RootCoreException(new RootCoreResponse.Error(
                    "TYPE_MISMATCH",
                    IllegalStateException.class.getName(),
                    "Static field value is " + value.getClass().getName()
                            + ", not " + fieldType.getName(),
                    "GET_STATIC_FIELD",
                    null
            ));
        }
        return (R) value;
    }

    public <R> RootObject<R> getStaticObjectField(
            String fieldName
    ) throws android.os.RemoteException {
        ensureOpen();
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name is empty");
        }

        RootCoreValue result = session.getStaticField(className, fieldName);
        if (result.getKind() == RootCoreValue.Kind.NULL
                || result.getKind() == RootCoreValue.Kind.VOID) {
            return null;
        }
        if (result.getKind() != RootCoreValue.Kind.OBJECT_REF) {
            throw new IllegalStateException(
                    "Static field is not a remote object: " + fieldName
            );
        }
        return new RootObject<>(session, result);
    }

    public void setStaticField(
            String fieldName, Object value
    ) throws android.os.RemoteException {
        ensureOpen();
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name is empty");
        }
        session.setStaticField(className, fieldName, value);
    }

    @SuppressWarnings("unchecked")
    public <R> R invokeStatic(
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

        RootCoreValue result = session.invokeStatic(
                className,
                methodName,
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
                    "INVOKE_STATIC_METHOD",
                    null
            ));
        }
        return (R) decoded;
    }

    public <R> RootObject<R> invokeStaticObject(
            String methodName,
            RootCoreArguments arguments
    ) throws android.os.RemoteException {
        ensureOpen();
        if (methodName == null || methodName.trim().isEmpty()) {
            throw new IllegalArgumentException("Method name is empty");
        }

        RootCoreValue result = session.invokeStatic(
                className,
                methodName,
                arguments == null ? RootCoreArguments.empty() : arguments
        );

        if (result.getKind() == RootCoreValue.Kind.NULL
                || result.getKind() == RootCoreValue.Kind.VOID) {
            return null;
        }
        if (result.getKind() != RootCoreValue.Kind.OBJECT_REF) {
            throw new IllegalStateException(
                    "Static method did not return a remote object: "
                            + methodName
            );
        }
        return new RootObject<>(session, result);
    }

    private static Object normalizeCharacter(
            Object value, Class<?> expectedType
    ) {
        if ((expectedType == char.class || expectedType == Character.class)
                && value instanceof String) {
            String text = (String) value;
            if (text.length() == 1) return text.charAt(0);
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

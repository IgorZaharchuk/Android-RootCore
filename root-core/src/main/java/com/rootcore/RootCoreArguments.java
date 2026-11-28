package com.rootcore;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicitly typed arguments for a remote constructor or method.
 *
 * Parameter types are explicit so that overloaded methods and typed null
 * arguments can be resolved unambiguously on the root side.
 */
public final class RootCoreArguments {
    private final List<Class<?>> parameterTypes;
    private final List<Object> values;

    private RootCoreArguments(List<Class<?>> types, List<Object> values) {
        this.parameterTypes = new ArrayList<>(types);
        this.values = new ArrayList<>(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RootCoreArguments empty() {
        return builder().build();
    }

    List<Class<?>> parameterTypes() {
        return new ArrayList<>(parameterTypes);
    }

    List<RootCoreValue> encodedValues(String sessionId) {
        List<RootCoreValue> result = new ArrayList<>();
        for (Object value : values) {
            result.add(encode(value, sessionId));
        }
        return result;
    }

    static String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }

    static RootCoreValue encode(Object value, String sessionId) {
        if (value == null) return RootCoreValue.nullValue();

        if (value instanceof RootObject<?>) {
            RootObject<?> object = (RootObject<?>) value;
            if (!sessionId.equals(object.sessionId())) {
                throw new IllegalArgumentException(
                        "RootObject belongs to another session"
                );
            }
            return object.referenceValue();
        }

        if (value instanceof Boolean) return RootCoreValue.of((Boolean) value);
        if (value instanceof Byte) return RootCoreValue.of((Byte) value);
        if (value instanceof Short) return RootCoreValue.of((Short) value);
        if (value instanceof Integer) return RootCoreValue.of((Integer) value);
        if (value instanceof Long) return RootCoreValue.of((Long) value);
        if (value instanceof Float) return RootCoreValue.of((Float) value);
        if (value instanceof Double) return RootCoreValue.of((Double) value);
        if (value instanceof Character) {
            return RootCoreValue.of(String.valueOf(value));
        }
        if (value instanceof String) return RootCoreValue.of((String) value);
        if (value instanceof byte[]) return RootCoreValue.of((byte[]) value);

        if (value.getClass().isArray()) {
            List<RootCoreValue> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                items.add(encode(Array.get(value, i), sessionId));
            }
            return RootCoreValue.array(items);
        }

        if (value instanceof Iterable<?>) {
            List<RootCoreValue> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                items.add(encode(item, sessionId));
            }
            return RootCoreValue.list(items);
        }

        if (value instanceof Map<?, ?>) {
            Map<String, RootCoreValue> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            "RootCore map keys must be strings"
                    );
                }
                fields.put((String) entry.getKey(),
                        encode(entry.getValue(), sessionId));
            }
            return RootCoreValue.map(fields);
        }

        throw new IllegalArgumentException(
                "Unsupported RootCore argument type: "
                        + value.getClass().getName()
                        + ". Pass a RootObject for root-side instances."
        );
    }

    public static final class Builder {
        private final List<Class<?>> types = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();

        public Builder add(Class<?> parameterType, Object value) {
            if (parameterType == null) {
                throw new IllegalArgumentException("Parameter type is null");
            }
            types.add(parameterType);
            values.add(value);
            return this;
        }

        public RootCoreArguments build() {
            return new RootCoreArguments(types, values);
        }
    }
}

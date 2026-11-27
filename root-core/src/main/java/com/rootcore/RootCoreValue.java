package com.rootcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable typed value used by RootCore Protocol v1. */
public final class RootCoreValue {
    public enum Kind {
        NULL(0), VOID(1), BOOLEAN(2), BYTE(3), SHORT(4), INT(5),
        LONG(6), FLOAT(7), DOUBLE(8), STRING(9), BYTE_ARRAY(10),
        ARRAY(11), LIST(12), MAP(13), STRUCT(14), OBJECT_REF(15),
        EXTENSION(16);

        private final int code;
        Kind(int code) { this.code = code; }
        public int getCode() { return code; }

        public static Kind fromCode(int code) {
            for (Kind kind : values()) {
                if (kind.code == code) return kind;
            }
            throw new IllegalArgumentException("Unknown RootCoreValue kind: " + code);
        }
    }

    private final Kind kind;
    private final Object value;

    private RootCoreValue(Kind kind, Object value) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.value = value;
    }

    private static RootCoreValue scalar(Kind kind, Object value) {
        return new RootCoreValue(kind, value);
    }

    public static RootCoreValue nullValue() { return scalar(Kind.NULL, null); }
    public static RootCoreValue voidValue() { return scalar(Kind.VOID, null); }
    public static RootCoreValue of(boolean v) { return scalar(Kind.BOOLEAN, v); }
    public static RootCoreValue of(byte v) { return scalar(Kind.BYTE, v); }
    public static RootCoreValue of(short v) { return scalar(Kind.SHORT, v); }
    public static RootCoreValue of(int v) { return scalar(Kind.INT, v); }
    public static RootCoreValue of(long v) { return scalar(Kind.LONG, v); }
    public static RootCoreValue of(float v) { return scalar(Kind.FLOAT, v); }
    public static RootCoreValue of(double v) { return scalar(Kind.DOUBLE, v); }

    public static RootCoreValue of(String v) {
        return v == null ? nullValue() : scalar(Kind.STRING, v);
    }

    public static RootCoreValue of(byte[] v) {
        return v == null ? nullValue() : scalar(Kind.BYTE_ARRAY, v.clone());
    }

    public static RootCoreValue array(List<RootCoreValue> values) {
        return sequence(Kind.ARRAY, values);
    }

    public static RootCoreValue list(List<RootCoreValue> values) {
        return sequence(Kind.LIST, values);
    }

    private static RootCoreValue sequence(Kind kind, List<RootCoreValue> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<RootCoreValue> copy = new ArrayList<>(values);
        if (copy.contains(null)) throw new IllegalArgumentException("Null item");
        return scalar(kind, Collections.unmodifiableList(copy));
    }

    public static RootCoreValue map(Map<String, RootCoreValue> values) {
        return fields(Kind.MAP, values);
    }

    public static RootCoreValue struct(Map<String, RootCoreValue> values) {
        return fields(Kind.STRUCT, values);
    }

    private static RootCoreValue fields(Kind kind, Map<String, RootCoreValue> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, RootCoreValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, RootCoreValue> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "field name");
            RootCoreValue item = Objects.requireNonNull(entry.getValue(), "field value");
            if (copy.put(key, item) != null) {
                throw new IllegalArgumentException("Duplicate field: " + key);
            }
        }
        return scalar(kind, Collections.unmodifiableMap(copy));
    }

    public static RootCoreValue objectRef(String objectId, String sessionId, String typeName) {
        LinkedHashMap<String, RootCoreValue> ref = new LinkedHashMap<>();
        ref.put("objectId", of(Objects.requireNonNull(objectId, "objectId")));
        ref.put("sessionId", of(Objects.requireNonNull(sessionId, "sessionId")));
        ref.put("typeName", of(Objects.requireNonNull(typeName, "typeName")));
        return scalar(Kind.OBJECT_REF, Collections.unmodifiableMap(ref));
    }

    public static RootCoreValue extension(String typeId, int version, RootCoreValue payload) {
        if (version < 1) throw new IllegalArgumentException("Invalid extension version");
        LinkedHashMap<String, RootCoreValue> ext = new LinkedHashMap<>();
        ext.put("typeId", of(Objects.requireNonNull(typeId, "typeId")));
        ext.put("version", of(version));
        ext.put("payload", Objects.requireNonNull(payload, "payload"));
        return scalar(Kind.EXTENSION, Collections.unmodifiableMap(ext));
    }

    public Kind getKind() { return kind; }

    public boolean asBoolean() { return (Boolean) value; }
    public byte asByte() { return (Byte) value; }
    public short asShort() { return (Short) value; }
    public int asInt() { return (Integer) value; }
    public long asLong() { return (Long) value; }
    public float asFloat() { return (Float) value; }
    public double asDouble() { return (Double) value; }
    public String asString() { return (String) value; }
    public byte[] asByteArray() { return ((byte[]) value).clone(); }

    @SuppressWarnings("unchecked")
    public List<RootCoreValue> asSequence() {
        return (List<RootCoreValue>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, RootCoreValue> asFields() {
        return (Map<String, RootCoreValue>) value;
    }
}

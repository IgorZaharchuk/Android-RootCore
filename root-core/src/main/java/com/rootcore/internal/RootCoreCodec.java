package com.rootcore.internal;

import android.os.Bundle;
import android.os.Parcel;

import com.rootcore.RootCoreRequest;
import com.rootcore.RootCoreResponse;
import com.rootcore.RootCoreValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bundle-based wire codec for RootCore Protocol v1. */
public final class RootCoreCodec {
    public static final int PROTOCOL_VERSION = 1;

    public static final int MAX_REQUEST_BYTES = 256 * 1024;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    public static final int MAX_BYTE_ARRAY_BYTES = 192 * 1024;
    public static final int MAX_STRING_BYTES = 64 * 1024;
    public static final int MAX_DEPTH = 16;
    public static final int MAX_COLLECTION_ITEMS = 1024;
    public static final int MAX_TOTAL_VALUES = 4096;

    private RootCoreCodec() {}

    public static Bundle encodeRequest(RootCoreRequest request) {
        Budget budget = new Budget();
        Bundle out = new Bundle();
        out.putInt("version", request.getProtocolVersion());
        out.putString("requestId", checkedString(request.getRequestId()));
        out.putString("operation", checkedString(request.getOperation()));
        out.putBundle("arguments", encodeFields(request.getArguments(), 0, budget));
        checkSize(out, MAX_REQUEST_BYTES);
        return out;
    }

    public static RootCoreRequest decodeRequest(Bundle input) {
        Bundle in = checkedBundle(input, MAX_REQUEST_BYTES);
        int version = in.getInt("version", -1);
        requireVersion(version);
        String requestId = requiredString(in, "requestId");
        String operation = requiredString(in, "operation");
        Bundle args = requiredBundle(in, "arguments");
        Map<String, RootCoreValue> values = decodeFields(args, 0, new Budget());
        return new RootCoreRequest(version, requestId, operation, values);
    }

    public static Bundle encodeResponse(RootCoreResponse response) {
        Budget budget = new Budget();
        Bundle out = new Bundle();
        out.putInt("version", PROTOCOL_VERSION);
        out.putString("requestId", checkedString(response.getRequestId()));
        out.putBoolean("success", response.isSuccess());

        if (response.isSuccess()) {
            out.putBundle("result", encodeValue(response.getResult(), 0, budget));
        } else {
            RootCoreResponse.Error error = response.getError();
            Bundle err = new Bundle();
            err.putString("code", checkedString(error.getCode()));
            err.putString("type", checkedString(error.getType()));
            err.putString("message", checkedString(error.getMessage()));
            err.putString("operation", checkedString(error.getOperation()));
            err.putBundle("details", encodeFields(error.getDetails(), 0, budget));
            out.putBundle("error", err);
        }
        checkSize(out, MAX_RESPONSE_BYTES);
        return out;
    }

    public static RootCoreResponse decodeResponse(Bundle input) {
        Bundle in = checkedBundle(input, MAX_RESPONSE_BYTES);
        requireVersion(in.getInt("version", -1));
        String requestId = requiredString(in, "requestId");
        if (!in.containsKey("success")) throw new IllegalArgumentException("Missing success");
        boolean success = in.getBoolean("success");

        if (success) {
            return RootCoreResponse.success(requestId,
                    decodeValue(requiredBundle(in, "result"), 0, new Budget()));
        }

        Bundle err = requiredBundle(in, "error");
        String code = requiredString(err, "code");
        String type = requiredString(err, "type");
        String message = requiredString(err, "message");
        String operation = requiredString(err, "operation");
        Map<String, RootCoreValue> details =
                decodeFields(requiredBundle(err, "details"), 0, new Budget());

        return RootCoreResponse.failure(requestId,
                new RootCoreResponse.Error(code, type, message, operation, details));
    }

    public static Bundle encodeValue(RootCoreValue value) {
        Bundle out = encodeValue(value, 0, new Budget());
        checkSize(out, MAX_REQUEST_BYTES);
        return out;
    }

    public static RootCoreValue decodeValue(Bundle value) {
        Bundle in = checkedBundle(value, MAX_REQUEST_BYTES);
        return decodeValue(in, 0, new Budget());
    }

    private static Bundle encodeValue(RootCoreValue value, int depth, Budget budget) {
        checkDepth(depth);
        budget.add();
        Bundle out = new Bundle();
        out.putInt("kind", value.getKind().getCode());

        switch (value.getKind()) {
            case NULL:
            case VOID:
                break;
            case BOOLEAN:
                out.putBoolean("v", value.asBoolean());
                break;
            case BYTE:
                out.putByte("v", value.asByte());
                break;
            case SHORT:
                out.putShort("v", value.asShort());
                break;
            case INT:
                out.putInt("v", value.asInt());
                break;
            case LONG:
                out.putLong("v", value.asLong());
                break;
            case FLOAT:
                out.putFloat("v", value.asFloat());
                break;
            case DOUBLE:
                out.putDouble("v", value.asDouble());
                break;
            case STRING:
                out.putString("v", checkedString(value.asString()));
                break;
            case BYTE_ARRAY: {
                byte[] bytes = value.asByteArray();
                if (bytes.length > MAX_BYTE_ARRAY_BYTES) limit("Byte array too large");
                out.putByteArray("v", bytes);
                break;
            }
            case ARRAY:
            case LIST: {
                List<RootCoreValue> items = value.asSequence();
                checkCollection(items.size());
                ArrayList<Bundle> encoded = new ArrayList<>(items.size());
                for (RootCoreValue item : items) {
                    encoded.add(encodeValue(item, depth + 1, budget));
                }
                out.putParcelableArrayList("items", encoded);
                break;
            }
            case MAP:
            case STRUCT:
            case OBJECT_REF:
            case EXTENSION:
                out.putBundle("fields", encodeFields(value.asFields(), depth + 1, budget));
                break;
            default:
                throw new IllegalArgumentException("Unsupported value kind");
        }
        return out;
    }

    private static RootCoreValue decodeValue(Bundle input, int depth, Budget budget) {
        checkDepth(depth);
        budget.add();
        Bundle in = safe(input);
        if (!in.containsKey("kind")) throw new IllegalArgumentException("Missing value kind");

        RootCoreValue.Kind kind = RootCoreValue.Kind.fromCode(in.getInt("kind"));
        switch (kind) {
            case NULL:
                return RootCoreValue.nullValue();
            case VOID:
                return RootCoreValue.voidValue();
            case BOOLEAN:
                requireKey(in, "v");
                return RootCoreValue.of(in.getBoolean("v"));
            case BYTE:
                requireKey(in, "v");
                return RootCoreValue.of(in.getByte("v"));
            case SHORT:
                requireKey(in, "v");
                return RootCoreValue.of(in.getShort("v"));
            case INT:
                requireKey(in, "v");
                return RootCoreValue.of(in.getInt("v"));
            case LONG:
                requireKey(in, "v");
                return RootCoreValue.of(in.getLong("v"));
            case FLOAT:
                requireKey(in, "v");
                return RootCoreValue.of(in.getFloat("v"));
            case DOUBLE:
                requireKey(in, "v");
                return RootCoreValue.of(in.getDouble("v"));
            case STRING:
                return RootCoreValue.of(requiredString(in, "v"));
            case BYTE_ARRAY: {
                if (!in.containsKey("v")) throw new IllegalArgumentException("Missing byte array");
                byte[] bytes = in.getByteArray("v");
                if (bytes == null || bytes.length > MAX_BYTE_ARRAY_BYTES) {
                    limit("Invalid byte array size");
                }
                return RootCoreValue.of(bytes);
            }
            case ARRAY:
            case LIST: {
                ArrayList<Bundle> raw = in.getParcelableArrayList("items");
                if (raw == null) throw new IllegalArgumentException("Missing sequence");
                checkCollection(raw.size());
                ArrayList<RootCoreValue> items = new ArrayList<>(raw.size());
                for (Bundle item : raw) {
                    items.add(decodeValue(requiredChild(item), depth + 1, budget));
                }
                return kind == RootCoreValue.Kind.ARRAY
                        ? RootCoreValue.array(items) : RootCoreValue.list(items);
            }
            case MAP:
            case STRUCT:
            case OBJECT_REF:
            case EXTENSION: {
                Map<String, RootCoreValue> fields =
                        decodeFields(requiredBundle(in, "fields"), depth + 1, budget);
                if (kind == RootCoreValue.Kind.MAP) return RootCoreValue.map(fields);
                if (kind == RootCoreValue.Kind.STRUCT) return RootCoreValue.struct(fields);
                if (kind == RootCoreValue.Kind.OBJECT_REF) {
                    return RootCoreValue.objectRef(
                            requiredFieldString(fields, "objectId"),
                            requiredFieldString(fields, "sessionId"),
                            requiredFieldString(fields, "typeName"));
                }
                return RootCoreValue.extension(
                        requiredFieldString(fields, "typeId"),
                        requiredFieldInt(fields, "version"),
                        requiredField(fields, "payload"));
            }
            default:
                throw new IllegalArgumentException("Unsupported value kind");
        }
    }

    private static Bundle encodeFields(Map<String, RootCoreValue> fields,
                                        int depth, Budget budget) {
        checkDepth(depth);
        checkCollection(fields.size());

        ArrayList<String> keys = new ArrayList<>(fields.size());
        ArrayList<Bundle> values = new ArrayList<>(fields.size());

        for (Map.Entry<String, RootCoreValue> entry : fields.entrySet()) {
            keys.add(checkedString(entry.getKey()));
            values.add(encodeValue(entry.getValue(), depth + 1, budget));
        }

        Bundle out = new Bundle();
        out.putStringArrayList("keys", keys);
        out.putParcelableArrayList("values", values);
        return out;
    }

    private static Map<String, RootCoreValue> decodeFields(Bundle input,
                                                           int depth, Budget budget) {
        checkDepth(depth);
        Bundle in = safe(input);
        ArrayList<String> keys = in.getStringArrayList("keys");
        ArrayList<Bundle> values = in.getParcelableArrayList("values");
        if (keys == null || values == null || keys.size() != values.size()) {
            throw new IllegalArgumentException("Invalid fields structure");
        }
        checkCollection(keys.size());

        LinkedHashMap<String, RootCoreValue> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = checkedString(keys.get(i));
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate field: " + key);
            }
            result.put(key, decodeValue(requiredChild(values.get(i)), depth + 1, budget));
        }
        return result;
    }

    private static Bundle requiredChild(Bundle value) {
        if (value == null) throw new IllegalArgumentException("Null child value");
        return value;
    }

    private static RootCoreValue requiredField(Map<String, RootCoreValue> fields, String key) {
        RootCoreValue value = fields.get(key);
        if (value == null) throw new IllegalArgumentException("Missing field: " + key);
        return value;
    }

    private static String requiredFieldString(Map<String, RootCoreValue> fields, String key) {
        RootCoreValue value = requiredField(fields, key);
        if (value.getKind() != RootCoreValue.Kind.STRING) {
            throw new IllegalArgumentException("Invalid field type: " + key);
        }
        return value.asString();
    }

    private static int requiredFieldInt(Map<String, RootCoreValue> fields, String key) {
        RootCoreValue value = requiredField(fields, key);
        if (value.getKind() != RootCoreValue.Kind.INT) {
            throw new IllegalArgumentException("Invalid field type: " + key);
        }
        return value.asInt();
    }

    private static Bundle requiredBundle(Bundle bundle, String key) {
        Bundle value = bundle.getBundle(key);
        if (value == null) throw new IllegalArgumentException("Missing bundle: " + key);
        return safe(value);
    }

    private static String requiredString(Bundle bundle, String key) {
        if (!bundle.containsKey(key)) throw new IllegalArgumentException("Missing string: " + key);
        Object value = bundle.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid string: " + key);
        return checkedString((String) value);
    }

    private static void requireKey(Bundle bundle, String key) {
        if (!bundle.containsKey(key)) throw new IllegalArgumentException("Missing value: " + key);
    }

    private static Bundle checkedBundle(Bundle input, int maxBytes) {
        if (input == null) throw new IllegalArgumentException("Bundle is null");
        Bundle copy = safe(input);
        checkSize(copy, maxBytes);
        return copy;
    }

    private static Bundle safe(Bundle bundle) {
        bundle.setClassLoader(RootCoreCodec.class.getClassLoader());
        return bundle;
    }

    private static String checkedString(String value) {
        if (value == null) throw new IllegalArgumentException("String is null");
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            limit("String too large");
        }
        return value;
    }

    private static void checkSize(Bundle bundle, int maxBytes) {
        Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            if (parcel.dataSize() > maxBytes) limit("Bundle exceeds size limit");
        } finally {
            parcel.recycle();
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) limit("Maximum nesting depth exceeded");
    }

    private static void checkCollection(int size) {
        if (size > MAX_COLLECTION_ITEMS) limit("Collection exceeds item limit");
    }

    private static void requireVersion(int version) {
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }
    }

    private static void limit(String message) {
        throw new IllegalArgumentException(message);
    }

    private static final class Budget {
        private int count;
        void add() {
            count++;
            if (count > MAX_TOTAL_VALUES) limit("Maximum total value count exceeded");
        }
    }
}

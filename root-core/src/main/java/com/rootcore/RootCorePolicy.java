package com.rootcore;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RootCorePolicy {

    public enum ExtraType {
        STRING,
        BOOLEAN,
        INTEGER,
        LONG,
        FLOAT
    }

    private static final class ActivityRule {
        final String component;
        final String action;
        final int flags;
        final Map<String, ExtraType> extras;

        ActivityRule(
                String component,
                String action,
                int flags,
                Map<String, ExtraType> extras
        ) {
            this.component = component;
            this.action = action;
            this.flags = flags;
            this.extras = Collections.unmodifiableMap(
                    new LinkedHashMap<>(extras)
            );
        }
    }

    private final Set<String> providerAuthorities;
    private final Map<String, ActivityRule> activityRules;
    private final Set<String> allowedOperations;
    private final Set<String> allowedExtensions;

    private RootCorePolicy(
            Set<String> providerAuthorities,
            Map<String, ActivityRule> activityRules,
            Set<String> allowedOperations,
            Set<String> allowedExtensions
    ) {
        this.providerAuthorities = Collections.unmodifiableSet(
                new LinkedHashSet<>(providerAuthorities)
        );
        this.activityRules = Collections.unmodifiableMap(
                new LinkedHashMap<>(activityRules)
        );
        this.allowedOperations = Collections.unmodifiableSet(
                new LinkedHashSet<>(allowedOperations)
        );
        this.allowedExtensions = Collections.unmodifiableSet(
                new LinkedHashSet<>(allowedExtensions)
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RootCorePolicy denyAll() {
        return builder().build();
    }

    public boolean allowsOperation(String operation) {
        return operation != null && allowedOperations.contains(operation);
    }

    public boolean allowsExtension(String typeId, int version) {
        return typeId != null
                && allowedExtensions.contains(typeId + ":" + version);
    }

    public boolean allowsProviderAuthority(String authority) {
        return authority != null && providerAuthorities.contains(authority);
    }

    public boolean allowsActivity(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return false;
        }

        String component = intent.getComponent().flattenToString();
        ActivityRule rule = activityRules.get(component);

        if (rule == null
                || !equalNullable(rule.action, intent.getAction())
                || rule.flags != intent.getFlags()) {
            return false;
        }

        Bundle actualExtras = intent.getExtras();

        if (actualExtras == null) {
            return rule.extras.isEmpty();
        }

        Set<String> actualKeys = actualExtras.keySet();

        if (actualKeys.size() != rule.extras.size()
                || !rule.extras.keySet().equals(actualKeys)) {
            return false;
        }

        for (Map.Entry<String, ExtraType> entry : rule.extras.entrySet()) {
            Object value = actualExtras.get(entry.getKey());

            if (!matchesType(value, entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    private static boolean equalNullable(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean matchesType(Object value, ExtraType type) {
        if (value == null || type == null) return false;

        switch (type) {
            case STRING:
                return value.getClass() == String.class;
            case BOOLEAN:
                return value.getClass() == Boolean.class;
            case INTEGER:
                return value.getClass() == Integer.class;
            case LONG:
                return value.getClass() == Long.class;
            case FLOAT:
                return value.getClass() == Float.class;
            default:
                return false;
        }
    }

    String encode() {
        try {
            JSONObject json = new JSONObject();
            JSONArray providers = new JSONArray();
            JSONArray activities = new JSONArray();
            JSONArray operations = new JSONArray();
            JSONArray extensions = new JSONArray();

            for (String operation : allowedOperations) {
                operations.put(operation);
            }
            for (String extension : allowedExtensions) {
                extensions.put(extension);
            }

            for (String authority : providerAuthorities) {
                providers.put(authority);
            }

            for (ActivityRule rule : activityRules.values()) {
                JSONObject activity = new JSONObject();
                JSONObject extras = new JSONObject();

                activity.put("component", rule.component);
                activity.put(
                        "action",
                        rule.action == null ? JSONObject.NULL : rule.action
                );
                activity.put("flags", rule.flags);

                for (Map.Entry<String, ExtraType> entry : rule.extras.entrySet()) {
                    extras.put(entry.getKey(), entry.getValue().name());
                }

                activity.put("extras", extras);
                activities.put(activity);
            }

            json.put("providers", providers);
            json.put("activities", activities);
            json.put("operations", operations);
            json.put("extensions", extensions);

            return Base64.encodeToString(
                    json.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP
            );
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Cannot encode RootCore policy", error
            );
        }
    }

    public static RootCorePolicy decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("RootCore policy is empty");
        }

        try {
            String source = new String(
                    Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP),
                    StandardCharsets.UTF_8
            );

            JSONObject json = new JSONObject(source);
            JSONArray providers = json.getJSONArray("providers");
            JSONArray activities = json.getJSONArray("activities");

            Builder builder = builder();
            // Older policy payloads have no operation/extension grants.
            // Missing fields therefore preserve deny-by-default behavior.
            JSONArray operations = json.optJSONArray("operations");
            if (operations != null) {
                for (int i = 0; i < operations.length(); i++) {
                    builder.allowOperation(operations.getString(i));
                }
            }

            JSONArray extensions = json.optJSONArray("extensions");
            if (extensions != null) {
                for (int i = 0; i < extensions.length(); i++) {
                    String rule = extensions.getString(i);
                    int split = rule.lastIndexOf(':');
                    if (split <= 0) {
                        throw new IllegalArgumentException(
                                "Invalid extension policy rule"
                        );
                    }
                    builder.allowExtension(
                            rule.substring(0, split),
                            Integer.parseInt(rule.substring(split + 1))
                    );
                }
            }

            for (int i = 0; i < providers.length(); i++) {
                builder.allowProviderAuthority(providers.getString(i));
            }

            for (int i = 0; i < activities.length(); i++) {
                JSONObject activity = activities.getJSONObject(i);

                String componentValue = activity.getString("component");
                ComponentName component =
                        ComponentName.unflattenFromString(componentValue);

                if (component == null) {
                    throw new IllegalArgumentException(
                            "Invalid Activity component: " + componentValue
                    );
                }

                Object actionValue = activity.get("action");
                String action = actionValue == JSONObject.NULL
                        ? null
                        : (String) actionValue;

                int flags = activity.getInt("flags");
                JSONObject extrasJson = activity.getJSONObject("extras");
                Map<String, ExtraType> extras = new LinkedHashMap<>();

                java.util.Iterator<String> keys = extrasJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    extras.put(
                            key,
                            ExtraType.valueOf(extrasJson.getString(key))
                    );
                }

                builder.allowActivity(component, action, flags, extras);
            }

            return builder.build();
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "Invalid RootCore policy", error
            );
        }
    }

    public static final class Builder {
        private final Set<String> providers = new LinkedHashSet<>();
        private final Map<String, ActivityRule> activities =
                new LinkedHashMap<>();
        private final Set<String> operations = new LinkedHashSet<>();
        private final Set<String> extensions = new LinkedHashSet<>();

        public Builder allowOperation(String operation) {
            if (operation == null || !operation.matches("[A-Z_]+")) {
                throw new IllegalArgumentException("Invalid operation");
            }
            operations.add(operation);
            return this;
        }

        public Builder allowExtension(String typeId, int version) {
            if (typeId == null || !typeId.matches("[A-Z_]+") || version < 1) {
                throw new IllegalArgumentException("Invalid extension rule");
            }
            extensions.add(typeId + ":" + version);
            return this;
        }

        public Builder allowCreateObject() {
            return allowOperation("CREATE_OBJECT");
        }

        public Builder allowInvokeMethod() {
            return allowOperation("INVOKE_METHOD");
        }

        public Builder allowReleaseObject() {
            return allowOperation("RELEASE_OBJECT");
        }

        public Builder allowGetField() {
            return allowOperation("GET_FIELD");
        }

        public Builder allowSetField() {
            return allowOperation("SET_FIELD");
        }

        public Builder allowGetStaticField() {
            return allowOperation("GET_STATIC_FIELD");
        }

        public Builder allowSetStaticField() {
            return allowOperation("SET_STATIC_FIELD");
        }

        public Builder allowQueryProvider() {
            return allowOperation("QUERY_PROVIDER");
        }

        public Builder allowLaunchActivity() {
            return allowOperation("LAUNCH_ACTIVITY");
        }

        public Builder allowUriExtension() {
            return allowExtension("URI", 1);
        }

        public Builder allowComponentNameExtension() {
            return allowExtension("COMPONENT_NAME", 1);
        }

        public Builder allowIntentExtension() {
            return allowExtension("INTENT", 1);
        }

        public Builder allowProviderAuthority(String authority) {
            if (authority == null || authority.trim().isEmpty()
                    || authority.contains("/")
                    || authority.contains(":")
                    || authority.contains(" ")) {
                throw new IllegalArgumentException(
                        "Invalid Provider authority"
                );
            }
            providers.add(authority);
            return this;
        }

        public Builder allowActivity(
                ComponentName component,
                String action,
                int flags,
                Map<String, ExtraType> allowedExtras
        ) {
            if (component == null
                    || component.getPackageName() == null
                    || component.getClassName() == null) {
                throw new IllegalArgumentException(
                        "Invalid Activity component"
                );
            }

            if (allowedExtras == null) {
                throw new IllegalArgumentException(
                        "Allowed Activity extras must not be null"
                );
            }

            Map<String, ExtraType> extras = new LinkedHashMap<>();

            for (Map.Entry<String, ExtraType> entry : allowedExtras.entrySet()) {
                String key = entry.getKey();
                ExtraType type = entry.getValue();

                if (key == null || key.trim().isEmpty() || type == null) {
                    throw new IllegalArgumentException(
                            "Invalid allowed Activity extra"
                    );
                }

                extras.put(key, type);
            }

            String flattened = component.flattenToString();
            activities.put(
                    flattened,
                    new ActivityRule(flattened, action, flags, extras)
            );

            return this;
        }

        public RootCorePolicy build() {
            return new RootCorePolicy(
                    providers, activities, operations, extensions
            );
        }
    }
}
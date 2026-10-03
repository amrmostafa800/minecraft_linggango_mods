package com.linggango.sync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.linggango.sync.LinggangoSync;
import com.linggango.sync.policy.DataClassifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads and holds the operator-facing configuration at {@code config/linggangosync.json}.
 *
 * <p>A JSON file (rather than {@code ForgeConfigSpec}) is used deliberately: per-mod
 * policy keys are open-ended and the set of installed mods differs between the server
 * and each client. A schema-less map lets an operator add {@code mods.<namespace>}
 * entries for mods this build has never heard of, without a mod update.
 *
 * <p>Missing or unparsable values fall back to the safe defaults in
 * {@link DataClassifier}, never to an optimisation.
 */
public final class SyncConfig {

    public static final String FILE_NAME = "linggangosync.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile SyncConfig instance = new SyncConfig();

    private final Map<String, SyncPolicy> categories = new LinkedHashMap<>();
    private final Map<String, Map<String, SyncPolicy>> modOverrides = new LinkedHashMap<>();
    private final Map<String, SyncPolicy> modDefaults = new LinkedHashMap<>();

    private boolean debugEnabled = false;
    private int statsIntervalSeconds = 60;
    private int packetHistoryMinutes = 5;
    private boolean logPerPacket = false;
    private boolean autoReportOnStop = true;

    private int cacheMaxEntries = 4096;
    private long cacheMaxBytes = 64L * 1024L * 1024L;
    private long cacheTtlSeconds = 7L * 24L * 3600L;
    private boolean cachePersistToDisk = true;
    private boolean cacheCompressOnDisk = false;

    private SyncConfig() {
        categories.putAll(DataClassifier.defaultCategoryPolicies());
        modDefaults.putAll(DataClassifier.defaultModPolicies());
    }

    public static SyncConfig get() {
        return instance;
    }

    public static Path path(Path configDir) {
        return configDir.resolve(FILE_NAME);
    }

    /** Loads (or creates) the config file. Never throws: on failure the safe defaults stay in place. */
    public static void load(Path configDir) {
        SyncConfig loaded = new SyncConfig();
        Path file = path(configDir);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(configDir);
                Files.writeString(file, GSON.toJson(loaded.toJson()), StandardCharsets.UTF_8);
                LinggangoSync.LOGGER.info("Wrote default config to {}", file);
            } else {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (root.isJsonObject()) {
                    loaded.apply(root.getAsJsonObject());
                } else {
                    LinggangoSync.LOGGER.warn("{} is not a JSON object; keeping safe defaults", file);
                }
            }
        } catch (IOException | RuntimeException e) {
            LinggangoSync.LOGGER.error("Could not read {}; keeping safe defaults ({})", file, e.toString());
        }
        instance = loaded;
    }

    private void apply(JsonObject root) {
        JsonObject debug = object(root, "debug");
        if (debug != null) {
            debugEnabled = bool(debug, "enabled", debugEnabled);
            statsIntervalSeconds = integer(debug, "statsIntervalSeconds", statsIntervalSeconds);
            packetHistoryMinutes = integer(debug, "packetHistoryMinutes", packetHistoryMinutes);
            logPerPacket = bool(debug, "logPerPacket", logPerPacket);
            autoReportOnStop = bool(debug, "autoReportOnStop", autoReportOnStop);
        }

        JsonObject cache = object(root, "cache");
        if (cache != null) {
            cacheMaxEntries = integer(cache, "maxEntries", cacheMaxEntries);
            cacheMaxBytes = longValue(cache, "maxBytes", cacheMaxBytes);
            cacheTtlSeconds = longValue(cache, "ttlSeconds", cacheTtlSeconds);
            cachePersistToDisk = bool(cache, "persistToDisk", cachePersistToDisk);
            cacheCompressOnDisk = bool(cache, "compressOnDisk", cacheCompressOnDisk);
        }

        JsonObject sync = object(root, "sync");
        if (sync != null) {
            for (Map.Entry<String, JsonElement> entry : sync.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    categories.put(entry.getKey(), SyncPolicy.parse(entry.getValue().getAsString()));
                }
            }
        }

        JsonObject mods = object(root, "mods");
        if (mods != null) {
            for (Map.Entry<String, JsonElement> entry : mods.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                Map<String, SyncPolicy> perCategory = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> policy : entry.getValue().getAsJsonObject().entrySet()) {
                    if (policy.getValue().isJsonPrimitive()) {
                        perCategory.put(policy.getKey(), SyncPolicy.parse(policy.getValue().getAsString()));
                    }
                }
                if (!perCategory.isEmpty()) {
                    modOverrides.put(entry.getKey(), perCategory);
                }
            }
        }
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);

        JsonObject debug = new JsonObject();
        debug.addProperty("enabled", debugEnabled);
        debug.addProperty("statsIntervalSeconds", statsIntervalSeconds);
        debug.addProperty("packetHistoryMinutes", packetHistoryMinutes);
        debug.addProperty("logPerPacket", logPerPacket);
        debug.addProperty("autoReportOnStop", autoReportOnStop);
        root.add("debug", debug);

        JsonObject cache = new JsonObject();
        cache.addProperty("maxEntries", cacheMaxEntries);
        cache.addProperty("maxBytes", cacheMaxBytes);
        cache.addProperty("ttlSeconds", cacheTtlSeconds);
        cache.addProperty("persistToDisk", cachePersistToDisk);
        cache.addProperty("compressOnDisk", cacheCompressOnDisk);
        root.add("cache", cache);

        JsonObject sync = new JsonObject();
        new TreeMap<>(categories).forEach((key, policy) -> sync.addProperty(key, policy.name()));
        root.add("sync", sync);

        // Namespace-wide defaults are written as a "*" entry, which is the same wildcard
        // the generic adapter uses, so an operator can read and edit them in place.
        JsonObject mods = new JsonObject();
        Map<String, Map<String, SyncPolicy>> merged = new TreeMap<>();
        modDefaults.forEach((namespace, policy) -> {
            Map<String, SyncPolicy> entry = new LinkedHashMap<>();
            entry.put("*", policy);
            merged.put(namespace, entry);
        });
        modOverrides.forEach((namespace, perCategory) ->
                merged.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).putAll(perCategory));
        merged.forEach((namespace, perCategory) -> {
            JsonObject entry = new JsonObject();
            new TreeMap<>(perCategory).forEach((k, v) -> entry.addProperty(k, v.name()));
            mods.add(namespace, entry);
        });
        root.add("mods", mods);
        return root;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean bool(JsonObject parent, String key, boolean fallback) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    private static int integer(JsonObject parent, String key, int fallback) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject parent, String key, long fallback) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public Map<String, SyncPolicy> categories() {
        return Collections.unmodifiableMap(categories);
    }

    public Map<String, Map<String, SyncPolicy>> modOverrides() {
        return Collections.unmodifiableMap(modOverrides);
    }

    public Map<String, SyncPolicy> modDefaults() {
        return Collections.unmodifiableMap(modDefaults);
    }

    public boolean debugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean value) {
        this.debugEnabled = value;
    }

    public int statsIntervalSeconds() {
        return statsIntervalSeconds;
    }

    public int packetHistoryMinutes() {
        return packetHistoryMinutes;
    }

    public boolean logPerPacket() {
        return logPerPacket;
    }

    public boolean autoReportOnStop() {
        return autoReportOnStop;
    }

    public int cacheMaxEntries() {
        return cacheMaxEntries;
    }

    public long cacheMaxBytes() {
        return cacheMaxBytes;
    }

    public long cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public boolean cachePersistToDisk() {
        return cachePersistToDisk;
    }

    public boolean cacheCompressOnDisk() {
        return cacheCompressOnDisk;
    }
}

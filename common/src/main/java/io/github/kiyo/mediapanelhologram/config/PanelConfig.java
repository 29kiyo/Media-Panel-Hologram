package io.github.kiyo.mediapanelhologram.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod config stored as JSON at config/mediapanelhologram.json.
 * Loaded once from MediaPanelHologram.init(). A missing file is created
 * with defaults; a broken file or out-of-range value falls back to the
 * default (the file is not overwritten in that case).
 *
 * maxGroupSize: max width/height of a merged panel group (CLAUDE.md 1.2).
 * Default 16, valid range 1..MAX_ALLOWED.
 */
public final class PanelConfig {
    private static final int DEFAULT_MAX_GROUP_SIZE = 16;
    private static final int MAX_ALLOWED = 256;
    private static final String FILE_NAME = "mediapanelhologram.json";

    private static int maxGroupSize = DEFAULT_MAX_GROUP_SIZE;

    public static int maxGroupSize() {
        return maxGroupSize;
    }

    public static void load() {
        Path path = Platform.getConfigFolder().resolve(FILE_NAME);
        if (!Files.exists(path)) {
            maxGroupSize = DEFAULT_MAX_GROUP_SIZE;
            save(path);
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            int v = obj.has("maxGroupSize") ? obj.get("maxGroupSize").getAsInt() : DEFAULT_MAX_GROUP_SIZE;
            if (v < 1 || v > MAX_ALLOWED) {
                System.err.println("[mediapanelhologram] maxGroupSize out of range (1.." + MAX_ALLOWED
                        + "): " + v + " -> using " + DEFAULT_MAX_GROUP_SIZE);
                v = DEFAULT_MAX_GROUP_SIZE;
            }
            maxGroupSize = v;
        } catch (Exception e) {
            System.err.println("[mediapanelhologram] Failed to read " + path + ": " + e + " -> using defaults");
            maxGroupSize = DEFAULT_MAX_GROUP_SIZE;
        }
    }

    private static void save(Path path) {
        JsonObject obj = new JsonObject();
        obj.addProperty("maxGroupSize", maxGroupSize);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(obj, w);
            }
        } catch (IOException e) {
            System.err.println("[mediapanelhologram] Failed to write " + path + ": " + e);
        }
    }

    private PanelConfig() {
    }
}

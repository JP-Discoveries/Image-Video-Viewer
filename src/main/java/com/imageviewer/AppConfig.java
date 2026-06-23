package com.imageviewer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application configuration, persisted as JSON at
 *   {@code ~/.image_viewer/config_java.json}
 *
 * All fields are public so Jackson can serialise/deserialise them without
 * additional annotations.  Unknown JSON keys are ignored (forward-compatible).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    // ── Config file location ─────────────────────────────────────────────────
    private static final Path CONFIG_PATH = Paths.get(
            System.getProperty("user.home"), ".image_viewer", "config_java.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Window state ─────────────────────────────────────────────────────────
    public double  windowWidth        = 1280;
    public double  windowHeight       = 800;
    public double  windowX            = -1;
    public double  windowY            = -1;
    public boolean windowMaximized    = false;

    // ── Panel sizes ──────────────────────────────────────────────────────────
    public double  thumbnailPanelWidth = 215;
    public int     thumbnailSize       = 120;   // px (width of each thumb)

    // ── Appearance ───────────────────────────────────────────────────────────
    public String  theme = "Dark";              // Dark | Light | Blue

    // ── Playback ─────────────────────────────────────────────────────────────
    public double  volume            = 1.0;
    public boolean loopVideo         = false;
    public boolean muteAudio         = false;
    public int     slideshowInterval = 5;       // seconds between images
    public String  transitionMode    = "None";   // None | Fade | DipToBlack

    // ── RAW photo decode ─────────────────────────────────────────────────────
    /** "ImageMagickFirst" (default) = full debayered decode via ImageMagick, embedded JPEG fallback.
     *  "EmbeddedFirst" = extract embedded JPEG preview (fast, no install required). */
    public String rawDecodeMode   = "ImageMagickFirst";

    // ── Frame captures folder ──────────────────────────────────────────────────
    /** Absolute path to the folder where frame captures are saved. null = use app working dir + /FrameCaptures */
    public String capturesFolder = null;

    // ── Alpha display ─────────────────────────────────────────────────────────
    /** When true, transparent pixels in alpha .mov / .webp show a checkerboard pattern
     *  instead of a solid black background. */
    public boolean alphaCheckerboard = false;

    // ── Video resume positions (path → last position ms) ─────────────────────
    /** Maps absolute video path → last watched position in milliseconds (0 = no saved position). */
    public Map<String, Long> videoPositions = new LinkedHashMap<>();

    // ── Video bookmarks (path → list of named timestamps) ────────────────────
    /** Maps absolute video path → user-defined named bookmarks. */
    public Map<String, List<VideoBookmark>> videoBookmarks = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoBookmark {
        public String name = "";
        public long   ms   = 0;
        public VideoBookmark() {}
        public VideoBookmark(String name, long ms) { this.name = name; this.ms = ms; }
    }

    // ── Navigation history ───────────────────────────────────────────────────
    public List<String> recentFolders    = new ArrayList<>();
    public List<String> favoriteFolders  = new ArrayList<>();
    public String       lastFolder       = null;

    // ── Starred files (persisted by absolute path string) ────────────────────
    public List<String> starredFiles = new ArrayList<>();

    public boolean isStarred(Path path) {
        return starredFiles.contains(path.toAbsolutePath().toString());
    }

    public void setStarred(Path path, boolean starred) {
        String key = path.toAbsolutePath().toString();
        if (starred) {
            if (!starredFiles.contains(key)) starredFiles.add(key);
        } else {
            starredFiles.remove(key);
        }
    }

    // ── Static factory ───────────────────────────────────────────────────────
    public static AppConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                return MAPPER.readValue(CONFIG_PATH.toFile(), AppConfig.class);
            } catch (IOException e) {
                System.err.println("[config] load error: " + e.getMessage());
            }
        }
        return new AppConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(CONFIG_PATH.toFile(), this);
        } catch (IOException e) {
            System.err.println("[config] save error: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    public void addRecentFolder(String folder) {
        recentFolders.remove(folder);
        recentFolders.add(0, folder);
        if (recentFolders.size() > 25)
            recentFolders = new ArrayList<>(recentFolders.subList(0, 25));
    }

    public void toggleFavorite(String folder) {
        if (favoriteFolders.contains(folder))
            favoriteFolders.remove(folder);
        else
            favoriteFolders.add(folder);
    }
}

package com.imageviewer;

import javafx.scene.Scene;

import java.util.List;
import java.util.Objects;

/**
 * Manages CSS theme switching.  Themes are bundled as resources under
 *   {@code com/imageviewer/css/<name>.css}
 */
public class ThemeManager {

    public static final List<String> THEME_NAMES = List.of(
            "Dark", "Light", "Blue", "Nord", "Dracula", "Monokai", "High Contrast",
            "Solarized Dark", "Solarized Light", "Gruvbox", "One Dark", "Catppuccin", "Tokyo Night");

    private String currentTheme = "Dark";

    public String getCurrentTheme() { return currentTheme; }

    /**
     * Apply the named theme to {@code scene}, removing any previously
     * applied theme stylesheet first.
     */
    public void applyTheme(Scene scene, String themeName) {
        if (!THEME_NAMES.contains(themeName)) {
            System.err.println("[theme] unknown theme: " + themeName + " — using Dark");
            themeName = "Dark";
        }
        currentTheme = themeName;

        // Remove all existing theme sheets
        scene.getStylesheets().removeIf(s -> s.contains("/css/"));

        String path = "/com/imageviewer/css/" + themeName.toLowerCase().replace(" ", "_") + ".css";
        var url = ThemeManager.class.getResource(path);
        if (url == null) {
            System.err.println("[theme] CSS not found: " + path);
            return;
        }
        scene.getStylesheets().add(url.toExternalForm());
    }

    /** Cycle to the next theme in the list and return its name. */
    public String nextTheme() {
        int idx = THEME_NAMES.indexOf(currentTheme);
        return THEME_NAMES.get((idx + 1) % THEME_NAMES.size());
    }
}

package com.imageviewer;

import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Central resolver for bundled external tools (ffmpeg, Python, Whisper models).
 *
 * Each method checks the app's own directory first (next to the JAR), then
 * falls back to the system PATH so the app also works in a developer run.
 */
public final class BundledTools {

    private BundledTools() {}

    // ── App directory ─────────────────────────────────────────────────────────

    /**
     * Returns the directory that contains the running JAR (or class root).
     * Uses multiple strategies because {@code getCodeSource().getLocation()}
     * can return {@code null} inside a jpackage app-image.
     */
    static Path appDir() {
        List<Path> candidates = new ArrayList<>();

        // 1. Code source (works in dev / fat-JAR runs)
        try {
            URL loc = BundledTools.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Path.of(loc.toURI());
                candidates.add(Files.isRegularFile(p) ? p.getParent() : p);
            }
        } catch (Exception ignored) {}

        // 2. java.home → jpackage puts runtime/ next to app/, so go up one level
        try {
            Path javaHome = Path.of(System.getProperty("java.home", ""));
            candidates.add(javaHome.getParent().resolve("app")); // jpackage layout
            candidates.add(javaHome.getParent());                 // non-jpackage fallback
        } catch (Exception ignored) {}

        // 3. Running exe path via ProcessHandle (jpackage .exe sits one level above app/)
        try {
            ProcessHandle.current().info().command().ifPresent(exe -> {
                Path exeDir = Path.of(exe).getParent();
                if (exeDir != null) {
                    candidates.add(exeDir.resolve("app"));
                    candidates.add(exeDir);
                }
            });
        } catch (Exception ignored) {}

        // Return the first candidate that actually exists
        for (Path p : candidates) {
            try {
                if (p != null && Files.isDirectory(p)) return p;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── ffmpeg / ffprobe ──────────────────────────────────────────────────────

    /** Path to ffmpeg.exe — bundled copy takes priority over system PATH. */
    public static String ffmpeg() {
        return resolve("ffmpeg", "ffmpeg.exe", "ffmpeg");
    }

    /** Path to ffprobe.exe — bundled copy takes priority over system PATH. */
    public static String ffprobe() {
        return resolve("ffmpeg", "ffprobe.exe", "ffprobe");
    }

    /** Path to ImageMagick — bundled magick.exe takes priority over system PATH. */
    public static String magick() {
        return resolve("imagemagick", "magick.exe", "magick");
    }

    // ── Python ────────────────────────────────────────────────────────────────

    /**
     * Path to python.exe — the bundled interpreter takes priority, then the
     * system {@code python} command.
     *
     * Two bundled layouts are supported: the python.org <em>embeddable</em>
     * distribution puts {@code python.exe} at {@code python_env\python.exe},
     * while a classic venv puts it under {@code python_env\Scripts\}.
     */
    public static String python() {
        Path dir = appDir();
        if (dir != null) {
            Path env = dir.resolve("python_env");
            Path embedded = env.resolve("python.exe");                       // embeddable layout
            if (Files.exists(embedded)) return embedded.toAbsolutePath().toString();
            Path venv = env.resolve("Scripts").resolve("python.exe");        // venv layout
            if (Files.exists(venv)) return venv.toAbsolutePath().toString();
        }
        return "python";
    }

    // ── Whisper model directory ───────────────────────────────────────────────

    /**
     * Returns the bundled Whisper model directory, or {@code null} if not bundled.
     * When non-null, pass it via {@code --model_dir} so Whisper uses the
     * pre-downloaded model instead of downloading on first use.
     */
    public static Path whisperModelDir() {
        Path dir = appDir();
        if (dir == null) return null;
        Path models = dir.resolve("whisper_models");
        return Files.isDirectory(models) ? models : null;
    }

    // ── JPEG colour-space detection ───────────────────────────────────────────

    /**
     * Returns {@code true} if {@code path} is a JPEG with 4 colour components
     * (CMYK or YCCK).  JavaFX loads these without error but renders them blank,
     * so callers should skip directly to an ImageIO/ffmpeg fallback.
     */
    public static boolean isCmykJpeg(Path path) {
        try (java.io.InputStream is = Files.newInputStream(path)) {
            if (is.read() != 0xFF || is.read() != 0xD8) return false;
            byte[] buf = new byte[2];
            while (true) {
                int b = is.read();
                if (b == -1) break;
                if ((b & 0xFF) != 0xFF) continue;
                int marker = is.read() & 0xFF;
                if (marker == 0xD9 || marker == 0xDA) break; // EOI or SOS
                // SOF markers
                if ((marker >= 0xC0 && marker <= 0xC3) ||
                    (marker >= 0xC5 && marker <= 0xC7) ||
                    (marker >= 0xC9 && marker <= 0xCB) ||
                    (marker >= 0xCD && marker <= 0xCF)) {
                    // length(2) + precision(1) + height(2) + width(2) = 7 bytes, then components(1)
                    is.skipNBytes(7);
                    int components = is.read() & 0xFF;
                    return components == 4;
                } else {
                    if (is.read(buf, 0, 2) < 2) break;
                    int segLen = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
                    if (segLen < 2) break;
                    is.skipNBytes(segLen - 2);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Looks for {@code subdir/relExe} next to the JAR.
     * Returns its absolute path string if found, otherwise {@code fallback}.
     */
    private static String resolve(String subdir, String relExe, String fallback) {
        Path dir = appDir();
        if (dir != null) {
            Path candidate = dir.resolve(subdir).resolve(relExe);
            if (Files.exists(candidate)) return candidate.toAbsolutePath().toString();
        }
        return fallback;
    }
}

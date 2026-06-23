package com.imageviewer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Manages a persistent Python subprocess running translate_bridge.py (Argos Translate).
 *
 * <p>Singleton: use {@link #getInstance()}.  Call {@link #stopGlobal()} on app close.
 * All public methods are thread-safe and block until the Python bridge responds.
 * Never call from the JavaFX Application Thread — always use a background thread.
 */
public class TranslationService {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static TranslationService INSTANCE;

    public static synchronized TranslationService getInstance() {
        if (INSTANCE == null) INSTANCE = new TranslationService();
        return INSTANCE;
    }

    /** Call from {@code wireWindowClose()} to shut the Python process down cleanly. */
    public static synchronized void stopGlobal() {
        if (INSTANCE != null) {
            INSTANCE.stop();
            INSTANCE = null;
        }
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    private Process        proc;
    private BufferedWriter out;
    private BufferedReader in;

    private TranslationService() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the bridge process and verifies it responds to ping.
     *
     * @return {@code true} if the bridge is up; {@code false} if Python or
     *         argostranslate is not installed.
     */
    public synchronized boolean start() {
        if (isRunning()) return true;
        try {
            Path bridge = extractBridge();
            ProcessBuilder pb = new ProcessBuilder(BundledTools.python(), bridge.toString());
            // Force UTF-8 I/O so accented characters (ñ é ü ç …) survive the JSON stream
            // on Windows, where Python defaults to cp1252.
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");   // Python 3.7+ belt-and-suspenders
            // Suppress Python's own stderr so it never contaminates the JSON stdout stream.
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            proc = pb.start();
            out  = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            in   = new BufferedReader(new InputStreamReader(proc.getInputStream(),  StandardCharsets.UTF_8));
            // Quick health-check
            Map<?, ?> pong = send(Map.of("cmd", "ping"));
            return "pong".equals(pong.get("result"));
        } catch (Exception e) {
            System.err.println("[TranslationService] start failed: " + e.getMessage());
            stop();
            return false;
        }
    }

    public synchronized boolean isRunning() {
        return proc != null && proc.isAlive();
    }

    public synchronized void stop() {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (in  != null) in .close(); } catch (IOException ignored) {}
        if (proc != null) { proc.destroyForcibly(); proc = null; }
        out = null;
        in  = null;
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Auto-detect the language of {@code text}.
     * Requires {@code pip install langdetect}; returns {@code "unknown"} if unavailable.
     */
    public synchronized String detect(String text) throws Exception {
        Map<?, ?> r = send(Map.of("cmd", "detect", "text", text));
        Object val = r.get("result");
        return val != null ? String.valueOf(val) : "unknown";
    }

    /** Translate a single string. */
    public synchronized String translate(String text, String from, String to) throws Exception {
        Map<?, ?> r = send(Map.of("cmd", "translate", "text", text, "from", from, "to", to));
        checkError(r);
        return String.valueOf(r.get("result"));
    }

    /**
     * Translate a list of strings in one Python call — much faster than calling
     * {@link #translate} per entry.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> translateList(List<String> texts, String from, String to)
            throws Exception {
        Map<?, ?> r = send(Map.of("cmd", "translate_list", "texts", texts, "from", from, "to", to));
        checkError(r);
        return (List<String>) r.get("result");
    }

    /** Returns all currently installed language packs. */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, String>> listInstalled() throws Exception {
        Map<?, ?> r = send(Map.of("cmd", "list_installed"));
        checkError(r);
        List<Map<String, String>> list = (List<Map<String, String>>) r.get("result");
        return list != null ? list : List.of();
    }

    /**
     * Fetches the full package index from the Argos server and returns all
     * available language packs (each entry includes an {@code "installed"} boolean).
     * This is slow (~2–5 s) — call from a background thread.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> listAvailable() throws Exception {
        Map<?, ?> r = send(Map.of("cmd", "list_available"));
        checkError(r);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("result");
        return list != null ? list : List.of();
    }

    /**
     * Downloads and installs the language pack for {@code from → to}.
     * May block for up to ~2 minutes on a slow connection.
     */
    public synchronized String install(String from, String to) throws Exception {
        Map<?, ?> r = send(Map.of("cmd", "install", "from", from, "to", to));
        checkError(r);
        return String.valueOf(r.get("result"));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<?, ?> send(Map<?, ?> request) throws Exception {
        if (!isRunning()) throw new IOException("Translation service not running");
        out.write(JSON.writeValueAsString(request));
        out.newLine();
        out.flush();
        String line = in.readLine();
        if (line == null) throw new IOException("Bridge process closed unexpectedly");
        return JSON.readValue(line, Map.class);
    }

    private void checkError(Map<?, ?> r) throws Exception {
        Object err = r.get("error");
        if (err != null) throw new Exception(String.valueOf(err));
    }

    /**
     * Extracts {@code translate_bridge.py} from classpath resources to
     * {@code ~/.imageviewer/translate_bridge.py} (always overwrites so updates take effect).
     */
    private static Path extractBridge() throws IOException {
        Path dest = Path.of(System.getProperty("user.home"), ".imageviewer", "translate_bridge.py");
        Files.createDirectories(dest.getParent());
        try (InputStream is = TranslationService.class
                .getResourceAsStream("/com/imageviewer/translate_bridge.py")) {
            if (is == null) throw new IOException("translate_bridge.py not found in classpath resources");
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }
}

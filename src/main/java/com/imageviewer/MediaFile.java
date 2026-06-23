package com.imageviewer;

import javafx.scene.image.Image;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight model object representing a single image or video on disk.
 * Thumbnails are loaded lazily on a background thread and stored here.
 */
public class MediaFile {

    public enum Type { IMAGE, GIF, RAW, VIDEO, UNKNOWN }

    // ── Supported extensions ─────────────────────────────────────────────────
    public static final Set<String> IMAGE_EXTS = Set.of(
            "jpg", "jpeg", "png", "bmp", "tif", "tiff", "ico", "webp"
    );
    public static final Set<String> VIDEO_EXTS = Set.of(
            "mp4", "m4v", "mov", "avi", "mkv", "wmv", "flv", "webm", "ts", "mts", "m2ts"
    );
    /** RAW camera & ImageMagick-decoded formats (HEIC, RAW) — decoded via embedded JPEG or ImageMagick subprocess. */
    public static final Set<String> RAW_EXTS = Set.of(
            "heic", "heif",        // Apple HEIF/HEIC (requires ImageMagick + libheif)
            "cr2", "cr3",          // Canon
            "nef", "nrw",          // Nikon
            "arw", "srf", "sr2",   // Sony
            "dng",                 // Adobe / universal
            "raf",                 // Fujifilm
            "orf",                 // Olympus
            "rw2",                 // Panasonic
            "pef",                 // Pentax
            "mrw",                 // Minolta / Konica-Minolta
            "rwl",                 // Leica
            "iiq",                 // Phase One
            "3fr",                 // Hasselblad
            "x3f",                 // Sigma
            "dcr", "kdc",          // Kodak
            "srw"                  // Samsung
    );
    public static final Set<String> ALL_EXTS;
    static {
        var all = new java.util.HashSet<String>();
        all.addAll(IMAGE_EXTS);
        all.addAll(VIDEO_EXTS);
        all.addAll(RAW_EXTS);
        all.add("gif");
        ALL_EXTS = Set.copyOf(all);
    }

    // ── Fields ───────────────────────────────────────────────────────────────
    private final Path   path;
    private final Type   type;
    private final String ext;

    /** Thumbnail (120×80) — set by ThumbnailPanel's background loader. */
    private volatile Image    thumbnail;

    /**
     * Whether this video file contains an audio stream.
     * null = not yet probed, true = has audio, false = video-only / silent.
     * Ignored for image files.
     */
    private volatile Boolean hasAudio;

    // ── Constructor ──────────────────────────────────────────────────────────
    public MediaFile(Path path) {
        this.path = path;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        this.ext = dot >= 0 ? name.substring(dot + 1) : "";
        this.type = resolveType(this.ext);
    }

    private static Type resolveType(String ext) {
        if ("gif".equals(ext))          return Type.GIF;
        if (IMAGE_EXTS.contains(ext))   return Type.IMAGE;
        if (RAW_EXTS.contains(ext))     return Type.RAW;
        if (VIDEO_EXTS.contains(ext))   return Type.VIDEO;
        return Type.UNKNOWN;
    }

    // ── Accessors ────────────────────────────────────────────────────────────
    public Path   getPath()      { return path; }
    public Type   getType()      { return type; }
    public String getExt()       { return ext; }
    public String getFilename()  { return path.getFileName().toString(); }
    public boolean isImage()     { return type == Type.IMAGE || type == Type.GIF || type == Type.RAW; }
    public boolean isRaw()       { return type == Type.RAW; }
    public boolean isVideo()     { return type == Type.VIDEO; }
    public Image   getThumbnail()          { return thumbnail; }
    public void    setThumbnail(Image img) { this.thumbnail = img; }

    /** null = not yet probed; true = has audio; false = video-only. */
    public Boolean getHasAudio()             { return hasAudio; }
    public void    setHasAudio(boolean val)  { this.hasAudio = val; }

    /** Whether the user has starred this file. Set from AppConfig after construction. */
    private volatile boolean starred = false;
    public boolean isStarred()           { return starred; }
    public void    setStarred(boolean v) { this.starred = v; }

    // ── Sortable attributes (lazy-loaded on first access) ─────────────────────
    private volatile long fileSize     = Long.MIN_VALUE;
    private volatile long lastModified = Long.MIN_VALUE;

    /** File size in bytes. Read from disk once on first call; returns 0 on error. */
    public long getFileSize() {
        if (fileSize == Long.MIN_VALUE) {
            try { fileSize = java.nio.file.Files.size(path); }
            catch (java.io.IOException e) { fileSize = 0; }
        }
        return fileSize;
    }

    /** Last-modified time as epoch millis. Read from disk once on first call; returns 0 on error. */
    public long getLastModified() {
        if (lastModified == Long.MIN_VALUE) {
            try { lastModified = java.nio.file.Files.getLastModifiedTime(path).toMillis(); }
            catch (java.io.IOException e) { lastModified = 0; }
        }
        return lastModified;
    }

    /** Returns true if this path's extension is in the supported set. */
    public static boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return ALL_EXTS.contains(name.substring(dot + 1));
    }

    @Override public String toString() { return getFilename(); }
}

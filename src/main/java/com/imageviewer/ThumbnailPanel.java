package com.imageviewer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.bytedeco.javacpp.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Thumbnail sidebar — virtual ListView with async background image loading.
 *
 * Thumbnail resolution order for each file:
 *   1. {@code <parent>/thumbnails/<stem>.jpg|jpeg|png|webp}  (pre-generated cache)
 *   2. Direct scaled-down load (images only)
 *   3. Generic video icon placeholder (videos without cache)
 *
 * Click behaviour (no focus required — event filter fires before ListView selection):
 *   Normal click   → {@link #setOnSelect}       — navigate main pane
 *   Ctrl+click     → {@link #setOnQueueToggle}  — toggle in slideshow queue
 *   Shift+click    → {@link #setOnQueueRange}   — range-add to queue
 *   Alt+click      → {@link #setOnAltSelect}    — load into compare pane
 *
 * Right-click → context menu: Reveal in Explorer, Load into Compare, Add/Remove from Queue.
 * When 2+ files are queued, a "Delete N queued" / "Rename N queued" batch section appears
 * at the top of the context menu — the queue serves as both the slideshow order and the
 * multi-file selection for bulk operations.
 *
 * Queued items show an orange numbered badge; when 2+ items are queued a blue tint is also
 * applied so the "batch selection" is visually obvious.
 * Call {@link #setQueuePositions(Map)} to update badges; pass an empty map to clear all.
 */
public class ThumbnailPanel extends VBox {

    // ── Sort / filter enums ───────────────────────────────────────────────────
    public enum SortOrder  { NAME, DATE_MODIFIED, FILE_SIZE }
    public enum FilterType { ALL, IMAGES, VIDEOS }

    static final double THUMB_W = 170;
    static final double THUMB_H = 120;
    // Load at 2× display size for sharp downscaling
    private static final double LOAD_W = THUMB_W * 2;
    private static final double LOAD_H = THUMB_H * 2;

    // ── Placeholder icon ─────────────────────────────────────────────────────
    static final Image VIDEO_ICON = makeVideoIcon();

    private static Image makeVideoIcon() {
        var url = ThumbnailPanel.class.getResource("/com/imageviewer/css/video_thumb.png");
        return url != null ? new Image(url.toExternalForm(), THUMB_W, THUMB_H, true, true) : null;
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final ListView<MediaFile> listView;
    private Consumer<Integer>  onSelect      = i -> {};
    /** Alt+click — always loads the file into the compare pane. */
    private Consumer<Integer>  onAltSelect   = i -> {};
    /** Ctrl+click — always toggles the file in the slideshow queue. */
    private Consumer<Integer>  onQueueToggle = i -> {};
    /** Right-click → Reveal in Explorer. */
    private Consumer<Integer>  onReveal      = i -> {};
    /** Right-click → Rename file. */
    private Consumer<Integer>  onRename      = i -> {};
    /** Browse button clicked. */
    private Runnable           onBrowse      = () -> {};
    /** Fired on the FX thread after every sort/filter change so MainWindow can refresh the index label. */
    private Runnable           onSortFilterChanged = () -> {};
    /** Shift+click — adds every file in [anchor, clicked] range to the queue. */
    private Consumer<int[]>    onQueueRange  = range -> {};

    // ── Batch callbacks (triggered via right-click when 2+ items are queued) ──
    /** Called with sorted list of original indices when user triggers batch delete. */
    private Consumer<List<Integer>> onBatchDelete = idxs -> {};
    /** Called with sorted list of original indices when user triggers batch rename. */
    private Consumer<List<Integer>> onBatchRename = idxs -> {};

    // ── File operation callbacks ──────────────────────────────────────────────
    /** Right-click → Star / Unstar. */
    private Consumer<Integer> onStar   = i -> {};
    /** Right-click → Copy to folder. */
    private Consumer<Integer> onCopyTo = i -> {};
    /** Right-click → Move to folder. */
    private Consumer<Integer> onMoveTo = i -> {};

    // ── Sort / filter state ───────────────────────────────────────────────────
    /** When true, only starred files are shown in the sidebar. */
    private boolean showStarredOnly = false;
    /** Master list — every file in the loaded folder (unsorted, unfiltered). */
    private List<MediaFile> fullList = new ArrayList<>();
    /**
     * Maps display position → original index in {@code fullList} / MainWindow's {@code files}.
     * Updated by {@link #applyFilterAndSort()}.  When no filter is active and sort==NAME this
     * is the identity mapping.
     */
    private final List<Integer> displayToOriginal = new ArrayList<>();
    private SortOrder  sortOrder  = SortOrder.NAME;
    private FilterType filterType = FilterType.ALL;

    /** Tracks the last normally-selected index so Shift+click has an anchor. */
    private int shiftAnchorIdx = -1;

    /** True while select() is setting the selection programmatically — prevents the
     *  selectedIndexProperty listener from firing onSelect (which would cause a
     *  re-entrant navigateTo call and double-load the same MediaPlayer). */
    private boolean programmaticSelect = false;

    /**
     * Maps file-list index → 1-based queue position.
     * Updated via {@link #setQueuePositions(Map)}.
     * Cells read this directly (inner class access).
     */
    private Map<Integer, Integer> queuePositions = new HashMap<>();

    /** Paths that have already been submitted to thumbPool — prevents duplicate tasks. */
    private final Set<Path> submitted = ConcurrentHashMap.newKeySet();

    private final ExecutorService thumbPool = new ThreadPoolExecutor(
            4, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> { Thread t = new Thread(r, "thumb-loader"); t.setDaemon(true); return t; },
            // Discard-oldest policy: remove the oldest queued task (clear its path from
            // submitted so the cell can re-request if it becomes visible again), then
            // retry the incoming task — there is now room for it.
            (r, exec) -> {
                Runnable discarded = exec.getQueue().poll();
                if (discarded instanceof ThumbTask tt) submitted.remove(tt.path);
                if (!exec.isShutdown()) {
                    try { exec.execute(r); }
                    catch (Exception ignored) {
                        if (r instanceof ThumbTask tt) submitted.remove(tt.path);
                    }
                } else if (r instanceof ThumbTask tt) submitted.remove(tt.path);
            });

    // ── O(1) lookup maps — rebuilt by applyFilterAndSort() on the FX thread ──
    /** Maps a file's Path → its current display-list index (after filter/sort). */
    private final Map<Path, Integer>    displayIndexByPath = new HashMap<>();
    /** Inverse of displayToOriginal: original index → display index. */
    private final Map<Integer, Integer> originalToDisplay  = new HashMap<>();

    // ── Async filter/sort plumbing ────────────────────────────────────────────
    private final AtomicLong    filterGen      = new AtomicLong(0);
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "filter-sort"); t.setDaemon(true); return t;
    });



    // ── Constructor ───────────────────────────────────────────────────────────
    public ThumbnailPanel() {
        getStyleClass().add("thumbnail-panel");

        Button header = new Button("⊞  Thumbnail Browser");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setTooltip(new Tooltip("Open thumbnail browser  (B)"));
        header.getStyleClass().add("panel-header");
        header.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand; " +
                "-fx-background-color: transparent; -fx-alignment: CENTER; " +
                "-fx-padding: 8 8 8 8;");
        header.setOnMouseEntered(e -> header.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand; " +
                "-fx-background-color: rgba(255,255,255,0.07); -fx-alignment: CENTER; " +
                "-fx-padding: 8 8 8 8;"));
        header.setOnMouseExited(e -> header.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand; " +
                "-fx-background-color: transparent; -fx-alignment: CENTER; " +
                "-fx-padding: 8 8 8 8;"));
        header.setOnAction(e -> onBrowse.run());

        // ── Star filter bar ───────────────────────────────────────────────────
        ToggleButton starFilterBtn = new ToggleButton("⭐  Starred only");
        starFilterBtn.getStyleClass().add("toolbar-btn");
        starFilterBtn.setFocusTraversable(false);
        starFilterBtn.setMaxWidth(Double.MAX_VALUE);
        starFilterBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3 6 3 6; " +
                "-fx-background-radius: 0; -fx-border-radius: 0;");
        starFilterBtn.setTooltip(new Tooltip("Show only starred files"));
        starFilterBtn.setOnAction(e -> {
            showStarredOnly = starFilterBtn.isSelected();
            applyFilterAndSort();
        });

        listView = new ListView<>();
        listView.getStyleClass().add("thumb-list");
        listView.setCellFactory(lv -> new ThumbnailCell());
        VBox.setVgrow(listView, Priority.ALWAYS);

        // Normal click / keyboard selection → onSelect; also updates shift anchor.
        // Suppressed when programmaticSelect is true to avoid re-entrant navigateTo.
        listView.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            int displayIdx = n.intValue();
            if (displayIdx >= 0) {
                shiftAnchorIdx = displayIdx;
                if (!programmaticSelect) {
                    int origIdx = toOriginal(displayIdx);
                    onSelect.accept(origIdx);
                }
            }
        });

        // ── Click filter — modifier-key clicks handled before ListView selection ──
        // Using addEventFilter on MOUSE_PRESSED fires BEFORE ListView's internal
        // focus + selection handling, so no "click once to focus" is required.
        listView.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;

            // ── Walk up scene graph to find the cell ──────────────────────────
            Node node = (Node) e.getTarget();
            while (node != null && !(node instanceof ThumbnailCell)) node = node.getParent();
            if (!(node instanceof ThumbnailCell cell) || cell.getItem() == null) return;

            Integer _di = displayIndexByPath.get(cell.getItem().getPath());
            if (_di == null) return;
            int displayIdx = _di;
            int origIdx = toOriginal(displayIdx);

            boolean isAlt   = e.isAltDown();
            boolean isCtrl  = e.isControlDown();
            boolean isShift = e.isShiftDown();
            if (!isAlt && !isCtrl && !isShift) return;

            if (isAlt && !isShift) {
                onAltSelect.accept(origIdx);
            } else if (isCtrl && !isShift) {
                onQueueToggle.accept(origIdx);
            } else if (isShift && !isAlt && !isCtrl) {
                int anchor = shiftAnchorIdx >= 0 ? shiftAnchorIdx : displayIdx;
                int lo = Math.min(anchor, displayIdx);
                int hi = Math.max(anchor, displayIdx);
                int[] range = new int[hi - lo + 1];
                for (int i = lo; i <= hi; i++) range[i - lo] = toOriginal(i);
                onQueueRange.accept(range);
            }
            e.consume();
        });

        getChildren().addAll(header, starFilterBtn, listView);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setFiles(List<MediaFile> items) {
        cancelAllThumbnails();
        fullList = new ArrayList<>(items);
        applyFilterAndSort();
    }

    /**
     * Highlight the file at the given <em>original</em> index (i.e. its position in the
     * master list passed to {@link #setFiles}).  If the file is currently filtered out
     * its cell is simply not selected, but no error is thrown.
     */
    public void select(int originalIndex) {
        Integer displayIdx = originalToDisplay.get(originalIndex);
        if (displayIdx != null) {
            int di = displayIdx.intValue();
            // Only scroll when the item is not already selected (i.e. the navigation
            // came from outside the sidebar — browser, keyboard, etc.).  When the user
            // clicks a cell directly it is already selected, so scrollTo would
            // needlessly jump the viewport.
            boolean needsScroll = listView.getSelectionModel().getSelectedIndex() != di;
            programmaticSelect = true;
            listView.getSelectionModel().select(di);
            if (needsScroll) listView.scrollTo(di);
            programmaticSelect = false;
        }
    }

    /**
     * Update the slideshow-queue badge display.
     * @param positions map from file-list index → 1-based queue position (empty = no badges)
     */
    public void setQueuePositions(Map<Integer, Integer> positions) {
        this.queuePositions = new HashMap<>(positions);
        Platform.runLater(() -> listView.refresh());
    }

    /**
     * Replace the MediaFile at {@code originalIndex} in the master list with
     * {@code updated}.  Re-applies sort/filter so the new filename sorts correctly.
     */
    public void updateFile(int originalIndex, MediaFile updated) {
        if (originalIndex < 0 || originalIndex >= fullList.size()) return;
        fullList.set(originalIndex, updated);
        applyFilterAndSort();
    }

    public void clearSelection()          { listView.getSelectionModel().clearSelection(); }
    public int  getSelectedIndex()        { return listView.getSelectionModel().getSelectedIndex(); }
    public void setOnSelect(Consumer<Integer> cb)      { onSelect = cb; }
    /** Alt+click handler — called with the file index; should load into compare pane. */
    public void setOnAltSelect(Consumer<Integer> cb)   { onAltSelect = cb; }
    /** Ctrl+click handler — called with the file index; should toggle slideshow queue. */
    public void setOnQueueToggle(Consumer<Integer> cb) { onQueueToggle = cb; }
    /** Right-click → Reveal in Explorer handler. */
    public void setOnReveal(Consumer<Integer> cb)      { onReveal = cb; }
    /** Right-click → Rename file handler. */
    public void setOnRename(Consumer<Integer> cb)      { onRename = cb; }
    /** Shift+click handler — called with array of file indices in the selected range. */
    public void setOnQueueRange(Consumer<int[]> cb)    { onQueueRange = cb; }
    public void setOnBrowse(Runnable cb)               { onBrowse = cb; }
    /** Called (on the FX thread) whenever the displayed list changes due to sort/filter. */
    public void setOnSortFilterChanged(Runnable cb)    { onSortFilterChanged = cb; }
    public void setOnBatchDelete(Consumer<List<Integer>> cb) { onBatchDelete = cb; }
    public void setOnBatchRename(Consumer<List<Integer>> cb) { onBatchRename = cb; }
    public void setOnStar(Consumer<Integer> cb)   { onStar   = cb; }
    public void setOnCopyTo(Consumer<Integer> cb) { onCopyTo = cb; }
    public void setOnMoveTo(Consumer<Integer> cb) { onMoveTo = cb; }
    public void shutdown()                { thumbPool.shutdownNow(); filterExecutor.shutdownNow(); }

    /** Force-refresh cell rendering (e.g. after star state changes). */
    public void refreshStarState() { Platform.runLater(() -> listView.refresh()); }

    /**
     * Returns the 1-based position of {@code originalIndex} in the current display list,
     * or 0 if that file is filtered out.
     */
    public int getDisplayIndex(int originalIndex) {
        Integer i = originalToDisplay.get(originalIndex);
        return i != null ? i + 1 : 0;
    }

    /** Number of files currently shown (after filter). */
    public int getDisplayCount() { return displayToOriginal.size(); }

    // ── Sort / filter helpers ─────────────────────────────────────────────────

    /**
     * Rebuild the ListView from {@code fullList} using the current sort and filter.
     * <p>
     * The filter + sort work runs on a background thread (filterExecutor) to avoid
     * blocking the FX thread for 10 k+ file lists.  A generation counter ensures
     * that stale operations (superseded before they finish) are silently discarded.
     */
    private void applyFilterAndSort() {
        long gen = filterGen.incrementAndGet();

        // Snapshot mutable state on the FX thread before handing off.
        List<MediaFile> snapshot      = new ArrayList<>(fullList);
        FilterType      ft            = filterType;
        SortOrder       so            = sortOrder;
        boolean         starredOnly   = showStarredOnly;
        Runnable        sortCb        = onSortFilterChanged;

        filterExecutor.submit(() -> {
            // ── 1. Filter ────────────────────────────────────────────────────
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < snapshot.size(); i++) {
                if (filterGen.get() != gen) return; // superseded
                MediaFile f = snapshot.get(i);
                boolean keep = switch (ft) {
                    case IMAGES -> f.isImage();
                    case VIDEOS -> f.isVideo();
                    default     -> true;
                };
                if (starredOnly) keep = keep && f.isStarred();
                if (keep) indices.add(i);
            }
            if (filterGen.get() != gen) return;

            // ── 2. Sort ──────────────────────────────────────────────────────
            Comparator<Integer> cmp = switch (so) {
                case DATE_MODIFIED ->
                        Comparator.<Integer, Long>comparing(
                                i -> snapshot.get(i).getLastModified()).reversed();
                case FILE_SIZE ->
                        Comparator.<Integer, Long>comparing(
                                i -> snapshot.get(i).getFileSize()).reversed();
                default ->
                        (a, b) -> naturalCompare(
                                snapshot.get(a).getFilename(),
                                snapshot.get(b).getFilename());
            };
            indices.sort(cmp);
            if (filterGen.get() != gen) return;

            // ── 3. Build display list and O(1) lookup maps ───────────────────
            List<MediaFile>      displayFiles = new ArrayList<>(indices.size());
            Map<Path, Integer>   idxByPath    = new HashMap<>(indices.size() * 2);
            Map<Integer,Integer> origToDisp   = new HashMap<>(indices.size() * 2);
            for (int d = 0; d < indices.size(); d++) {
                int orig = indices.get(d);
                displayFiles.add(snapshot.get(orig));
                idxByPath.put(snapshot.get(orig).getPath(), d);
                origToDisp.put(orig, d);
            }
            if (filterGen.get() != gen) return;

            // ── 4. Apply on FX thread ────────────────────────────────────────
            Platform.runLater(() -> {
                if (filterGen.get() != gen) return; // superseded before we ran
                displayToOriginal.clear();
                displayToOriginal.addAll(indices);
                displayIndexByPath.clear();
                displayIndexByPath.putAll(idxByPath);
                originalToDisplay.clear();
                originalToDisplay.putAll(origToDisp);
                listView.getItems().setAll(displayFiles);
                sortCb.run();
            });
        });
    }

    /** Translate a display-list position to the original-file-list index. */
    private int toOriginal(int displayIdx) {
        return (displayIdx >= 0 && displayIdx < displayToOriginal.size())
                ? displayToOriginal.get(displayIdx) : displayIdx;
    }

    // ── Async thumbnail loader ────────────────────────────────────────────────

    void requestThumbnailLoad(MediaFile file) {
        if (file.getThumbnail() != null) return;
        if (!submitted.add(file.getPath())) return; // already queued
        Path path = file.getPath();
        thumbPool.execute(new ThumbTask(path, () -> {
            Image img = loadThumbnail(file); // may set hasAudio via generateVideoThumbnail
            submitted.remove(path);

            // Audio detection for videos whose sidecar was already on disk
            // (generateVideoThumbnail was skipped so hasAudio was never set above).
            if (file.isVideo() && file.getHasAudio() == null) {
                file.setHasAudio(probeHasAudio(path));
            }

            if (img != null) file.setThumbnail(img);

            // O(1) cell refresh — no indexOf() scan needed.
            Platform.runLater(() -> {
                Integer d = displayIndexByPath.get(path);
                if (d != null) listView.getItems().set(d, file);
            });
        }));
    }

    /** Returns true if the file has at least one audio stream (via JavaCPP avformat). */
    private boolean probeHasAudio(Path filePath) {
        try {
            AVFormatContext fmtCtx = avformat_alloc_context();
            if (avformat_open_input(fmtCtx, filePath.toString(), null, null) < 0) return false;
            avformat_find_stream_info(fmtCtx, (PointerPointer) null);
            boolean found = false;
            for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                if (fmtCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                    found = true; break;
                }
            }
            avformat_close_input(fmtCtx);
            return found;
        } catch (Exception ignored) { return false; }
    }

    /**
     * Signal in-flight thumbnail tasks to abort before opening a video for playback.
     * Thumbnails for other files will restart automatically when their cells next become visible.
     */
    public void cancelAllForPlayback() {
        // JavaCPP tasks check submitted.contains() and bail out early — clearing it lets them
        // finish quickly without holding library resources during playback.
        submitted.clear();
    }

    /** Clear submission set so stale tasks bail out when a new folder is loaded. */
    private void cancelAllThumbnails() {
        submitted.clear();
    }

    /**
     * Thumbnail resolution order:
     *   1. thumbnails/<stem>.{jpg,jpeg,png,webp} in the same parent directory
     *   2. Direct scaled load (images only)
     *   3. VIDEO_ICON placeholder (videos with no cached thumb)
     */
    private Image loadThumbnail(MediaFile file) {
        // ── 1. Pre-generated thumbnail cache ─────────────────────────────────
        Path parent = file.getPath().getParent();
        if (parent != null) {
            String name  = file.getFilename();
            int    dot   = name.lastIndexOf('.');
            String stem  = dot >= 0 ? name.substring(0, dot) : name;
            Path   tDir  = parent.resolve("thumbnails");

            for (String ext : List.of("jpg", "jpeg", "png", "webp")) {
                Path tp = tDir.resolve(stem + "." + ext);
                if (Files.exists(tp)) {
                    try {
                        return new Image(tp.toUri().toString(), LOAD_W, LOAD_H, true, true, false);
                    } catch (Exception ignored) {}
                }
            }
        }

        // ── 2. RAW photo — extract embedded JPEG preview, save as sidecar ───
        if (file.getType() == MediaFile.Type.RAW) {
            Image generated = generateRawThumbnail(file);
            if (generated != null) return generated;
            return null; // no preview found; cell stays blank
        }

        // ── 3. Direct load for standard images ───────────────────────────────
        if (file.isImage()) {
            try {
                Image img = new Image(file.getPath().toUri().toString(),
                        LOAD_W, LOAD_H, true, true, false);
                // Some formats (CMYK JPEG) load without error but produce a blank
                // 0-width image — treat those the same as an error.
                if (!img.isError() && img.getWidth() > 0) return img;
            } catch (Exception ignored) {}
            // Fallback: ImageIO handles CMYK JPEG, TIFF (Java 9+), and other formats
            // that JavaFX's built-in decoder rejects.
            try {
                java.awt.image.BufferedImage bi =
                        javax.imageio.ImageIO.read(file.getPath().toFile());
                if (bi != null) {
                    double scale = Math.min((double) LOAD_W / bi.getWidth(),
                                           (double) LOAD_H / bi.getHeight());
                    int tw = Math.max(1, (int)(bi.getWidth()  * scale));
                    int th = Math.max(1, (int)(bi.getHeight() * scale));
                    java.awt.image.BufferedImage scaled =
                            new java.awt.image.BufferedImage(tw, th,
                                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = scaled.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(bi, 0, 0, tw, th, null);
                    g2.dispose();
                    int[] argb = new int[tw * th];
                    scaled.getRGB(0, 0, tw, th, argb, 0, tw);
                    javafx.scene.image.WritableImage fx =
                            new javafx.scene.image.WritableImage(tw, th);
                    fx.getPixelWriter().setPixels(0, 0, tw, th,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, tw);
                    return fx;
                }
            } catch (Exception ignored) {}
            // Last resort: pipe through ffmpeg (handles WebP, ICO, HEIC, etc.)
            Image ffImg = loadThumbnailViaFfmpeg(file.getPath());
            if (ffImg != null) return ffImg;
            // Final fallback: ImageMagick (handles WebP variants ffmpeg can't decode, etc.)
            Image magickImg = loadThumbnailViaImageMagick(file.getPath());
            if (magickImg != null) return magickImg;
            return null;
        }

        // ── 4. Video — try to generate sidecar via FFmpeg, fall back to icon ────
        if (file.isVideo()) {
            Image generated = generateVideoThumbnail(file);
            if (generated != null) return generated;
        }
        return VIDEO_ICON;
    }

    // ── ffmpeg image thumbnail helper ────────────────────────────────────────

    /** Decode an image frame via ffmpeg → PNG pipe. Handles WebP, ICO, HEIC, etc. */
    private static Image loadThumbnailViaFfmpeg(Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                BundledTools.ffmpeg(), "-y", "-i", path.toAbsolutePath().toString(),
                "-frames:v", "1", "-vf", "scale=" + LOAD_W + ":" + LOAD_H + ":force_original_aspect_ratio=decrease",
                "-f", "image2pipe", "-vcodec", "png", "pipe:1");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            byte[] pngBytes = p.getInputStream().readAllBytes();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (pngBytes.length == 0) return null;
            return new Image(new java.io.ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decode an image frame via ImageMagick → PNG pipe, then scale to thumbnail size.
     * Used as a final fallback when both JavaFX and ffmpeg fail (e.g. yuva420p WebP).
     */
    private static Image loadThumbnailViaImageMagick(Path path) {
        String filePath = path.toAbsolutePath().toString();
        String magick = BundledTools.magick(); // bundled magick.exe or "magick"
        // Try "magick convert" (v7) then bare "convert" (v6)
        String[][] cmds = {
            { magick, "convert", filePath, "png:-" },
            { "convert", filePath, "png:-" }
        };
        for (String[] cmd : cmds) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                byte[] pngBytes = p.getInputStream().readAllBytes();
                p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (pngBytes.length == 0) continue;
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(pngBytes));
                if (bi == null) continue;
                double scale = Math.min((double) LOAD_W / bi.getWidth(),
                                       (double) LOAD_H / bi.getHeight());
                int tw = Math.max(1, (int)(bi.getWidth()  * scale));
                int th = Math.max(1, (int)(bi.getHeight() * scale));
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                        tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(bi, 0, 0, tw, th, null);
                g2.dispose();
                int[] argb = new int[tw * th];
                scaled.getRGB(0, 0, tw, th, argb, 0, tw);
                javafx.scene.image.WritableImage fx = new javafx.scene.image.WritableImage(tw, th);
                fx.getPixelWriter().setPixels(0, 0, tw, th,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, tw);
                return fx;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── RAW photo thumbnail helpers ───────────────────────────────────────────

    /**
     * Extract, scale, and cache a thumbnail for a RAW camera file.
     * Reads up to 20 MB looking for the largest embedded JPEG preview, scales it to
     * 640 px wide, saves it as a sidecar JPEG, and returns a JavaFX Image.
     */
    private Image generateRawThumbnail(MediaFile file) {
        Path path   = file.getPath();
        Path parent = path.getParent();
        if (parent == null) return null;

        String name = file.getFilename();
        int    dot  = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        Path   tDir = parent.resolve("thumbnails");
        Path   out  = tDir.resolve(stem + ".jpg");

        try {
            byte[] jpegBytes = extractEmbeddedJpeg(path, 20_000_000);
            if (jpegBytes == null) return null;

            Files.createDirectories(tDir);

            // Decode embedded JPEG → scale to 640 px wide → save as sidecar
            java.awt.image.BufferedImage src =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpegBytes));
            if (src == null) return null;

            int srcW  = src.getWidth(), srcH = src.getHeight();
            int dstW  = Math.min(640, srcW);
            int dstH  = Math.max(1, (int)(srcH * ((double) dstW / srcW)));
            java.awt.image.BufferedImage scaled =
                    new java.awt.image.BufferedImage(dstW, dstH, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                               java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, dstW, dstH, null);
            g.dispose();
            javax.imageio.ImageIO.write(scaled, "jpg", out.toFile());

            if (Files.exists(out))
                return new Image(out.toUri().toString(), LOAD_W, LOAD_H, true, true, false);
        } catch (Exception e) {
            System.err.println("[raw-thumb] " + file.getFilename() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Scan the first {@code maxBytes} of {@code path} for the largest complete JPEG
     * (SOI marker FF D8 FF [E0..FF] … EOI marker FF D9).
     * Returns the raw JPEG bytes, or {@code null} if none found.
     * <p>
     * Used to extract embedded preview images from RAW camera files without external tools.
     */
    static byte[] extractEmbeddedJpeg(Path path, int maxBytes) {
        try (var is = Files.newInputStream(path)) {
            byte[] buf = is.readNBytes(maxBytes);
            byte[] best = null;
            int i = 0;
            while (i < buf.length - 3) {
                // JPEG SOI: FF D8 FF followed by a marker byte (E0–FF covers all APPn + others)
                if ((buf[i]   & 0xFF) == 0xFF &&
                    (buf[i+1] & 0xFF) == 0xD8 &&
                    (buf[i+2] & 0xFF) == 0xFF &&
                    (buf[i+3] & 0xFF) >= 0xE0) {
                    int start = i;
                    boolean found = false;
                    // Scan forward for EOI (FF D9)
                    for (int j = start + 2; j < buf.length - 1; j++) {
                        if ((buf[j] & 0xFF) == 0xFF && (buf[j+1] & 0xFF) == 0xD9) {
                            byte[] candidate = java.util.Arrays.copyOfRange(buf, start, j + 2);
                            if (best == null || candidate.length > best.length) {
                                best = candidate;
                            }
                            i = j + 2;
                            found = true;
                            break;
                        }
                    }
                    if (!found) i++;
                } else {
                    i++;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Seeks to 10% of video duration, decodes one frame via JavaCPP FFmpeg,
     * scales to 640 px wide, saves as sidecar JPEG, returns a scaled JavaFX Image.
     */
    private Image generateVideoThumbnail(MediaFile file) {
        Path parent = file.getPath().getParent();
        if (parent == null) return null;

        String name = file.getFilename();
        int    dot  = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        Path   tDir = parent.resolve("thumbnails");
        Path   out  = tDir.resolve(stem + ".jpg");

        Path filePath = file.getPath();
        try {
            Files.createDirectories(tDir);

            // ── 1. Open container, detect audio streams, get duration ────────
            AVFormatContext fmtCtx = avformat_alloc_context();
            if (avformat_open_input(fmtCtx, filePath.toString(), null, null) < 0) return null;
            if (avformat_find_stream_info(fmtCtx, (PointerPointer) null) < 0) {
                avformat_close_input(fmtCtx); return null;
            }

            int     vidStream = -1;
            boolean hasAudio  = false;
            for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                AVStream st = fmtCtx.streams(i);
                int type = st.codecpar().codec_type();
                if (type == AVMEDIA_TYPE_VIDEO && vidStream < 0) vidStream = i;
                else if (type == AVMEDIA_TYPE_AUDIO)             hasAudio  = true;
            }
            file.setHasAudio(hasAudio);

            if (vidStream < 0 || !submitted.contains(filePath)) {
                avformat_close_input(fmtCtx); return null;
            }

            // ── 2. Seek to 10% of duration ───────────────────────────────────
            long durationUs = fmtCtx.duration(); // AV_TIME_BASE = 1_000_000 µs
            if (durationUs > 0)
                avformat_seek_file(fmtCtx, -1, Long.MIN_VALUE, durationUs / 10, durationUs / 10, 0);

            // ── 3. Open decoder (no alpha needed for thumbnails) ─────────────
            AVCodec codec = avcodec_find_decoder(fmtCtx.streams(vidStream).codecpar().codec_id());
            if (codec == null || codec.isNull()) { avformat_close_input(fmtCtx); return null; }

            AVCodecContext codecCtx = avcodec_alloc_context3(codec);
            avcodec_parameters_to_context(codecCtx, fmtCtx.streams(vidStream).codecpar());
            codecCtx.thread_count(2);
            if (avcodec_open2(codecCtx, codec, (AVDictionary) null) < 0) {
                avcodec_free_context(codecCtx); avformat_close_input(fmtCtx); return null;
            }

            // ── 4. Read packets until we get one decoded frame ───────────────
            AVPacket pkt   = av_packet_alloc();
            AVFrame  frame = av_frame_alloc();
            boolean  got   = false;
            outer:
            while (submitted.contains(filePath) && av_read_frame(fmtCtx, pkt) >= 0) {
                if (pkt.stream_index() != vidStream) { av_packet_unref(pkt); continue; }
                if (avcodec_send_packet(codecCtx, pkt) >= 0) {
                    av_packet_unref(pkt);
                    if (avcodec_receive_frame(codecCtx, frame) >= 0) { got = true; break outer; }
                } else {
                    av_packet_unref(pkt);
                }
            }

            Image result = null;
            if (got && submitted.contains(filePath)) {
                // ── 5. Scale to 640 px wide, convert to RGB24 for JPEG ───────
                int srcW   = frame.width(), srcH = frame.height();
                int thumbW = Math.min(640, srcW);
                int thumbH = Math.max(2, (int)(srcH * ((double) thumbW / srcW))) & ~1;

                SwsContext swsCtx = sws_getContext(srcW, srcH, frame.format(),
                                                   thumbW, thumbH, AV_PIX_FMT_RGB24,
                                                   SWS_BILINEAR, null, null, (DoublePointer) null);
                if (swsCtx != null) {
                    AVFrame dst = av_frame_alloc();
                    dst.format(AV_PIX_FMT_RGB24); dst.width(thumbW); dst.height(thumbH);
                    av_frame_get_buffer(dst, 1);
                    sws_scale(swsCtx, frame.data(), frame.linesize(), 0, srcH,
                              dst.data(), dst.linesize());

                    int    stride   = dst.linesize(0);
                    byte[] rgbBytes = new byte[thumbH * stride];
                    dst.data(0).get(rgbBytes);

                    // Pack into TYPE_INT_RGB to avoid Java ImageIO's known BGR channel-swap bug.
                    BufferedImage bi = new BufferedImage(thumbW, thumbH, BufferedImage.TYPE_INT_RGB);
                    int[] pixels = new int[thumbW * thumbH];
                    for (int row = 0; row < thumbH; row++)
                        for (int col = 0; col < thumbW; col++) {
                            int base = row * stride + col * 3;
                            pixels[row * thumbW + col] = ((rgbBytes[base]   & 0xFF) << 16)
                                                       | ((rgbBytes[base+1] & 0xFF) << 8)
                                                       |  (rgbBytes[base+2] & 0xFF);
                        }
                    bi.getRaster().setDataElements(0, 0, thumbW, thumbH, pixels);
                    javax.imageio.ImageIO.write(bi, "jpg", out.toFile());

                    sws_freeContext(swsCtx);
                    av_frame_free(dst);

                    if (Files.exists(out))
                        result = new Image(out.toUri().toString(), LOAD_W, LOAD_H, true, true, false);
                }
            }

            av_frame_free(frame);
            av_packet_free(pkt);
            avcodec_free_context(codecCtx);
            avformat_close_input(fmtCtx);
            return result;
        } catch (Exception e) {
            System.err.println("[thumb] failed to generate thumbnail for " + file.getFilename() + ": " + e.getMessage());
        }
        return null;
    }

    // ── Context menu ─────────────────────────────────────────────────────────

    private ContextMenu buildContextMenu(int idx, MediaFile item) {
        ContextMenu cm = new ContextMenu();

        // ── Batch operations (shown when 2+ files are queued) ─────────────────
        // The slideshow queue doubles as multi-file selection for bulk ops.
        if (queuePositions.size() >= 2) {
            int n = queuePositions.size();
            List<Integer> queueIdxs = new ArrayList<>(queuePositions.keySet());
            Collections.sort(queueIdxs);

            MenuItem batchDeleteItem = new MenuItem("🗑  Delete " + n + " queued files…");
            batchDeleteItem.setOnAction(e -> onBatchDelete.accept(queueIdxs));

            MenuItem batchRenameItem = new MenuItem("✏  Rename " + n + " queued files…");
            batchRenameItem.setOnAction(e -> onBatchRename.accept(queueIdxs));

            cm.getItems().addAll(batchDeleteItem, batchRenameItem, new SeparatorMenuItem());
        }

        // ── Single-file operations ────────────────────────────────────────────
        MenuItem revealItem = new MenuItem("📂  Reveal in Explorer");
        revealItem.setOnAction(e -> {
            onReveal.accept(idx);
            revealInExplorer(item.getPath());
        });

        MenuItem renameItem = new MenuItem("✏  Rename…");
        renameItem.setOnAction(e -> onRename.accept(idx));

        MenuItem starItem = new MenuItem(item.isStarred() ? "☆  Unstar" : "⭐  Star");
        starItem.setOnAction(e -> onStar.accept(idx));

        MenuItem copyToItem = new MenuItem("📋  Copy to…");
        copyToItem.setOnAction(e -> onCopyTo.accept(idx));

        MenuItem moveToItem = new MenuItem("✂  Move to…");
        moveToItem.setOnAction(e -> onMoveTo.accept(idx));

        MenuItem compareItem = new MenuItem("⚔  Load into Compare Pane");
        compareItem.setOnAction(e -> onAltSelect.accept(idx));

        boolean inQueue = queuePositions.containsKey(idx);
        MenuItem queueItem = new MenuItem(inQueue ? "✕  Remove from Queue" : "➕  Add to Queue");
        queueItem.setOnAction(e -> onQueueToggle.accept(idx));

        cm.getItems().addAll(revealItem, renameItem, starItem,
                new SeparatorMenuItem(), copyToItem, moveToItem,
                new SeparatorMenuItem(), compareItem, queueItem);
        return cm;
    }

    // ── Sidecar thumbnail cache utilities ─────────────────────────────────────

    /**
     * Delete the sidecar thumbnail for {@code filePath} (if it exists).
     * Looks in {@code <parent>/thumbnails/<stem>.{jpg,jpeg,png,webp}}.
     */
    public static void deleteSidecarThumbnail(Path filePath) {
        Path parent = filePath.getParent();
        if (parent == null) return;
        String name = filePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        Path tDir = parent.resolve("thumbnails");
        for (String ext : List.of("jpg", "jpeg", "png", "webp")) {
            Path tp = tDir.resolve(stem + "." + ext);
            try { Files.deleteIfExists(tp); } catch (IOException ignored) {}
        }
    }

    /**
     * Rename the sidecar thumbnail when a file is renamed.
     * Moves {@code <parent>/thumbnails/<oldStem>.jpg} → {@code <parent>/thumbnails/<newStem>.jpg}.
     */
    public static void renameSidecarThumbnail(Path oldPath, Path newPath) {
        Path parent = oldPath.getParent();
        if (parent == null) return;
        String oldName = oldPath.getFileName().toString();
        String newName = newPath.getFileName().toString();
        int d1 = oldName.lastIndexOf('.'), d2 = newName.lastIndexOf('.');
        String oldStem = d1 >= 0 ? oldName.substring(0, d1) : oldName;
        String newStem = d2 >= 0 ? newName.substring(0, d2) : newName;
        Path tDir = parent.resolve("thumbnails");
        for (String ext : List.of("jpg", "jpeg", "png", "webp")) {
            Path oldThumb = tDir.resolve(oldStem + "." + ext);
            if (Files.exists(oldThumb)) {
                try { Files.move(oldThumb, tDir.resolve(newStem + "." + ext),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ignored) {}
                break; // only one sidecar per file
            }
        }
    }

    static void revealInExplorer(Path path) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                // /select, highlights the specific file in Explorer
                new ProcessBuilder("explorer", "/select,", path.toAbsolutePath().toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", path.toAbsolutePath().toString()).start();
            } else {
                File dir = path.getParent() != null ? path.getParent().toFile() : path.toFile();
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir);
            }
        } catch (IOException ex) {
            System.err.println("[reveal] " + ex.getMessage());
        }
    }

    // ── Natural sort ─────────────────────────────────────────────────────────

    /**
     * Natural-order string comparison: numeric runs are compared as integers so
     * "2" < "10" instead of "10" < "2" (lexicographic).
     */
    static int naturalCompare(String a, String b) {
        String sa = a.toLowerCase();
        String sb = b.toLowerCase();
        int ia = 0, ib = 0;
        while (ia < sa.length() && ib < sb.length()) {
            boolean da = Character.isDigit(sa.charAt(ia));
            boolean db = Character.isDigit(sb.charAt(ib));
            if (da && db) {
                int ea = ia, eb = ib;
                while (ea < sa.length() && Character.isDigit(sa.charAt(ea))) ea++;
                while (eb < sb.length() && Character.isDigit(sb.charAt(eb))) eb++;
                long na = Long.parseLong(sa.substring(ia, ea));
                long nb = Long.parseLong(sb.substring(ib, eb));
                if (na != nb) return Long.compare(na, nb);
                ia = ea; ib = eb;
            } else {
                int cmp = Character.compare(sa.charAt(ia), sb.charAt(ib));
                if (cmp != 0) return cmp;
                ia++; ib++;
            }
        }
        return Integer.compare(sa.length() - ia, sb.length() - ib);
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");

    static String formatFileSize(long bytes) {
        if (bytes <= 0)            return "Unknown size";
        if (bytes < 1_024)         return bytes + " B";
        if (bytes < 1_048_576)     return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    static String formatDate(long epochMillis) {
        if (epochMillis <= 0) return "Unknown date";
        LocalDateTime dt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return DATE_FMT.format(dt);
    }

    // ── Thumb task wrapper ────────────────────────────────────────────────────

    /**
     * Wraps a thumbnail-load Runnable with its target Path so the discard-oldest
     * rejection handler can remove the path from {@code submitted} when the task
     * is evicted from the bounded queue.
     */
    private static final class ThumbTask implements Runnable {
        final Path path;
        private final Runnable work;
        ThumbTask(Path path, Runnable work) { this.path = path; this.work = work; }
        @Override public void run() { work.run(); }
    }

    // ── Custom list cell ──────────────────────────────────────────────────────

    private class ThumbnailCell extends ListCell<MediaFile> {

        private final ImageView  thumbView  = new ImageView();
        private final StackPane  imgStack   = new StackPane();
        private final VBox       cellBox    = new VBox(0);

        // Slideshow queue position badge (shown top-right corner of thumbnail)
        private final Label      queueBadge = new Label();

        // Video play badge (centred over thumbnail for video files)
        private final Label      playBadge  = new Label("▶");

        // Audio indicator — small dot at bottom-right (green = audio, grey = silent)
        private final Label      audioBadge = new Label();

        // Star badge — gold ★ at top-left when file is starred
        private final Label      starBadge  = new Label("★");

        // Batch-select overlay — blue tint + checkmark badge (top-left)
        private final Rectangle  batchTint  = new Rectangle(THUMB_W, THUMB_H,
                javafx.scene.paint.Color.web("#4a90d9", 0.30));
        private final Label      batchCheck = new Label("✓");

        // Tooltip — shows filename, size, date on hover
        private final Tooltip    cellTooltip = new Tooltip();

        ThumbnailCell() {
            thumbView.setFitWidth(THUMB_W);
            thumbView.setFitHeight(THUMB_H);
            thumbView.setPreserveRatio(true);
            thumbView.setSmooth(true);

            Rectangle bg = new Rectangle(THUMB_W, THUMB_H, Color.web("#2a2a2a"));

            // Queue badge — small orange pill at top-right
            queueBadge.setStyle(
                    "-fx-background-color: #f5a623; -fx-text-fill: white; " +
                    "-fx-font-size: 9px; -fx-font-weight: bold; " +
                    "-fx-padding: 1 5 1 5; -fx-background-radius: 8;");
            queueBadge.setVisible(false);
            StackPane.setAlignment(queueBadge, Pos.TOP_RIGHT);
            StackPane.setMargin(queueBadge, new Insets(3, 3, 0, 0));

            // Video play badge — centred, semi-transparent circle
            playBadge.setStyle(
                    "-fx-text-fill: rgba(255,255,255,0.90); -fx-font-size: 18px; " +
                    "-fx-background-color: rgba(0,0,0,0.42); " +
                    "-fx-background-radius: 50; -fx-padding: 3 7 3 9;");
            playBadge.setVisible(false);
            playBadge.setMouseTransparent(true);
            StackPane.setAlignment(playBadge, Pos.CENTER);

            // Audio dot badge — bottom-right, hidden until probe completes
            audioBadge.setStyle(
                    "-fx-min-width: 8px; -fx-min-height: 8px; " +
                    "-fx-max-width: 8px; -fx-max-height: 8px; " +
                    "-fx-background-radius: 50; -fx-background-color: transparent;");
            audioBadge.setVisible(false);
            audioBadge.setMouseTransparent(true);
            StackPane.setAlignment(audioBadge, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(audioBadge, new Insets(0, 4, 4, 0));

            // Star badge — gold ★ shown when file is starred (bottom-left)
            starBadge.setStyle("-fx-text-fill: #ffd700; -fx-font-size: 13px; " +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 2, 0, 0, 1);");
            starBadge.setVisible(false);
            starBadge.setMouseTransparent(true);
            StackPane.setAlignment(starBadge, Pos.BOTTOM_LEFT);
            StackPane.setMargin(starBadge, new Insets(0, 0, 3, 4));

            batchTint.setVisible(false);
            batchTint.setMouseTransparent(true);

            batchCheck.setStyle(
                    "-fx-background-color: #4a90d9; -fx-text-fill: white; " +
                    "-fx-font-size: 10px; -fx-font-weight: bold; " +
                    "-fx-padding: 1 5 1 5; -fx-background-radius: 8;");
            batchCheck.setVisible(false);
            batchCheck.setMouseTransparent(true);
            StackPane.setAlignment(batchCheck, Pos.TOP_LEFT);
            StackPane.setMargin(batchCheck, new Insets(3, 0, 0, 3));

            imgStack.getChildren().addAll(bg, thumbView, playBadge, queueBadge, audioBadge,
                    starBadge, batchTint, batchCheck);

            // Tooltip installed once; text updated in updateItem()
            cellTooltip.setStyle("-fx-font-size: 11px;");
            cellTooltip.setShowDelay(Duration.millis(500));
            Tooltip.install(cellBox, cellTooltip);
            imgStack.setAlignment(Pos.CENTER);

            cellBox.getChildren().add(imgStack);
            cellBox.setAlignment(Pos.CENTER);
            cellBox.setPadding(new Insets(4, 6, 4, 6));

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPadding(Insets.EMPTY);

            // Right-click → context menu (Modifier-key clicks handled by ListView filter)
            cellBox.setOnContextMenuRequested(e -> {
                MediaFile item = getItem();
                if (item == null) return;
                Integer displayIdx = displayIndexByPath.get(item.getPath());
                if (displayIdx != null) {
                    int origIdx = toOriginal(displayIdx);
                    buildContextMenu(origIdx, item).show(cellBox, e.getScreenX(), e.getScreenY());
                }
                e.consume();
            });
        }

        @Override
        protected void updateItem(MediaFile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); return; }

            // Video play badge
            playBadge.setVisible(item.isVideo());

            // Audio indicator dot (only for videos; hidden until probe result is known)
            Boolean ha = item.getHasAudio();
            if (item.isVideo() && ha != null) {
                String color = ha ? "#4caf50" : "#888888";   // green = audio, grey = silent
                audioBadge.setStyle(
                        "-fx-min-width: 8px; -fx-min-height: 8px; " +
                        "-fx-max-width: 8px; -fx-max-height: 8px; " +
                        "-fx-background-radius: 50; -fx-background-color: " + color + ";");
                audioBadge.setVisible(true);
            } else {
                audioBadge.setVisible(false);
            }

            // Queue badge — map display position → original file-list index
            Integer qPos = queuePositions.get(toOriginal(getIndex()));
            if (qPos != null) {
                queueBadge.setText(String.valueOf(qPos));
                queueBadge.setVisible(true);
            } else {
                queueBadge.setVisible(false);
            }

            // Star badge
            starBadge.setVisible(item.isStarred());

            // Batch-select overlay — shown when this item is queued AND 2+ items are queued
            // (signals "this file will be affected by batch delete/rename")
            boolean inBatch = queuePositions.size() >= 2 && queuePositions.containsKey(toOriginal(getIndex()));
            batchTint.setVisible(inBatch);
            batchCheck.setVisible(inBatch);

            // Tooltip text
            cellTooltip.setText(item.getFilename()
                    + "\n" + formatFileSize(item.getFileSize())
                    + "\n" + formatDate(item.getLastModified()));

            Image thumb = item.getThumbnail();
            if (thumb != null) {
                thumbView.setImage(thumb);
            } else {
                thumbView.setImage(null);
                requestThumbnailLoad(item);
            }
            setGraphic(cellBox);
        }

    }
}

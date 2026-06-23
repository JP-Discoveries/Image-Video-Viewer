package com.imageviewer;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.*;
import javafx.util.Duration;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Main application window.
 *
 * Layout — single mode
 * ─────────────────────
 *  ┌────────────────────────────────────────────────────────────────────────┐
 *  │ [«] [Open] [◀][▶] [Fit][1:1][+][−][↻R][↺L] [⚔] [⏵][5s⬆] [🎨][⚙][?][⛶]│
 *  ├──────────────┬─────────────────────────────────────────────────────────┤
 *  │  Thumbnails  │         MediaPane (main)                                │
 *  ├──────────────┴─────────────────────────────────────────────────────────┤
 *  │  [1/42]  filename.jpg  |  1920×1080  |  Zoom: 87%  [====progress====] │
 *  └────────────────────────────────────────────────────────────────────────┘
 *
 * Keyboard shortcuts
 * ──────────────────
 *  ←/→ / PgUp/Dn   prev/next (main pane)
 *  Alt+←/→         prev/next (compare pane, compare mode only)
 *  Ctrl+←/→        same as Alt+←/→ (legacy alias)
 *  Alt+click thumb  load into compare pane (enters compare mode if needed)
 *  Ctrl+click thumb toggle in slideshow queue
 *  Space            play/pause video (routes to whichever pane the mouse is over)
 *  +/-/0            zoom in/out/fit
 *  R                rotate right 90°
 *  L                rotate left 90°
 *  F / F11          full-screen toggle
 *  T                cycle theme
 *  C                toggle compare mode
 *  Home/End         first/last file
 *  O / Ctrl+O       open folder
 *  Ctrl+S           slideshow toggle
 *  Escape           exit full-screen
 *  ?                shortcuts help dialog
 */
public class MainWindow {

    // ── Core ──────────────────────────────────────────────────────────────────
    private final Stage        stage;
    private final AppConfig    config;
    private final ThemeManager theme;

    // ── UI nodes ─────────────────────────────────────────────────────────────
    private final Scene          scene;
    private final BorderPane     root;
    private final ToolBar        toolbar;
    private final HBox           statusBar;
    private final ThumbnailPanel thumbPanel;
    private final MediaPane      mediaPane;
    private final MediaPane      comparePane;
    private final Label          statusLabel;
    private final Label          idxLabel;
    private final SplitPane      splitPane;
    private final StackPane      contentArea;
    private       ToggleButton   compareBtn;
    private       Button         slideshowBtn;
    private       Spinner<Integer> slideshowSpinner;
    private       ProgressBar    slideshowProgress;
    private       Button         clearQueueBtn;
    private       VBox           syncControlsBar;
    private       Button         syncPlayPauseBtn;
    private       ToggleButton   syncLoopBtn;

    // ── File list ─────────────────────────────────────────────────────────────
    private final List<MediaFile> files     = new ArrayList<>();
    private int                   currentIdx = -1;

    // ── Compare mode ─────────────────────────────────────────────────────────
    private boolean    compareMode = false;
    private int        compareIdx  = -1;
    private MediaPane  activePane;

    // ── Pre-maximize window bounds (UNDECORATED doesn't auto-restore on Windows) ──
    /** Tolerance (px) for detecting that saved restore bounds are "stale" (basically fullscreen). */
    private static final int STALE_RESTORE_TOLERANCE = 20;
    private double preMaxX, preMaxY, preMaxW, preMaxH;

    // ── Compare split pane ────────────────────────────────────────────────────
    /** The inner SplitPane created by {@link #enterCompareMode()}. Null when compare is off. */
    private SplitPane compareSplitPane  = null;
    /** Last-known divider position for the compare split; preserved across fullscreen/maximize. */
    private double    compareDivPos     = 0.5;
    /** Auto-hide timer for the sync controls overlay (restarted on every mouse move). */
    private Timeline  syncHideTimer     = null;

    // ── Dual-display (presentation) mode ─────────────────────────────────────
    private Stage        presentationStage = null;
    private MediaPane    presentationPane  = null;
    private boolean      dualDisplayMode   = false;
    private ToggleButton dualDisplayBtn;

    // ── Slideshow ─────────────────────────────────────────────────────────────
    private Timeline      slideshowTimer;
    private boolean       slideshowRunning  = false;
    private double        slideshowElapsed  = 0;   // seconds elapsed in current interval
    private Timeline      slideshowProgressTimer;  // drives the progress bar animation
    private boolean       slideshowShuffle  = false;
    private boolean       slideshowLoop     = true;   // true = loop forever; false = stop after one pass
    private ToggleButton  shuffleBtn;
    private ToggleButton  loopBtn;
    /** Pre-shuffled deck of file indices (or queue positions). Rebuilt each pass. */
    private List<Integer> shuffleOrder      = new ArrayList<>();
    private int           shufflePos        = 0;

    // ── Slideshow queue ───────────────────────────────────────────────────────
    private final List<Integer>         slideshowQueue    = new ArrayList<>();
    private final Map<Integer, Integer> queuePositions    = new LinkedHashMap<>();
    private int                         slideshowQueueIdx = 0;
    /** Persisted between thumbnail-browser opens so sort state survives close/reopen. */
    private int     browserSortMode = 0;
    private boolean browserSortAsc  = true;
    /** Non-null while the thumbnail browser is open; null after it closes. */
    private ThumbnailBrowserDialog openBrowserDialog = null;

    // ── Custom title bar ──────────────────────────────────────────────────────
    private HBox  titleBar;
    private VBox  topArea;          // titleBar stacked above toolbar
    private Label folderLabel;      // bottom-right: "FolderName / filename.ext"
    // Window drag state
    private double dragOffsetX, dragOffsetY;
    // Window resize state
    private static final int RESIZE_MARGIN = 6;
    private boolean resizing     = false;
    private String  resizeDir    = "";
    private double  rsStartSX, rsStartSY;
    private double  rsStartWX, rsStartWY, rsStartWW, rsStartWH;

    // ── Fullscreen / sidebar state ────────────────────────────────────────────
    private static final double SIDEBAR_PRESET_W  = 215.0; // pixels — preset width after open/expand
    private double   fullscreenSavedDivider = 0.15;
    private boolean  sidebarCollapsed       = false;
    private Timeline cursorHideTimer;

    // ── Drag & Drop overlay ───────────────────────────────────────────────────
    private StackPane sceneRoot;
    private VBox      dropOverlay;

    // ── File system watcher ───────────────────────────────────────────────────
    private WatchService               watchService;
    private List<Path>                 watchedFolders = new ArrayList<>();
    private final ScheduledExecutorService watchExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fs-watcher"); t.setDaemon(true); return t;
            });
    private ScheduledFuture<?>         watchRescanFuture;

    // ── Constructor ───────────────────────────────────────────────────────────
    /** Command-line arguments passed at launch (e.g. a path from "Open with"). */
    private final List<String> launchArgs;

    public MainWindow(Stage stage, AppConfig config, ThemeManager theme, List<String> launchArgs) {
        this.stage      = stage;
        this.config     = config;
        this.theme      = theme;
        this.launchArgs = launchArgs;

        // Remove OS title bar so we can draw our own themed chrome
        stage.initStyle(StageStyle.UNDECORATED);

        mediaPane   = new MediaPane();
        comparePane = new MediaPane();
        activePane  = mediaPane;

        mediaPane.setOnStatusUpdate(this::setStatus);
        mediaPane.setOnFullscreenRequest(this::toggleFullScreen);
        comparePane.setOnStatusUpdate(s -> {});
        comparePane.setOnFullscreenRequest(this::toggleFullScreen);

        // Initialise from config
        mediaPane.setLoopEnabled(config.loopVideo);
        comparePane.setLoopEnabled(config.loopVideo);
        applyTransitionModeFromConfig();
        MediaPane.setRawDecodeMode(config.rawDecodeMode);
        mediaPane.setAlphaCheckerboard(config.alphaCheckerboard);
        comparePane.setAlphaCheckerboard(config.alphaCheckerboard);

        // Route Space to whichever pane the mouse is over
        mediaPane.setOnMouseEntered(e   -> activePane = mediaPane);
        comparePane.setOnMouseEntered(e -> activePane = comparePane);

        // End-of-media callback for video-aware slideshow
        mediaPane.setOnEndOfMedia(this::onVideoEnded);
        mediaPane.setOnFrameCapture(this::showFrameCaptureDialog);
        mediaPane.setOnCopyImageToClipboard(this::copyCurrentImageToClipboard);
        // Update sync-controls button when compare pane video ends
        comparePane.setOnEndOfMedia(() -> { if (compareMode) updateSyncPlayPauseBtn(); });

        // Save/clear video resume positions in config
        mediaPane.setOnSavePosition((path, ms) -> {
            if (ms <= 0) config.videoPositions.remove(path.toString());
            else         config.videoPositions.put(path.toString(), ms);
            config.save();
        });

        // Bookmark callbacks
        mediaPane.setOnGetBookmarks(path ->
                config.videoBookmarks.getOrDefault(path, new java.util.ArrayList<>()));
        mediaPane.setOnAddBookmark((path, name, ms) -> {
            config.videoBookmarks.computeIfAbsent(path, k -> new java.util.ArrayList<>())
                    .add(new AppConfig.VideoBookmark(name, ms));
            config.save();
        });
        mediaPane.setOnDeleteBookmark((path, name) -> {
            java.util.List<AppConfig.VideoBookmark> bms = config.videoBookmarks.get(path);
            if (bms != null) {
                bms.removeIf(bm -> bm.name.equals(name));
                if (bms.isEmpty()) config.videoBookmarks.remove(path);
            }
            config.save();
        });

        thumbPanel = new ThumbnailPanel();
        thumbPanel.setPrefWidth(config.thumbnailPanelWidth);
        thumbPanel.setMinWidth(190);   // ThumbnailPanel.THUMB_W=170 + ~10 px padding each side
        thumbPanel.setMaxWidth(360);   // stage.minWidth(520) - maxThumb(360) - divider(8) = 152 px min for contentArea
        thumbPanel.setOnSelect(this::navigateTo);
        thumbPanel.setOnRename(this::renameFile);
        thumbPanel.setOnBatchDelete(this::batchDeleteFiles);
        thumbPanel.setOnBatchRename(this::batchRenameFiles);
        thumbPanel.setOnStar(this::toggleStar);
        thumbPanel.setOnCopyTo(this::copyFileTo);
        thumbPanel.setOnMoveTo(this::moveFileTo);
        thumbPanel.setOnQueueToggle(this::addToSlideshowQueue);
        thumbPanel.setOnQueueRange(range -> {
            for (int idx : range) {
                if (!slideshowQueue.contains(idx)) slideshowQueue.add(idx);
            }
            rebuildQueuePositions();
            setStatus(String.format("Added range to queue — %d file%s queued  (Shift+click to add ranges)",
                    slideshowQueue.size(), slideshowQueue.size() == 1 ? "" : "s"));
        });
        thumbPanel.setOnBrowse(this::openThumbnailBrowser);
        thumbPanel.setOnSortFilterChanged(this::refreshIdxLabel);
        thumbPanel.setOnAltSelect(idx -> {
            if (!files.isEmpty() && idx >= 0 && idx < files.size()) {
                if (compareMode && idx == compareIdx) {
                    toggleCompareMode();
                } else {
                    boolean wasInCompare = compareMode;
                    if (!compareMode) enterCompareMode();
                    MediaFile mf = files.get(idx);
                    if (wasInCompare) {
                        loadIntoPane(mf, comparePane, idx);
                    } else {
                        // Defer until after the SplitPane layout pass so MediaView has a size
                        Platform.runLater(() -> loadIntoPane(mf, comparePane, idx));
                    }
                }
            }
        });

        contentArea = new StackPane(mediaPane);
        contentArea.setMinWidth(150); // outer SplitPane must always leave room for content
        syncControlsBar = buildSyncControlsBar();
        syncControlsBar.setVisible(false);
        StackPane.setAlignment(syncControlsBar, Pos.CENTER_LEFT);
        StackPane.setMargin(syncControlsBar, Insets.EMPTY);
        contentArea.getChildren().add(syncControlsBar);

        splitPane = new SplitPane(thumbPanel, contentArea);
        SplitPane.setResizableWithParent(thumbPanel, false);
        // Defer divider restore until the SplitPane has a real width.
        // Calling setDividerPositions before the first layout pass produces
        // an unreliable result — JavaFX overwrites it during initial layout.
        final double savedThumbPx = config.thumbnailPanelWidth;
        ChangeListener<Number> dividerRestoreListener = new ChangeListener<>() {
            @Override public void changed(ObservableValue<? extends Number> obs, Number oldW, Number newW) {
                double w = newW.doubleValue();
                if (w > 0) {
                    splitPane.widthProperty().removeListener(this);
                    // Clamp upward so an old too-narrow saved value never clips thumbnails
                    double px = Math.max(savedThumbPx, thumbPanel.getMinWidth());
                    splitPane.setDividerPositions(px / w);
                }
            }
        };
        splitPane.widthProperty().addListener(dividerRestoreListener);

        // ── Status bar ────────────────────────────────────────────────────────
        // Left: folder name (fixed width, muted)
        folderLabel = new Label();
        folderLabel.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #888; -fx-padding: 0 6 0 0;");
        folderLabel.setMaxWidth(260);

        // Centre: media info from MediaPane (filename | dims | size | zoom)
        statusLabel = new Label("No folder open — use 📂 Open or drag a folder here");

        // File index — sits directly after the media info
        idxLabel = new Label();
        idxLabel.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #aaa; -fx-padding: 0 0 0 6;");

        // Spacer pushes progress bar to the far right
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);

        slideshowProgress = new ProgressBar(0);
        slideshowProgress.setPrefWidth(120);
        slideshowProgress.setMaxHeight(8);
        slideshowProgress.setVisible(false);
        slideshowProgress.getStyleClass().add("slideshow-progress");

        statusBar = new HBox(6, folderLabel, statusLabel, idxLabel, statusSpacer, slideshowProgress);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(3, 10, 3, 10));

        toolbar  = buildToolBar();
        titleBar = buildTitleBar();
        topArea  = new VBox(0, titleBar, toolbar);

        root = new BorderPane();
        root.setTop(topArea);
        root.setCenter(splitPane);
        root.setBottom(statusBar);

        dropOverlay = buildDropOverlay();
        sceneRoot = new StackPane(root, dropOverlay);
        scene = new Scene(sceneRoot, config.windowWidth, config.windowHeight);
        scene.setFill(javafx.scene.paint.Color.web("#141414")); // prevent white bleed-through
        if (!ThemeManager.THEME_NAMES.contains(config.theme)) config.theme = "Dark";
        theme.applyTheme(scene, config.theme);

        // Enforce minimum window size so the custom title bar / toolbar never overflow
        stage.setMinWidth(520);
        stage.setMinHeight(360);

        stage.setTitle("Image & Video Viewer");
        stage.setScene(scene);
        stage.getIcons().add(makeStageIcon());
        if (config.windowX >= 0 && config.windowY >= 0) {
            stage.setX(config.windowX);
            stage.setY(config.windowY);
        }

        // Seed pre-maximize bounds from config.  saveConfig() always writes the
        // pre-maximize size, so this is reliable even when the app was closed maximized.
        preMaxX = config.windowX >= 0 ? config.windowX : -1;   // -1 = use screen centre
        preMaxY = config.windowY >= 0 ? config.windowY : -1;
        preMaxW = config.windowWidth;
        preMaxH = config.windowHeight;

        // Keep preMax* current whenever the window is moved/resized while not maximized.
        ChangeListener<Number> trackBounds = (obs, old, nv) -> {
            if (!stage.isMaximized() && !stage.isFullScreen()) {
                preMaxX = stage.getX(); preMaxY = stage.getY();
                preMaxW = stage.getWidth(); preMaxH = stage.getHeight();
            }
        };
        stage.xProperty().addListener(trackBounds);
        stage.yProperty().addListener(trackBounds);
        stage.widthProperty().addListener(trackBounds);
        stage.heightProperty().addListener(trackBounds);
        // Repaint newly-exposed pixels when resized externally (Windows Snap, maximise, etc.)
        stage.widthProperty().addListener((obs, o, n)  -> scene.getRoot().requestLayout());
        stage.heightProperty().addListener((obs, o, n) -> scene.getRoot().requestLayout());

        if (config.windowMaximized) stage.setMaximized(true);

        wireFullScreen();
        // Re-apply compare divider when the window is maximized or restored,
        // because the layout change can shift the inner SplitPane's computed sizes.
        // Also manually restore pre-maximize bounds: UNDECORATED stages on Windows
        // do not auto-restore their size/position when setMaximized(false) is called.
        stage.maximizedProperty().addListener((obs, was, isNow) -> {
            if (compareMode && compareSplitPane != null) {
                final double cpos = compareDivPos;
                Platform.runLater(() -> compareSplitPane.setDividerPositions(cpos));
            }
        });
        wireKeyboard();
        wireDragDrop();
        wireWindowClose();
        wireWindowDrag();
        // Any mouse movement over the content area re-shows the sync controls bar
        // (only when videos are present — refreshSyncControlsVisibility checks this).
        contentArea.addEventFilter(MouseEvent.MOUSE_MOVED,   e -> { if (compareMode) refreshSyncControlsVisibility(); });
        contentArea.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> { if (compareMode) refreshSyncControlsVisibility(); });
        wireWindowResize();
        restoreLastFolder();
    }

    public void show() { stage.show(); }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private ToolBar buildToolBar() {
        // Sidebar collapse
        Button sidebarBtn = new Button("«");
        sidebarBtn.setTooltip(new Tooltip("Collapse/expand sidebar"));
        sidebarBtn.getStyleClass().add("toolbar-btn");
        sidebarBtn.setOnAction(e -> toggleSidebar());

        Button openBtn    = toolBtn("📂 Open",  "Open folder  (O)",  this::openFolderDialog);
        Button clearBtn   = toolBtn("✕ Clear",   "Close all files and go to empty state", this::clearAllFiles);
        Button prevBtn    = toolBtn("◀",         "Previous  (←)",          this::prevFile);
        Button nextBtn    = toolBtn("▶",         "Next  (→)",              this::nextFile);
        Button fitBtn     = toolBtn("Fit",        "Zoom to fit  (0)",       () -> mediaPane.zoomFit());
        Button actualBtn  = toolBtn("1:1",        "Actual size",            () -> mediaPane.zoomActual());
        Button zoomInBtn  = toolBtn("+",          "Zoom in  (+)",           () -> mediaPane.zoomIn());
        Button zoomOutBtn = toolBtn("−",          "Zoom out  (−)",          () -> mediaPane.zoomOut());
        Button rotRBtn    = toolBtn("↻",          "Rotate right 90°  (R)",  () -> mediaPane.rotateRight());
        Button rotLBtn    = toolBtn("↺",          "Rotate left 90°  (L)",   () -> mediaPane.rotateLeft());

        compareBtn = new ToggleButton("⚔ Compare");
        compareBtn.setTooltip(new Tooltip(
                "Side-by-side compare mode  (C)\n" +
                "Alt+click thumbnail → load into right pane\n" +
                "Ctrl+click thumbnail → toggle slideshow queue\n" +
                "Alt+← / Alt+→ → navigate right pane"));
        compareBtn.getStyleClass().add("toolbar-btn");
        compareBtn.setOnAction(e -> toggleCompareMode());

        slideshowBtn = toolBtn("⏵ Slideshow",
                "Start/stop slideshow  (Ctrl+S)\nCtrl+click thumbnails to build a queue",
                this::toggleSlideshow);

        // Interval spinner (always visible, persists to config)
        slideshowSpinner = new Spinner<>(1, 300, config.slideshowInterval, 1);
        slideshowSpinner.setPrefWidth(64);
        slideshowSpinner.setTooltip(new Tooltip("Slideshow interval (seconds)"));
        slideshowSpinner.getStyleClass().add("split-arrows-horizontal");
        slideshowSpinner.valueProperty().addListener((obs, o, n) -> {
            config.slideshowInterval = n;
            if (slideshowRunning) restartSlideshowTimer();
        });

        // Shuffle toggle
        shuffleBtn = new ToggleButton("🔀");
        shuffleBtn.setTooltip(new Tooltip("Shuffle slideshow order"));
        shuffleBtn.getStyleClass().add("toolbar-btn");
        // Loop toggle
        loopBtn = new ToggleButton("🔁");
        loopBtn.setSelected(true);   // loop on by default
        loopBtn.setTooltip(new Tooltip("Loop slideshow — repeat after last file"));
        loopBtn.getStyleClass().add("toolbar-btn");
        loopBtn.setOnAction(e -> {
            slideshowLoop = loopBtn.isSelected();
            setStatus(slideshowLoop ? "Slideshow loop ON" : "Slideshow loop OFF");
        });

        shuffleBtn.setOnAction(e -> {
            slideshowShuffle = shuffleBtn.isSelected();
            shuffleOrder.clear();   // force deck rebuild on next advance
            setStatus(slideshowShuffle ? "Shuffle ON" : "Shuffle OFF");
        });

        // Queue buttons
        clearQueueBtn = toolBtn("✕ Queue", "Clear slideshow queue  (clears all queued files)",
                this::clearSlideshowQueue);
        Button queueMgrBtn = toolBtn("📋 Manage Queue", "Open queue manager — reorder and remove queued files",
                this::openQueueManager);

        dualDisplayBtn = new ToggleButton("⊞");
        dualDisplayBtn.setTooltip(new Tooltip("Dual-display mode — send to second monitor  (D)"));
        dualDisplayBtn.getStyleClass().add("toolbar-btn");
        dualDisplayBtn.setOnAction(e -> toggleDualDisplay());

        Button settingsBtn = toolBtn("⚙ Settings", "Settings…",  this::openSettings);
        Button helpBtn  = toolBtn("?",         "Keyboard shortcuts  (?)", this::showShortcutsHelp);
        Button fsBtn    = toolBtn("⛶",          "Full-screen  (F)",        this::toggleFullScreen);

        // Single HBox inside the ToolBar so we control the layout (groups + spacer)
        HBox leftGroups = new HBox(5,
                toolGroup(sidebarBtn, openBtn, clearBtn),
                toolGroup(prevBtn, nextBtn),
                toolGroup(fitBtn, actualBtn, zoomInBtn, zoomOutBtn, rotRBtn, rotLBtn),
                toolGroup(compareBtn),
                toolGroup(slideshowBtn, slideshowSpinner, shuffleBtn, loopBtn, clearQueueBtn, queueMgrBtn)
        );
        leftGroups.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbarContent = new HBox(5, leftGroups, spacer, toolGroup(dualDisplayBtn, settingsBtn, helpBtn, fsBtn));
        toolbarContent.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(toolbarContent, Priority.ALWAYS);

        ToolBar bar = new ToolBar(toolbarContent);
        bar.getStyleClass().add("main-toolbar");
        return bar;
    }

    /**
     * Creates a dark UNDECORATED title bar using the app icon (camera).
     * Use the overload to pass a dialog-specific icon.
     */
    private HBox makeTitleBar(String title, Stage s) {
        Image appIcon = stage.getIcons().isEmpty() ? null : stage.getIcons().get(0);
        return makeTitleBar(title, s, appIcon);
    }

    /**
     * Creates a dark UNDECORATED title bar for a popup Stage — icon, title, draggable,
     * and a red-on-hover ✕ close button pinned to the right.
     *
     * @param dialogIcon dialog-specific icon (16×16); pass null to show no icon
     */
    private HBox makeTitleBar(String title, Stage s, Image dialogIcon) {
        HBox bar = new HBox(6);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 0, 5, 10));
        bar.setStyle("-fx-background-color: #1a1a1a; "
                + "-fx-border-color: transparent transparent #333 transparent;");

        if (dialogIcon != null) {
            ImageView icon = new ImageView(dialogIcon);
            icon.setFitWidth(16); icon.setFitHeight(16); icon.setPreserveRatio(true);
            bar.getChildren().add(icon);
        }

        Label lbl = new Label(title);
        lbl.setStyle("-fx-text-fill: #cccccc; -fx-font-weight: bold; -fx-font-size: 12px;");
        lbl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lbl, Priority.ALWAYS);
        bar.getChildren().add(lbl);

        String closeDflt  = "-fx-background-color: transparent; -fx-text-fill: #888; "
                + "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 10 2 10;";
        String closeHover = "-fx-background-color: #c42b1c; -fx-text-fill: white; "
                + "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 10 2 10;";
        Button closeBtn = new Button("✕");
        closeBtn.setStyle(closeDflt);
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(closeHover));
        closeBtn.setOnMouseExited(e  -> closeBtn.setStyle(closeDflt));
        closeBtn.setOnAction(e -> s.close());
        bar.getChildren().add(closeBtn);

        double[] drag = {0, 0};
        bar.setOnMousePressed(e -> { drag[0] = e.getScreenX(); drag[1] = e.getScreenY(); });
        bar.setOnMouseDragged(e -> {
            s.setX(s.getX() + e.getScreenX() - drag[0]);
            s.setY(s.getY() + e.getScreenY() - drag[1]);
            drag[0] = e.getScreenX(); drag[1] = e.getScreenY();
        });
        return bar;
    }

    /** Centre an undecorated dialog Stage over the owner window after it is shown. */
    private static void centerOnOwner(Stage dlg, Window owner) {
        if (owner == null) return;
        dlg.setX(owner.getX() + (owner.getWidth()  - dlg.getWidth())  / 2);
        dlg.setY(owner.getY() + (owner.getHeight() - dlg.getHeight()) / 2);
    }

    /** Standard bottom button bar for UNDECORATED dialogs. */
    private HBox makeDialogButtonBar(Node... buttons) {
        HBox bar = new HBox(8, buttons);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-border-color: #333 transparent transparent transparent;");
        return bar;
    }

    // ── Dialog icon builders (drawn programmatically at 16×16) ───────────────

    /** Gear/cog icon — used by the Settings dialog. */
    private static WritableImage buildSettingsIcon() {
        Canvas c = new Canvas(16, 16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#9eb8d4"));
        g.fillOval(1, 1, 14, 14);                    // outer gear ring
        g.setFill(Color.web("#1a1a1a"));
        g.fillRect(6,  0,  4, 2);                    // top notch
        g.fillRect(6, 14,  4, 2);                    // bottom notch
        g.fillRect(0,  6,  2, 4);                    // left notch
        g.fillRect(14, 6,  2, 4);                    // right notch
        g.fillOval(5,  5,  6, 6);                    // inner hole
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    /** Keyboard icon — used by the Keyboard Shortcuts dialog. */
    private static WritableImage buildKeyboardIcon() {
        Canvas c = new Canvas(16, 16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#7090b0"));
        g.fillRoundRect(0, 3, 16, 11, 2, 2);         // keyboard body
        g.setFill(Color.web("#1a1a1a"));
        for (int x = 1; x <= 12; x += 4) g.fillRoundRect(x, 5, 2, 2, 1, 1); // top row
        for (int x = 1; x <= 12; x += 4) g.fillRoundRect(x, 8, 2, 2, 1, 1); // mid row
        g.fillRoundRect(3, 11, 10, 2, 1, 1);         // spacebar
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    /** Ordered-list icon — used by the Queue Manager dialog. */
    private static WritableImage buildQueueIcon() {
        Canvas c = new Canvas(16, 16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#9eb8d4"));
        for (int row = 0; row < 4; row++) {
            int y = 2 + row * 4;
            g.fillOval(1, y, 2, 2);                  // bullet
            g.fillRoundRect(5, y, 10, 2, 1, 1);      // line
        }
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    /** Trash-can icon — used by the Delete File confirmation dialog. */
    private static WritableImage buildDeleteIcon() {
        Canvas c = new Canvas(16, 16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#e05050"));
        g.fillRoundRect(6, 1, 4, 3, 1, 1);           // handle
        g.fillRect(2, 3, 12, 2);                      // lid
        g.fillRoundRect(3, 6, 10, 9, 2, 2);           // body
        g.setFill(Color.web("#1a1a1a"));
        g.fillRect(6,  8, 1, 5);                      // stripe 1
        g.fillRect(8,  8, 1, 5);                      // stripe 2
        g.fillRect(10, 8, 1, 5);                      // stripe 3
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    private Button toolBtn(String text, String tooltip, Runnable action) {
        Button btn = new Button(text);
        btn.setTooltip(new Tooltip(tooltip));
        btn.getStyleClass().add("toolbar-btn");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    /** Wraps related toolbar nodes in a visually grouped container. */
    private HBox toolGroup(Node... nodes) {
        HBox g = new HBox(2, nodes);
        g.setAlignment(Pos.CENTER_LEFT);
        g.getStyleClass().add("toolbar-group");
        return g;
    }

    // ── Custom title bar ──────────────────────────────────────────────────────

    private HBox buildTitleBar() {
        // ── App icon (film frame shape) ───────────────────────────────────────
        Node icon = makeAppIcon(18);

        // Title is the flex element: it grows to fill space and shrinks to zero
        // (with ellipsis) as the window narrows, keeping the WM buttons always
        // anchored to the right and never clipped off-screen.
        Label title = new Label("Image & Video Viewer");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 0 0 0 6;");
        title.setMouseTransparent(true);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        // ── Window control buttons ────────────────────────────────────────────
        Button minBtn   = wmBtn("─",  false);
        Button maxBtn   = wmBtn("□",  false);
        Button closeBtn = wmBtn("✕",  true);

        minBtn.setOnAction(e   -> stage.setIconified(true));
        maxBtn.setOnAction(e   -> { if (stage.isMaximized()) restoreFromMaximized(); else stage.setMaximized(true); });
        closeBtn.setOnAction(e -> stage.close());
        stage.maximizedProperty().addListener((obs, was, isNow) ->
                maxBtn.setText(isNow ? "❐" : "□"));

        HBox bar = new HBox(0, icon, title, minBtn, maxBtn, closeBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(30);
        bar.setMinHeight(30);
        // Minimum = 3 WM buttons (46 px each) + icon + padding — prevents them from
        // being pushed off-screen when the window is narrowed quickly
        bar.setMinWidth(46 * 3 + 34);
        bar.getStyleClass().add("title-bar");
        bar.setPadding(new Insets(0, 0, 0, 10));
        return bar;
    }

    /** Creates the small film-frame app icon used in the title bar. */
    private static Node makeAppIcon(double size) {
        double w = size * 1.3, h = size;
        // Film body
        Rectangle body = new Rectangle(0, 0, w, h);
        body.setArcWidth(3); body.setArcHeight(3);
        body.setFill(Color.web("#4d78cc"));
        // Sprocket holes (top & bottom strips)
        double holeW = w * 0.12, holeH = h * 0.18, gap = (w - holeW * 4) / 5.0;
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane(body);
        for (int i = 0; i < 4; i++) {
            double x = gap + i * (holeW + gap);
            Rectangle top = new Rectangle(x, h * 0.04, holeW, holeH);
            top.setArcWidth(1); top.setArcHeight(1);
            top.setFill(Color.web("#2a2a2a"));
            Rectangle bot = new Rectangle(x, h * 0.78, holeW, holeH);
            bot.setArcWidth(1); bot.setArcHeight(1);
            bot.setFill(Color.web("#2a2a2a"));
            pane.getChildren().addAll(top, bot);
        }
        // Play triangle
        Polygon tri = new Polygon(w * 0.32, h * 0.24, w * 0.80, h * 0.50, w * 0.32, h * 0.76);
        tri.setFill(Color.WHITE);
        pane.getChildren().add(tri);
        pane.setPrefSize(w, h); pane.setMinSize(w, h); pane.setMaxSize(w, h);
        pane.setMouseTransparent(true);

        // Also set as stage icon (taskbar) on first call
        return pane;
    }

    /** Creates a window chrome button (minimize / maximize / close). */
    private static Button wmBtn(String text, boolean isClose) {
        Button btn = new Button(text);
        btn.setFocusTraversable(false);
        btn.setPrefSize(46, 30); btn.setMinSize(46, 30); btn.setMaxSize(46, 30);
        btn.getStyleClass().addAll("wm-btn", isClose ? "wm-close" : "");
        return btn;
    }

    /** Generates a 32×32 stage icon (film frame + play triangle) for the taskbar. */
    private static WritableImage makeStageIcon() {
        Canvas c = new Canvas(32, 32);
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(Color.web("#4d78cc"));
        gc.fillRoundRect(1, 1, 30, 30, 6, 6);
        // Sprocket holes
        gc.setFill(Color.web("#1a1a1a"));
        for (int i = 0; i < 4; i++) {
            gc.fillRoundRect(3 + i * 7, 2,  5, 4, 1, 1);
            gc.fillRoundRect(3 + i * 7, 26, 5, 4, 1, 1);
        }
        // Play triangle
        gc.setFill(Color.WHITE);
        gc.fillPolygon(new double[]{10, 24, 10}, new double[]{8, 16, 24}, 3);
        WritableImage img = new WritableImage(32, 32);
        c.snapshot(null, img);
        return img;
    }

    // ── Window drag (title bar) ───────────────────────────────────────────────

    private void wireWindowDrag() {
        titleBar.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                dragOffsetX = e.getSceneX();
                dragOffsetY = e.getSceneY();
            }
        });
        titleBar.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY || stage.isFullScreen()) return;
            if (stage.isMaximized()) {
                // Snap out of maximized; keep cursor above title bar.
                restoreFromMaximized();
                dragOffsetX = stage.getWidth() * (e.getSceneX() / scene.getWidth());
                dragOffsetY = e.getSceneY();
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        titleBar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                if (stage.isMaximized()) restoreFromMaximized(); else stage.setMaximized(true);
            }
        });
    }

    // ── Window resize (scene edges) ───────────────────────────────────────────

    private void wireWindowResize() {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (stage.isFullScreen() || stage.isMaximized() || resizing) return;
            resizeDir = resizeDirAt(e.getSceneX(), e.getSceneY());
            applyCursorForResize(resizeDir);
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || stage.isFullScreen() || stage.isMaximized()) return;
            String dir = resizeDirAt(e.getSceneX(), e.getSceneY());
            if (!dir.isEmpty()) {
                resizing = true; resizeDir = dir;
                rsStartSX = e.getScreenX(); rsStartSY = e.getScreenY();
                rsStartWX = stage.getX();   rsStartWY = stage.getY();
                rsStartWW = stage.getWidth();rsStartWH = stage.getHeight();
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!resizing) return;
            double dx = e.getScreenX() - rsStartSX, dy = e.getScreenY() - rsStartSY;
            double nx = rsStartWX, ny = rsStartWY, nw = rsStartWW, nh = rsStartWH;
            // Clamp to the same limits enforced by stage.setMinWidth/Height so that nx/ny
            // are always calculated for the actual clamped size — prevents X drift.
            if (resizeDir.contains("E")) nw = Math.max(520, rsStartWW + dx);
            if (resizeDir.contains("S")) nh = Math.max(360, rsStartWH + dy);
            if (resizeDir.contains("W")) { nw = Math.max(520, rsStartWW - dx); nx = rsStartWX + (rsStartWW - nw); }
            if (resizeDir.contains("N")) { nh = Math.max(360, rsStartWH - dy); ny = rsStartWY + (rsStartWH - nh); }
            stage.setX(nx); stage.setY(ny); stage.setWidth(nw); stage.setHeight(nh);
            // Force JavaFX to repaint newly-exposed pixels on Windows (prevents black-box artifact)
            scene.getRoot().requestLayout();
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizing) {
                resizing = false; resizeDir = ""; scene.setCursor(Cursor.DEFAULT);
                scene.getRoot().requestLayout();
            }
        });
    }

    private String resizeDirAt(double x, double y) {
        double w = scene.getWidth(), h = scene.getHeight();
        boolean n = y < RESIZE_MARGIN, s = y > h - RESIZE_MARGIN;
        boolean we = x < RESIZE_MARGIN, e = x > w - RESIZE_MARGIN;
        if (n && we) return "NW"; if (n && e) return "NE";
        if (s && we) return "SW"; if (s && e) return "SE";
        if (n) return "N"; if (s) return "S"; if (we) return "W"; if (e) return "E";
        return "";
    }

    private void applyCursorForResize(String dir) {
        scene.setCursor(switch (dir) {
            case "N", "S"   -> Cursor.V_RESIZE;
            case "E", "W"   -> Cursor.H_RESIZE;
            case "NW", "SE" -> Cursor.NW_RESIZE;
            case "NE", "SW" -> Cursor.NE_RESIZE;
            default         -> Cursor.DEFAULT;
        });
    }

    // ── Sidebar collapse ──────────────────────────────────────────────────────

    private void toggleSidebar() {
        if (sidebarCollapsed) {
            // Expand — always restore to the preset width, not the last dragged position
            if (!splitPane.getItems().contains(thumbPanel)) {
                splitPane.getItems().add(0, thumbPanel);
                SplitPane.setResizableWithParent(thumbPanel, false);
            }
            sidebarCollapsed = false;
            Platform.runLater(() ->
                    splitPane.setDividerPositions(SIDEBAR_PRESET_W / Math.max(1, splitPane.getWidth())));
        } else {
            // Collapse — remove thumbPanel entirely so the divider truly reaches zero
            splitPane.getItems().remove(thumbPanel);
            sidebarCollapsed = true;
        }
    }

    /** Sets the sidebar divider to the preset width (no-op if sidebar is collapsed). */
    private void resetSidebarToPreset() {
        if (!sidebarCollapsed) {
            Platform.runLater(() ->
                    splitPane.setDividerPositions(SIDEBAR_PRESET_W / Math.max(1, splitPane.getWidth())));
        }
    }

    // ── Compare mode ─────────────────────────────────────────────────────────

    private void toggleCompareMode() {
        compareMode = !compareMode;
        compareBtn.setSelected(compareMode);

        if (compareMode) {
            enterCompareMode();
            if (!files.isEmpty()) {
                int seedIdx = (currentIdx + 1) % files.size();
                // Defer until after the SplitPane layout pass so MediaView has a size
                Platform.runLater(() -> loadIntoPane(files.get(seedIdx), comparePane, seedIdx));
            }
        } else {
            comparePane.clearMedia();
            if (mediaPane.getParent() instanceof BorderPane bp) bp.setCenter(null);
            syncControlsBar.translateXProperty().unbind();
            syncControlsBar.setTranslateX(0);
            syncControlsBar.setVisible(false);
            contentArea.getChildren().setAll(mediaPane, syncControlsBar);
            compareSplitPane = null;
            compareIdx = -1;
            setStatus("Compare mode OFF");
        }
    }

    private void enterCompareMode() {
        compareMode = true;
        compareBtn.setSelected(true);

        compareDivPos = 0.5;

        BorderPane mainWrap = wrapPane(mediaPane,   "Main  (← / →)");
        BorderPane cmpWrap  = wrapPane(comparePane, "Compare  (Alt+← / →  or  Alt+click)");
        double totalW = Math.max(contentArea.getWidth(), 100);
        mainWrap.setPrefWidth(totalW * 0.5);
        cmpWrap.setPrefWidth(totalW * 0.5);

        final SplitPane sp = new SplitPane(mainWrap, cmpWrap);
        compareSplitPane = sp;

        sp.widthProperty().addListener(new ChangeListener<>() {
            @Override public void changed(ObservableValue<? extends Number> obs, Number oldW, Number newW) {
                if (newW.doubleValue() <= 0) return;
                sp.widthProperty().removeListener(this);
                Platform.runLater(() -> {
                    if (compareSplitPane != sp) return;
                    sp.setDividerPositions(0.5);
                    SplitPane.Divider div = sp.getDividers().get(0);
                    div.positionProperty().addListener(
                            (o, old, n) -> { if (compareSplitPane == sp) compareDivPos = n.doubleValue(); });
                    syncControlsBar.translateXProperty().bind(
                            sp.widthProperty().multiply(div.positionProperty())
                                              .subtract(syncControlsBar.widthProperty().divide(2)));
                });
            }
        });

        contentArea.getChildren().setAll(compareSplitPane, syncControlsBar);

        refreshSyncControlsVisibility();
        Platform.runLater(this::updateSyncPlayPauseBtn);
        setStatus("Compare mode ON — Alt+click thumbnail or Alt+← / → to navigate right pane");
    }

    /**
     * Show the sync controls bar (fade in + start auto-hide timer) only when compare
     * mode is active and at least one pane is showing a video.  Call on mouse activity
     * or after loading new content into either compare pane.
     */
    private void refreshSyncControlsVisibility() {
        if (!compareMode) { hideSyncControls(false); return; }
        boolean mainHasVid    = currentIdx >= 0 && currentIdx < files.size()
                                && files.get(currentIdx).isVideo();
        boolean compareHasVid = compareIdx >= 0 && compareIdx < files.size()
                                && files.get(compareIdx).isVideo();
        if (mainHasVid || compareHasVid) showSyncControls();
        else                              hideSyncControls(false);
    }

    /** Fade the sync controls in and (re)start the 2.5 s auto-hide countdown. */
    private void showSyncControls() {
        if (syncHideTimer != null) syncHideTimer.stop();
        syncControlsBar.setVisible(true);
        // Snap to full opacity immediately (no need to animate in — user just moved)
        syncControlsBar.setOpacity(1.0);
        syncHideTimer = new Timeline(new KeyFrame(Duration.millis(2500), e -> hideSyncControls(true)));
        syncHideTimer.play();
    }

    /** Fade the sync controls out.  Pass {@code animate=false} for an instant hide. */
    private void hideSyncControls(boolean animate) {
        if (syncHideTimer != null) { syncHideTimer.stop(); syncHideTimer = null; }
        if (!syncControlsBar.isVisible()) return;
        if (animate) {
            FadeTransition ft = new FadeTransition(Duration.millis(350), syncControlsBar);
            ft.setFromValue(syncControlsBar.getOpacity());
            ft.setToValue(0.0);
            ft.setOnFinished(e -> syncControlsBar.setVisible(false));
            ft.play();
        } else {
            syncControlsBar.setOpacity(1.0);
            syncControlsBar.setVisible(false);
        }
    }

    private BorderPane wrapPane(MediaPane pane, String label) {
        Label lbl = new Label(label);
        lbl.setMaxWidth(Double.MAX_VALUE);
        lbl.setStyle("-fx-font-size: 10px; -fx-padding: 2 6; -fx-text-fill: #888; " +
                     "-fx-background-color: #1a1a1a;");
        BorderPane bp = new BorderPane(pane);
        bp.setTop(lbl);
        // Explicit background so no white scene-fill bleeds through gaps in compare mode
        bp.setStyle("-fx-background-color: #141414;");
        return bp;
    }

    // ── Synchronized compare-mode video controls ──────────────────────────────

    private VBox buildSyncControlsBar() {
        Button restartBtn = new Button("⏮ Restart Both");
        restartBtn.setTooltip(new Tooltip("Restart both videos from the beginning"));
        restartBtn.getStyleClass().add("toolbar-btn");
        restartBtn.setOnAction(e -> {
            mediaPane.restartVideo();
            comparePane.restartVideo();
        });

        syncPlayPauseBtn = new Button("⏸ Pause Both");
        syncPlayPauseBtn.setTooltip(new Tooltip("Play / Pause both videos simultaneously"));
        syncPlayPauseBtn.getStyleClass().add("toolbar-btn");
        syncPlayPauseBtn.setOnAction(e -> syncPlayPause());

        syncLoopBtn = new ToggleButton(config.loopVideo ? "🔁 Loop: ON" : "🔁 Loop: OFF");
        syncLoopBtn.setTooltip(new Tooltip("Toggle loop for both videos"));
        syncLoopBtn.getStyleClass().add("toolbar-btn");
        syncLoopBtn.setSelected(config.loopVideo);
        syncLoopBtn.setOnAction(e -> {
            boolean loop = syncLoopBtn.isSelected();
            syncLoopBtn.setText(loop ? "🔁 Loop: ON" : "🔁 Loop: OFF");
            mediaPane.setLoopEnabled(loop);
            comparePane.setLoopEnabled(loop);
        });

        for (var btn : new ButtonBase[]{restartBtn, syncPlayPauseBtn, syncLoopBtn})
            btn.setMaxWidth(Double.MAX_VALUE);   // fill VBox width so all buttons are equal-width

        VBox bar = new VBox(4, restartBtn, syncPlayPauseBtn, syncLoopBtn);
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(10, 8, 10, 8));
        bar.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-background-radius: 8;");
        bar.setMaxWidth(Region.USE_PREF_SIZE);
        bar.setMaxHeight(Region.USE_PREF_SIZE);  // prevent StackPane from stretching the bar full-height
        bar.setMouseTransparent(false);
        return bar;
    }

    private void syncPlayPause() {
        boolean mainPlaying    = mediaPane.isVideoPlaying();
        boolean comparePlaying = comparePane.isVideoPlaying();
        if (mainPlaying || comparePlaying) {
            if (mainPlaying)    mediaPane.togglePlayPause();
            if (comparePlaying) comparePane.togglePlayPause();
            syncPlayPauseBtn.setText("▶ Play Both");
        } else {
            mediaPane.togglePlayPause();
            comparePane.togglePlayPause();
            syncPlayPauseBtn.setText("⏸ Pause Both");
        }
    }

    private void updateSyncPlayPauseBtn() {
        boolean eitherPlaying = mediaPane.isVideoPlaying() || comparePane.isVideoPlaying();
        syncPlayPauseBtn.setText(eitherPlaying ? "⏸ Pause Both" : "▶ Play Both");
        boolean loop = mediaPane.isLoopEnabled();
        syncLoopBtn.setSelected(loop);
        syncLoopBtn.setText(loop ? "🔁 Loop: ON" : "🔁 Loop: OFF");
    }

    private void loadIntoPane(MediaFile mf, MediaPane pane, int idx) {
        if (pane == comparePane) compareIdx = idx;
        // showVideo/showImage each call disposeVideo() internally — don't call clearMedia()
        // first, as that would hide videoPane (→ width=0) and cause a black frame on load.
        if (mf.isVideo()) {
            // Kill ALL in-flight FFmpeg thumbnail processes — competing FFmpeg processes
            // consume I/O, file handles, and decode resources that the MediaPlayer needs.
            thumbPanel.cancelAllForPlayback();

            MediaPane.TransitionMode tm = pane.getTransitionMode();
            if (tm != MediaPane.TransitionMode.NONE) {
                // Dip to Black = explicit black; Fade = theme background colour.
                // Cover fades in over the old video, new video loads, cover fades out once
                // the first real frame has been decoded (no blank flash).
                boolean dipToBlack = (tm == MediaPane.TransitionMode.DIP_TO_BLACK);
                int fadeMs = dipToBlack ? 200 : 150;
                pane.setCoverColor(dipToBlack ? javafx.scene.paint.Color.BLACK : null);
                pane.fadeToBlack(fadeMs, () -> {
                    pane.showVideo(mf.getPath());
                    // Register AFTER showVideo() so showVideo()'s own init can't clear it.
                    // Safe: VLC can't deliver onFirstFrameArrived() until we yield the FX thread.
                    pane.setOnFirstFrame(() -> pane.fadeFromBlack(fadeMs));
                    if (pane == mediaPane) {
                        Long savedMs = config.videoPositions.get(mf.getPath().toString());
                        if (savedMs != null && savedMs > 10_000) {
                            pane.showResumePrompt(savedMs, () -> pane.seekToMs(savedMs));
                        }
                    }
                });
            } else {
                pane.showVideo(mf.getPath());
                // Offer to resume from saved position (only for the main pane, not compare)
                if (pane == mediaPane) {
                    Long savedMs = config.videoPositions.get(mf.getPath().toString());
                    if (savedMs != null && savedMs > 10_000) {
                        pane.showResumePrompt(savedMs, () -> pane.seekToMs(savedMs));
                    }
                }
            }
        } else {
            pane.showImage(mf.getPath());
        }

        // Mirror to the presentation pane whenever the main pane is updated
        if (pane == mediaPane && dualDisplayMode && presentationPane != null) {
            if (mf.isVideo()) presentationPane.showVideo(mf.getPath());
            else              presentationPane.showImage(mf.getPath());
        }

        // Sync controls should only appear when at least one pane has a video
        refreshSyncControlsVisibility();
    }

    private void compareNext() {
        if (!files.isEmpty()) {
            int next = (compareIdx + 1) % files.size();
            loadIntoPane(files.get(next), comparePane, next);
        }
    }

    private void comparePrev() {
        if (!files.isEmpty()) {
            int prev = (compareIdx - 1 + files.size()) % files.size();
            loadIntoPane(files.get(prev), comparePane, prev);
        }
    }

    // ── Slideshow queue ───────────────────────────────────────────────────────

    private void addToSlideshowQueue(int idx) {
        if (slideshowQueue.contains(idx)) {
            slideshowQueue.remove(Integer.valueOf(idx));
        } else {
            slideshowQueue.add(idx);
        }
        rebuildQueuePositions();

        if (slideshowQueue.isEmpty()) setStatus("Slideshow queue cleared");
        else setStatus(String.format("Slideshow queue: %d file%s  (Ctrl+click to add/remove)",
                slideshowQueue.size(), slideshowQueue.size() == 1 ? "" : "s"));
    }

    private void rebuildQueuePositions() {
        queuePositions.clear();
        for (int i = 0; i < slideshowQueue.size(); i++)
            queuePositions.put(slideshowQueue.get(i), i + 1);
        thumbPanel.setQueuePositions(queuePositions);
        // Keep clear button label in sync with queue count
        if (clearQueueBtn != null) {
            int n = slideshowQueue.size();
            clearQueueBtn.setText(n == 0 ? "✕ Queue" : "✕ Queue (" + n + ")");
        }
    }

    private void clearSlideshowQueue() {
        slideshowQueue.clear();
        rebuildQueuePositions();
        setStatus("Slideshow queue cleared");
    }

    private void openQueueManager() {
        if (slideshowQueue.isEmpty()) {
            setStatus("Slideshow queue is empty — Ctrl+click or Shift+click thumbnails to add files");
            return;
        }

        Stage dlgStage = new Stage(StageStyle.UNDECORATED);
        dlgStage.initOwner(stage);
        dlgStage.initModality(Modality.WINDOW_MODAL);
        dlgStage.getIcons().addAll(stage.getIcons());
        int n = slideshowQueue.size();
        String qTitle = "Queue Manager  ·  " + n + " file" + (n == 1 ? "" : "s");
        dlgStage.setMinWidth(420);
        dlgStage.setMinHeight(260);

        // Work on a mutable copy; committed to slideshowQueue only on Apply.
        ObservableList<Integer> items = FXCollections.observableArrayList(slideshowQueue);

        // ── Drag state ────────────────────────────────────────────────────────
        final double  CELL_H       = 76;
        final double  THUMB_W      = 116;
        final double  THUMB_H      = 66;
        final int[]     dragFrom     = {-1};
        final int[]     insertBefore = {-1};
        final boolean[] dragging     = {false};
        final double[]  dragStartX   = {0};
        final double[]  dragStartY   = {0};
        final Map<Integer, HBox>      liveRows      = new HashMap<>();
        final Map<Integer, ImageView> liveThumbViews = new HashMap<>();
        final Set<Integer>            thumbSubmitted = ConcurrentHashMap.newKeySet();

        // Background pool for loading thumbnails not yet cached on MediaFile.
        final java.util.concurrent.ExecutorService thumbExec =
                java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "qmgr-thumb"); t.setDaemon(true); return t;
                });

        // Tries to load a thumbnail from sidecar or direct (no FFmpeg).
        final java.util.function.Function<MediaFile, javafx.scene.image.Image> tryLoadThumb = file -> {
            Path parent = file.getPath().getParent();
            if (parent != null) {
                String nm  = file.getFilename();
                int    dot = nm.lastIndexOf('.');
                String stem = dot >= 0 ? nm.substring(0, dot) : nm;
                Path   tDir = parent.resolve("thumbnails");
                for (String ext : List.of("jpg", "jpeg", "png", "webp")) {
                    Path tp = tDir.resolve(stem + "." + ext);
                    if (Files.exists(tp)) {
                        try { return new javafx.scene.image.Image(
                                tp.toUri().toString(), THUMB_W * 2, THUMB_H * 2, true, true, false);
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (file.isImage()) {
                try { return new javafx.scene.image.Image(
                        file.getPath().toUri().toString(), THUMB_W * 2, THUMB_H * 2, true, true, false);
                } catch (Exception e2) { return null; }
            }
            return null; // video with no sidecar — dark placeholder is fine
        };

        final String ROW_BASE = "-fx-background-color: rgba(255,255,255,0.04); "
                + "-fx-background-radius: 6; -fx-padding: 0 4 0 0;";

        final java.util.function.BiConsumer<Integer, HBox> styleRow = (idx, row) -> {
            if (row == null) return;
            boolean isSource  = dragging[0] && dragFrom[0] == idx;
            boolean dropAbove = dragging[0] && insertBefore[0] == idx;
            boolean dropBelow = dragging[0] && insertBefore[0] >= items.size()
                                && idx == items.size() - 1;
            if (isSource) {
                row.setStyle(ROW_BASE);
                row.setOpacity(0.30);
            } else if (dropAbove) {
                row.setStyle(ROW_BASE + "-fx-border-color: #f5a623 transparent "
                        + "transparent transparent; -fx-border-width: 2 0 0 0;");
                row.setOpacity(1.0);
            } else if (dropBelow) {
                row.setStyle(ROW_BASE + "-fx-border-color: transparent transparent "
                        + "#f5a623 transparent; -fx-border-width: 0 0 2 0;");
                row.setOpacity(1.0);
            } else {
                row.setStyle(ROW_BASE);
                row.setOpacity(1.0);
            }
        };

        final Runnable refreshVisuals = () -> liveRows.forEach(styleRow::accept);

        // ── List ──────────────────────────────────────────────────────────────
        ListView<Integer> queueList = new ListView<>(items);
        queueList.setFixedCellSize(CELL_H);
        queueList.setPrefWidth(520);
        queueList.setPrefHeight(Math.min(600, items.size() * CELL_H + 4));
        queueList.setFocusTraversable(false);

        // ── Cell factory ──────────────────────────────────────────────────────
        queueList.setCellFactory(lv -> {
            ListCell<Integer> cell = new ListCell<>() {
                @Override public void updateSelected(boolean s) { /* suppress selection flash */ }
                @Override
                protected void updateItem(Integer fileIdx, boolean empty) {
                    super.updateItem(fileIdx, empty);
                    setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                    if (empty || fileIdx == null) {
                        liveRows.remove(getIndex());
                        liveThumbViews.remove(getIndex());
                        setGraphic(null);
                        return;
                    }

                    int listIdx = getIndex();
                    int qPos = listIdx + 1;
                    MediaFile file = (fileIdx >= 0 && fileIdx < files.size())
                            ? files.get(fileIdx) : null;
                    String filename = file != null ? file.getFilename() : "?";
                    boolean isVid   = file != null && file.isVideo();

                    // ── Drag handle ───────────────────────────────────────────
                    Label handle = new Label("⠿");
                    handle.setStyle("-fx-text-fill: #666; -fx-font-size: 18px; "
                            + "-fx-padding: 0 8 0 4; -fx-cursor: open-hand;");

                    // ── Thumbnail stack ───────────────────────────────────────
                    javafx.scene.shape.Rectangle bg =
                            new javafx.scene.shape.Rectangle(THUMB_W, THUMB_H,
                                    javafx.scene.paint.Color.web("#222"));
                    bg.setArcWidth(4); bg.setArcHeight(4);

                    ImageView thumbView = new ImageView();
                    thumbView.setFitWidth(THUMB_W);
                    thumbView.setFitHeight(THUMB_H);
                    thumbView.setPreserveRatio(true);
                    thumbView.setSmooth(true);

                    // Populate thumbnail — use cached value or trigger async load
                    javafx.scene.image.Image cached = file != null ? file.getThumbnail() : null;
                    if (cached != null) {
                        thumbView.setImage(cached);
                    } else if (file != null && thumbSubmitted.add(fileIdx)) {
                        final MediaFile f = file;
                        final int fIdx   = fileIdx;
                        thumbExec.submit(() -> {
                            javafx.scene.image.Image img = tryLoadThumb.apply(f);
                            if (img != null) {
                                f.setThumbnail(img);
                                Platform.runLater(() -> {
                                    // find the current ImageView for this file index
                                    liveThumbViews.forEach((li, iv) -> {
                                        if (li < items.size() && items.get(li).equals(fIdx))
                                            iv.setImage(img);
                                    });
                                });
                            }
                        });
                    }

                    // Video play badge (centred)
                    Label playBadge = new Label("▶");
                    playBadge.setStyle(
                            "-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 16px; "
                            + "-fx-background-color: rgba(0,0,0,0.45); "
                            + "-fx-background-radius: 50; -fx-padding: 3 6 3 8;");
                    playBadge.setMouseTransparent(true);
                    playBadge.setVisible(isVid);

                    // Queue-position badge (top-left of thumbnail)
                    Label badge = new Label(String.valueOf(qPos));
                    badge.setStyle("-fx-background-color: #f5a623; -fx-text-fill: white; "
                            + "-fx-font-weight: bold; -fx-font-size: 10px; "
                            + "-fx-padding: 2 5 2 5; -fx-background-radius: 4;");
                    badge.setMouseTransparent(true);
                    StackPane.setAlignment(badge, Pos.TOP_LEFT);
                    StackPane.setMargin(badge, new Insets(3, 0, 0, 3));

                    StackPane thumbStack = new StackPane(bg, thumbView, playBadge, badge);
                    thumbStack.setPrefSize(THUMB_W, THUMB_H);
                    thumbStack.setMaxSize(THUMB_W, THUMB_H);
                    thumbStack.setStyle("-fx-background-radius: 4;");
                    Tooltip.install(thumbStack, new Tooltip(filename));

                    // ── Remove button ─────────────────────────────────────────
                    Button rmBtn = new Button("✕");
                    rmBtn.getStyleClass().add("toolbar-btn");
                    rmBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8 2 8;");
                    rmBtn.setOnAction(e -> items.remove(fileIdx));

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    HBox row = new HBox(0, handle, thumbStack, spacer, rmBtn);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPrefHeight(CELL_H - 8);
                    HBox.setMargin(thumbStack, new Insets(0, 6, 0, 0));
                    HBox.setMargin(rmBtn,      new Insets(0, 6, 0, 0));

                    liveRows.put(listIdx, row);
                    liveThumbViews.put(listIdx, thumbView);
                    styleRow.accept(listIdx, row);

                    VBox wrapper = new VBox(row);
                    wrapper.setPadding(new Insets(5, 4, 5, 4));
                    setGraphic(wrapper);
                }
            };

            // MOUSE_PRESSED: record drag source (skip remove button) + press highlight
            cell.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (cell.isEmpty()) return;
                Node n2 = (Node) e.getTarget();
                while (n2 != null && n2 != cell) {
                    if (n2 instanceof Button) return;
                    n2 = n2.getParent();
                }
                dragFrom[0]    = cell.getIndex();
                dragging[0]    = false;
                insertBefore[0] = -1;
                dragStartX[0]  = e.getSceneX();
                dragStartY[0]  = e.getSceneY();
                // Brief highlight so the click feels responsive
                HBox row = liveRows.get(cell.getIndex());
                if (row != null) {
                    row.setStyle("-fx-background-color: rgba(255,255,255,0.13); "
                            + "-fx-background-radius: 6; -fx-padding: 0 4 0 0;");
                }
            });

            return cell;
        });

        // ── Drag tracking on the list level ───────────────────────────────────
        // List-level filters fire before the ListView's own handlers, so we
        // always receive drag events. Visuals are updated by styling liveRows
        // directly — no refresh() during drag means zero cell-rebuild flicker.
        queueList.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (dragFrom[0] < 0) return;

            // Require 6px of travel before treating as a real drag — prevents
            // accidental moves from micro-movements during a normal click.
            double dist = Math.hypot(e.getSceneX() - dragStartX[0],
                                     e.getSceneY() - dragStartY[0]);
            if (!dragging[0] && dist < 6) return;

            boolean wasAlreadyDragging = dragging[0];
            dragging[0] = true;

            // sceneToLocal gives coords relative to the ListView's viewport top-left.
            // To get the real list-item index we must add the scroll offset, otherwise
            // the slot calculation is wrong whenever the list is scrolled.
            double viewportY = queueList.sceneToLocal(e.getSceneX(), e.getSceneY()).getY();
            ScrollBar sb = (ScrollBar) queueList.lookup(".scroll-bar:vertical");
            double scrollFrac  = (sb != null) ? sb.getValue() : 0.0;
            double contentH    = items.size() * CELL_H;
            double scrollOffset = scrollFrac * Math.max(0, contentH - queueList.getHeight());
            double contentY    = viewportY + scrollOffset;

            int idx = Math.max(0, Math.min((int)(contentY / CELL_H), items.size()));
            double offset = contentY - idx * CELL_H;
            if (idx < items.size() && offset >= CELL_H * 0.5) idx++;
            int newInsert = Math.max(0, Math.min(idx, items.size()));

            // Only update visuals when the drop slot changes
            if (!wasAlreadyDragging || newInsert != insertBefore[0]) {
                insertBefore[0] = newInsert;
                refreshVisuals.run();
            }
            e.consume();   // prevent ListView scroll during reorder
        });

        queueList.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            int  pressedIdx = dragFrom[0];   // save before clearing
            boolean wasDragging = dragging[0];
            boolean didMove     = false;
            if (wasDragging && pressedIdx >= 0 && insertBefore[0] >= 0) {
                int from = pressedIdx;
                int to   = insertBefore[0];
                if (to != from && to != from + 1) {
                    int dest = to > from ? to - 1 : to;
                    Integer moved = items.remove(from);
                    items.add(Math.min(dest, items.size()), moved);
                    didMove = true;
                }
            }
            dragFrom[0]    = -1;
            insertBefore[0] = -1;
            dragging[0]    = false;
            if (didMove) {
                queueList.refresh();
            } else if (wasDragging) {
                refreshVisuals.run();
            } else if (pressedIdx >= 0) {
                // Plain click — restore press highlight without rebuilding the cell
                HBox row = liveRows.get(pressedIdx);
                if (row != null) styleRow.accept(pressedIdx, row);
            }
        });

        // ── Buttons ───────────────────────────────────────────────────────────
        Button clearAllBtn = toolBtn("✕ Clear All", "Remove every item from the queue",
                items::clear);

        Button genCCBtn = toolBtn("🎙 Generate CC", "Bulk-generate captions for all videos in the queue", null);
        genCCBtn.setOnAction(e -> {
            // Collect video paths from current items list
            List<Path> videos = items.stream()
                    .filter(idx -> idx >= 0 && idx < files.size() && files.get(idx).isVideo())
                    .map(idx -> files.get(idx).getPath())
                    .collect(Collectors.toList());
            if (videos.isEmpty()) {
                setStatus("No video files in queue — CC generation skipped");
                return;
            }
            // Commit queue and close manager before starting
            slideshowQueue.clear();
            slideshowQueue.addAll(items);
            rebuildQueuePositions();
            dlgStage.close();
            openBulkCCDialog(videos);
        });

        Button applyBtn = new Button("✔ Apply & Close");
        applyBtn.getStyleClass().add("toolbar-btn");
        applyBtn.setDefaultButton(true);
        applyBtn.setOnAction(e -> {
            slideshowQueue.clear();
            slideshowQueue.addAll(items);
            rebuildQueuePositions();
            setStatus(items.isEmpty()
                    ? "Slideshow queue cleared"
                    : String.format("Queue updated — %d file%s",
                            items.size(), items.size() == 1 ? "" : "s"));
            dlgStage.close();
        });

        Button cancelBtn = toolBtn("Cancel", "Discard changes", dlgStage::close);
        cancelBtn.setCancelButton(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox btnRow = new HBox(8, clearAllBtn, genCCBtn, spacer, cancelBtn, applyBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        btnRow.setPadding(new Insets(6, 4, 2, 4));

        Label hint = new Label(
                "Drag ⠿ to reorder  ·  click ✕ to remove individual items  ·  "
                + "Ctrl+click or Shift+click thumbnails to add more");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        hint.setPadding(new Insets(2, 4, 0, 4));

        VBox layout = new VBox(6, queueList, hint, btnRow);
        layout.setPadding(new Insets(10));

        VBox dlgRoot = new VBox(0, makeTitleBar(qTitle, dlgStage, buildQueueIcon()), layout);
        VBox.setVgrow(layout, Priority.ALWAYS);
        Scene dlgScene = new Scene(dlgRoot);
        if (scene != null) dlgScene.getStylesheets().addAll(scene.getStylesheets());
        dlgStage.setScene(dlgScene);
        dlgStage.setResizable(true);
        dlgStage.setOnHidden(e -> thumbExec.shutdownNow());
        dlgStage.setOnShown(e -> centerOnOwner(dlgStage, stage));
        dlgStage.showAndWait();
    }

    /**
     * Opens a non-modal progress window and bulk-generates CC for the given video paths.
     */
    private void openBulkCCDialog(List<Path> videos) {
        int total = videos.size();

        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.NONE);
        dlg.getIcons().addAll(stage.getIcons());
        dlg.setResizable(true);
        dlg.setMinWidth(520);
        dlg.setMinHeight(300);

        // ── Custom title bar ──────────────────────────────────────────────────
        Label titleLbl = new Label("Generating CC — 0 / " + total);
        titleLbl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLbl, Priority.ALWAYS);
        // X on the title bar — same logic as setOnCloseRequest
        Button titleCloseBtn = new Button("✕");
        titleCloseBtn.getStyleClass().addAll("wm-btn", "wm-close");
        HBox titleBar = new HBox(6);
        titleBar.getStyleClass().add("title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(5, 0, 5, 10));
        if (!stage.getIcons().isEmpty()) {
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(stage.getIcons().get(0));
            iv.setFitWidth(16); iv.setFitHeight(16); iv.setPreserveRatio(true);
            titleBar.getChildren().add(iv);
        }
        titleBar.getChildren().addAll(titleLbl, titleCloseBtn);
        double[] drag = {0, 0};
        titleBar.setOnMousePressed(e -> { drag[0] = e.getScreenX(); drag[1] = e.getScreenY(); });
        titleBar.setOnMouseDragged(e -> {
            dlg.setX(dlg.getX() + e.getScreenX() - drag[0]);
            dlg.setY(dlg.getY() + e.getScreenY() - drag[1]);
            drag[0] = e.getScreenX(); drag[1] = e.getScreenY();
        });

        // ── Overall progress ──────────────────────────────────────────────────
        Label overallLbl = new Label("Starting…");
        overallLbl.setWrapText(true);
        overallLbl.setMaxWidth(Double.MAX_VALUE);
        ProgressBar overallBar = new ProgressBar(0);
        overallBar.setMaxWidth(Double.MAX_VALUE);

        // ── Current-file progress ─────────────────────────────────────────────
        Label fileLbl = new Label(" ");
        fileLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");
        fileLbl.setWrapText(true);
        fileLbl.setMaxWidth(Double.MAX_VALUE);
        ProgressBar fileBar = new ProgressBar(0);
        fileBar.setMaxWidth(Double.MAX_VALUE);

        // ── Log ───────────────────────────────────────────────────────────────
        TextArea log = new TextArea();
        log.setEditable(false);
        log.setWrapText(true);
        VBox.setVgrow(log, Priority.ALWAYS);

        // ── Skip-existing checkbox ────────────────────────────────────────────
        CheckBox skipBox = new CheckBox("Skip videos that already have a subtitle file");
        skipBox.setSelected(true);

        // ── Buttons ───────────────────────────────────────────────────────────
        Button cancelBtn = new Button("Cancel");
        Button closeBtn  = new Button("Close");
        closeBtn.setDisable(true);
        cancelBtn.setCancelButton(true);

        HBox btnRow = new HBox(8, new Region(), cancelBtn, closeBtn);
        HBox.setHgrow(btnRow.getChildren().get(0), Priority.ALWAYS);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8,
                overallLbl, overallBar,
                fileLbl, fileBar,
                skipBox, log, btnRow);
        content.setPadding(new Insets(14));

        BorderPane root = new BorderPane(content);
        root.setTop(titleBar);

        Scene dlgScene = new Scene(root, 540, 400);
        if (scene != null) dlgScene.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(dlgScene);

        // ── Cancel / close helpers ────────────────────────────────────────────
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Thread[] workerRef = {null};

        Runnable doCancel = () -> {
            if (!cancelled.get() && closeBtn.isDisable()) {
                cancelled.set(true);
                if (workerRef[0] != null) workerRef[0].interrupt();
                cancelBtn.setDisable(true);
                overallLbl.setText("Cancelling…");
            }
        };
        titleCloseBtn.setOnAction(e -> { doCancel.run(); dlg.close(); });

        // ── Start generation once dialog is shown ─────────────────────────────
        dlg.setOnShown(ev -> {
            centerOnOwner(dlg, stage);

            Thread worker = mediaPane.generateCaptionsBulk(
                    videos,
                    skipBox.isSelected(),
                    // onFileStart(fileIndex 0-based, total)
                    (fi, tot) -> Platform.runLater(() -> {
                        titleLbl.setText("Generating CC — " + fi + " / " + tot);
                        overallBar.setProgress((double) fi / tot);
                        fileBar.setProgress(0);
                    }),
                    // onFileProgress (0-100)
                    pct -> Platform.runLater(() -> fileBar.setProgress(pct / 100.0)),
                    // onStatus
                    msg -> Platform.runLater(() -> {
                        overallLbl.setText(msg);
                        log.appendText(msg + "\n");
                        fileLbl.setText(msg);
                        setStatus(msg);
                    }),
                    // onDone
                    () -> Platform.runLater(() -> {
                        overallBar.setProgress(1.0);
                        fileBar.setProgress(1.0);
                        titleLbl.setText("CC Generation Complete — " + total + " / " + total);
                        cancelBtn.setDisable(true);
                        closeBtn.setDisable(false);
                        skipBox.setDisable(true);
                    }),
                    cancelled);
            workerRef[0] = worker;
            skipBox.setDisable(true); // lock after start
        });

        cancelBtn.setOnAction(e -> doCancel.run());
        closeBtn.setOnAction(e -> dlg.close());
        dlg.setOnCloseRequest(e -> doCancel.run());

        dlg.show();
    }

    private void slideshowNextQueued() {
        if (slideshowQueue.isEmpty()) return;
        int next = slideshowQueueIdx + 1;
        if (next >= slideshowQueue.size()) {
            if (!slideshowLoop) { stopSlideshow(); setStatus("Slideshow finished"); return; }
            next = 0;
        }
        slideshowQueueIdx = next;
        navigateTo(slideshowQueue.get(slideshowQueueIdx));
    }

    private void slideshowPrevQueued() {
        if (slideshowQueue.isEmpty()) return;
        int prev = slideshowQueueIdx - 1;
        if (prev < 0) {
            if (!slideshowLoop) return;
            prev = slideshowQueue.size() - 1;
        }
        slideshowQueueIdx = prev;
        navigateTo(slideshowQueue.get(slideshowQueueIdx));
    }

    /**
     * Manual "next" step while slideshow is running — respects queue and shuffle.
     * Resets the interval timer so the new file gets the full display time.
     */
    private void slideshowManualNext() {
        if (slideshowShuffle) {
            if (shufflePos >= shuffleOrder.size()) rebuildShuffleOrder();
            navigateTo(shuffleOrder.get(shufflePos++));
        } else if (!slideshowQueue.isEmpty()) {
            slideshowNextQueued();
        } else {
            nextFile();
        }
        startSlideshowTimer();
    }

    /**
     * Manual "prev" step while slideshow is running — respects queue and shuffle.
     * Resets the interval timer so the new file gets the full display time.
     */
    private void slideshowManualPrev() {
        if (slideshowShuffle) {
            // shufflePos points to the *next* item; current is at shufflePos-1,
            // previous is at shufflePos-2.  Clamp so we never go below 0.
            shufflePos = Math.max(0, shufflePos - 2);
            if (shufflePos < shuffleOrder.size()) {
                navigateTo(shuffleOrder.get(shufflePos++));
            }
        } else if (!slideshowQueue.isEmpty()) {
            slideshowPrevQueued();
        } else {
            prevFile();
        }
        startSlideshowTimer();
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    private void wireKeyboard() {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode kc   = e.getCode();
            boolean ctrl = e.isControlDown();

            switch (kc) {
                case RIGHT, PAGE_DOWN -> {
                    if ((ctrl || e.isAltDown()) && compareMode) compareNext();
                    else if (!ctrl && !e.isAltDown() && !e.isShiftDown()
                            && kc == KeyCode.RIGHT && mediaPane.isVideoPaused()) {
                        mediaPane.stepFrame(true);   // bare → = frame step while paused
                    } else if (slideshowRunning) {
                        slideshowManualNext();        // respect queue / shuffle order
                    } else nextFile();
                    e.consume();
                }
                case LEFT, PAGE_UP -> {
                    if ((ctrl || e.isAltDown()) && compareMode) comparePrev();
                    else if (!ctrl && !e.isAltDown() && !e.isShiftDown()
                            && kc == KeyCode.LEFT && mediaPane.isVideoPaused()) {
                        mediaPane.stepFrame(false);  // bare ← = frame step while paused
                    } else if (slideshowRunning) {
                        slideshowManualPrev();        // respect queue / shuffle order
                    } else prevFile();
                    e.consume();
                }
                case HOME              -> { navigateTo(0);                 e.consume(); }
                case END               -> { navigateTo(files.size() - 1); e.consume(); }
                case SPACE             -> { activePane.togglePlayPause();  e.consume(); }
                case PLUS, EQUALS, ADD -> { mediaPane.zoomIn();            e.consume(); }
                case MINUS, SUBTRACT   -> { mediaPane.zoomOut();           e.consume(); }
                case DIGIT0, NUMPAD0   -> { mediaPane.zoomFit();           e.consume(); }
                case R                 -> { mediaPane.rotateRight();        e.consume(); }
                case L                 -> { if (!ctrl) { mediaPane.rotateLeft(); e.consume(); } }
                case F, F11            -> {
                    if (kc == KeyCode.F && ctrl && e.isShiftDown()) openCapturesFolder();
                    else toggleFullScreen();
                    e.consume();
                }
                case D                 -> { if (!ctrl) { toggleDualDisplay(); e.consume(); } }
                case T                 -> { cycleTheme();                   e.consume(); }
                case C                 -> {
                    if (ctrl) { copyCurrentImageToClipboard(); e.consume(); }
                    else      { toggleCompareMode();           e.consume(); }
                }
                case V                 -> { if (ctrl) { pasteFromClipboard();   e.consume(); } }
                case DELETE            -> {
                    if (slideshowQueue.size() >= 2) {
                        List<Integer> batch = new ArrayList<>(slideshowQueue);
                        Collections.sort(batch);
                        batchDeleteFiles(batch);
                    } else {
                        deleteCurrentFile();
                    }
                    e.consume();
                }
                case ESCAPE            -> { if (stage.isFullScreen()) { stage.setFullScreen(false); e.consume(); } }
                case B                 -> { if (!ctrl) { openThumbnailBrowser(); e.consume(); } }
                case O                 -> { openFolderDialog(); e.consume(); }
                case PERIOD            -> { mediaPane.stepSpeedUp();   e.consume(); }
                case COMMA             -> { mediaPane.stepSpeedDown();  e.consume(); }
                case S                 -> { if (ctrl) { toggleSlideshow();    e.consume(); } }
                case P                 -> { if (ctrl) { printCurrentFile();    e.consume(); } }
                case I                 -> { if (ctrl) { showFileInfo();        e.consume(); } }
                case SLASH             -> { if (!ctrl) { showShortcutsHelp(); e.consume(); } }
                case F2                -> { renameCurrentFile(); e.consume(); }
                default -> {}
            }
        });
    }

    // ── Full-screen ───────────────────────────────────────────────────────────

    private void toggleFullScreen() { stage.setFullScreen(!stage.isFullScreen()); }

    /** Show or hide the compare-pane header labels (e.g. "Main ←/→") embedded in the
     *  compareSplitPane wrapPanes.  They are hidden in fullscreen so the content fills
     *  the entire pane without a tiny label bar eating into the image area. */
    private void setComparePaneLabelsVisible(boolean visible) {
        if (compareSplitPane == null) return;
        for (javafx.scene.Node item : compareSplitPane.getItems()) {
            if (item instanceof BorderPane bp && bp.getTop() != null) {
                bp.getTop().setVisible(visible);
                bp.getTop().setManaged(visible);
            }
        }
    }

    private void wireFullScreen() {
        stage.fullScreenProperty().addListener((obs, was, isNow) -> {
            if (isNow) {
                if (!sidebarCollapsed && !splitPane.getDividers().isEmpty())
                    fullscreenSavedDivider = splitPane.getDividerPositions()[0];
                root.setTop(null);
                root.setBottom(null);
                splitPane.getItems().remove(thumbPanel);
                setComparePaneLabelsVisible(false);
                // Start the cursor-hide countdown immediately on entering fullscreen.
                restartCursorHideTimer();
            } else {
                root.setTop(topArea);
                root.setBottom(statusBar);
                setComparePaneLabelsVisible(true);
                // Only restore thumbPanel if the sidebar isn't intentionally collapsed
                if (!sidebarCollapsed && !splitPane.getItems().contains(thumbPanel)) {
                    splitPane.getItems().add(0, thumbPanel);
                    SplitPane.setResizableWithParent(thumbPanel, false);
                }
                if (!sidebarCollapsed) {
                    final double pos = fullscreenSavedDivider;
                    Platform.runLater(() -> splitPane.setDividerPositions(pos));
                }
                // Re-apply compare divider after the layout settles
                if (compareMode && compareSplitPane != null) {
                    final double cpos = compareDivPos;
                    Platform.runLater(() -> compareSplitPane.setDividerPositions(cpos));
                }
                // Always restore cursor when leaving fullscreen.
                if (cursorHideTimer != null) { cursorHideTimer.stop(); cursorHideTimer = null; }
                scene.setCursor(Cursor.DEFAULT);
            }
        });

        // Any mouse activity in fullscreen: show cursor and reset the hide countdown.
        javafx.event.EventHandler<MouseEvent> cursorActivity = e -> {
            if (!stage.isFullScreen()) return;
            scene.setCursor(Cursor.DEFAULT);
            restartCursorHideTimer();
        };
        scene.addEventFilter(MouseEvent.MOUSE_MOVED,   cursorActivity);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, cursorActivity);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, cursorActivity);
    }

    private void restartCursorHideTimer() {
        if (cursorHideTimer != null) cursorHideTimer.stop();
        cursorHideTimer = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            if (stage.isFullScreen()) scene.setCursor(Cursor.NONE);
        }));
        cursorHideTimer.play();
    }

    // ── Dual-display (presentation) mode ─────────────────────────────────────

    private void toggleDualDisplay() {
        if (dualDisplayMode) closePresentationStage();
        else                 openPresentationStage();
    }

    private void openPresentationStage() {
        // Find the screen that does NOT contain the main window
        javafx.geometry.Rectangle2D mainBounds =
                new javafx.geometry.Rectangle2D(stage.getX(), stage.getY(), 1, 1);
        Screen mainScreen = Screen.getScreensForRectangle(mainBounds)
                .stream().findFirst().orElse(Screen.getPrimary());
        Screen secondScreen = Screen.getScreens().stream()
                .filter(s -> !s.getBounds().equals(mainScreen.getBounds()))
                .findFirst().orElse(null);

        if (secondScreen == null) {
            dualDisplayBtn.setSelected(false);
            setStatus("No second monitor detected");
            return;
        }

        presentationPane = new MediaPane();
        presentationPane.setOnStatusUpdate(s -> {});
        presentationPane.setOnFullscreenRequest(() -> presentationStage.setFullScreen(!presentationStage.isFullScreen()));

        // Use visual bounds (excludes taskbar) so the window doesn't spill off-screen
        javafx.geometry.Rectangle2D bounds = secondScreen.getVisualBounds();
        Scene presScene = new Scene(presentationPane, bounds.getWidth(), bounds.getHeight());
        // Must be BLACK (not default white) — the videoView is intentionally 46 px shorter
        // than videoPane to leave room for the control bar, and that gap would show white otherwise.
        presScene.setFill(javafx.scene.paint.Color.BLACK);
        theme.applyTheme(presScene, theme.getCurrentTheme());

        presentationStage = new Stage();
        // DECORATED gives the OS title bar → user can drag, resize, and minimize freely
        presentationStage.initStyle(StageStyle.DECORATED);
        presentationStage.setTitle("Presentation Display");
        presentationStage.setScene(presScene);

        // Start maximized on the second screen (user can un-maximize to freely move/resize)
        presentationStage.setX(bounds.getMinX());
        presentationStage.setY(bounds.getMinY());
        presentationStage.setWidth(bounds.getWidth());
        presentationStage.setHeight(bounds.getHeight());

        // Escape / D on the presentation window closes it
        presScene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) presentationStage.setFullScreen(false);
            else if (e.getCode() == KeyCode.D)  closePresentationStage();
            else if (e.getCode() == KeyCode.F || e.getCode() == KeyCode.F11)
                presentationStage.setFullScreen(!presentationStage.isFullScreen());
        });
        presentationStage.setOnHidden(e -> {
            if (dualDisplayMode) {
                dualDisplayMode = false;
                dualDisplayBtn.setSelected(false);
                presentationPane = null;
                presentationStage = null;
            }
        });

        presentationStage.show();
        dualDisplayMode = true;
        dualDisplayBtn.setSelected(true);
        setStatus("Dual-display mode active  —  drag/resize the presentation window freely  (D to close)");

        // Mirror current file to the presentation pane
        if (currentIdx >= 0 && currentIdx < files.size())
            loadIntoPane(files.get(currentIdx), presentationPane, currentIdx);
    }

    private void closePresentationStage() {
        dualDisplayMode = false;
        dualDisplayBtn.setSelected(false);
        if (presentationPane != null) { presentationPane.stopVideo(); presentationPane = null; }
        if (presentationStage != null) { presentationStage.close(); presentationStage = null; }
        setStatus("Dual-display mode off");
    }

    // ── Drag & Drop ───────────────────────────────────────────────────────────

    /** Returns true if at least one item in the list is a directory or supported media file. */
    private boolean isAnyAcceptable(List<java.io.File> items) {
        for (java.io.File f : items) {
            Path p = f.toPath();
            if (Files.isDirectory(p) || MediaFile.isSupported(p)) return true;
        }
        return false;
    }

    private VBox buildDropOverlay() {
        Label lbl = new Label("Drop to load");
        lbl.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 4, 0, 0, 1);");
        VBox box = new VBox(lbl);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: rgba(0,0,0,0.52); "
                + "-fx-border-color: rgba(255,255,255,0.45); "
                + "-fx-border-width: 3; -fx-border-radius: 10; -fx-background-radius: 10;");
        box.setMouseTransparent(true);
        box.setVisible(false);
        StackPane.setMargin(box, new Insets(20));
        return box;
    }

    private void wireDragDrop() {
        // Only accept drags that contain at least one supported file or folder
        scene.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && isAnyAcceptable(db.getFiles())) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        // Show overlay when an acceptable drag enters the window
        scene.setOnDragEntered(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && isAnyAcceptable(db.getFiles())) {
                dropOverlay.setVisible(true);
            }
            e.consume();
        });
        // Hide overlay when the drag leaves
        scene.setOnDragExited(e -> {
            dropOverlay.setVisible(false);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            dropOverlay.setVisible(false);
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                List<Path> paths = db.getFiles().stream()
                        .map(java.io.File::toPath)
                        .filter(p -> Files.isDirectory(p) || MediaFile.isSupported(p))
                        .collect(Collectors.toList());
                if (!paths.isEmpty()) {
                    loadPaths(paths);
                    e.setDropCompleted(true);
                }
            }
            e.consume();
        });
    }

    // ── Window close ─────────────────────────────────────────────────────────

    private void wireWindowClose() {
        // Use setOnHiding (not setOnCloseRequest) so this fires for BOTH the OS
        // title-bar X button AND the toolbar close button (stage.close()).
        stage.setOnHiding(e -> {
            // Stop videos first so their positions are saved into config,
            // then write the final config (includes those positions + window state).
            mediaPane.stopVideo();
            comparePane.stopVideo();
            if (presentationPane  != null) presentationPane.stopVideo();
            if (presentationStage != null) { presentationStage.close(); presentationStage = null; }
            saveConfig();
            thumbPanel.shutdown();
            stopWatcher();
            watchExecutor.shutdownNow();
            TranslationService.stopGlobal();
            MediaPane.releaseVlcFactory();
        });
    }

    // ── Folder loading ────────────────────────────────────────────────────────

    private void openFolderDialog() {
        FolderBrowserDialog dlg = new FolderBrowserDialog(stage, config);
        Optional<List<Path>> result = dlg.showAndWait();
        result.ifPresent(paths -> {
            config.favoriteFolders.clear();
            config.favoriteFolders.addAll(dlg.getFavorites());
            if (paths.size() == 1) {
                loadFolder(paths.get(0));
            } else {
                loadPaths(paths);
            }
            config.save();
        });
    }

    private void openThumbnailBrowser() {
        if (files.isEmpty()) {
            setStatus("No files loaded — open a folder first");
            return;
        }
        ThumbnailBrowserDialog[] ref = {null};
        ref[0] = new ThumbnailBrowserDialog(stage, scene, List.copyOf(files), currentIdx,
                browserSortMode, browserSortAsc,
                idx -> navigateTo(idx),   // navigateTo() now syncs browser via openBrowserDialog
                idx -> {
                    addToSlideshowQueue(idx);
                    if (ref[0] != null) ref[0].refreshQueueBadges(queuePositions);
                },
                range -> {
                    for (int idx : range)
                        if (!slideshowQueue.contains(idx)) slideshowQueue.add(idx);
                    rebuildQueuePositions();
                    if (ref[0] != null) ref[0].refreshQueueBadges(queuePositions);
                },
                idx -> {
                    if (!files.isEmpty() && idx >= 0 && idx < files.size()) {
                        if (compareMode && idx == compareIdx) {
                            toggleCompareMode();
                        } else {
                            boolean wasInCompare = compareMode;
                            if (!compareMode) enterCompareMode();
                            MediaFile mf = files.get(idx);
                            if (wasInCompare) {
                                loadIntoPane(mf, comparePane, idx);
                            } else {
                                Platform.runLater(() -> loadIntoPane(mf, comparePane, idx));
                            }
                        }
                    }
                });
        ref[0].setOnReveal(idx -> {});   // navigateTo already handles focus; reveal opens Explorer
        ref[0].setOnRename(this::renameFile);
        ref[0].setOnBatchDelete(this::batchDeleteFiles);
        ref[0].setOnBatchRename(this::batchRenameFiles);
        ref[0].setOnStar(idx -> {
            toggleStar(idx);
            // Rebuild browser grid so star badge + filter update immediately
            if (ref[0] != null) ref[0].refreshStars(List.copyOf(files));
        });
        ref[0].setOnCopyTo(this::copyFileTo);
        ref[0].setOnMoveTo(this::moveFileTo);
        ref[0].setOnClearQueue(() -> {
            slideshowQueue.clear();
            queuePositions.clear();
            thumbPanel.setQueuePositions(queuePositions);
            ref[0].refreshQueueBadges(queuePositions);
        });
        ref[0].refreshQueueBadges(queuePositions);   // show existing queue state on open
        openBrowserDialog = ref[0];
        ref[0].setOnClose(() -> openBrowserDialog = null);
        ref[0].showAndWait();
        // Remember sort state for next open
        browserSortMode = ref[0].getSortMode();
        browserSortAsc  = ref[0].isSortAsc();
    }

    public void loadFolder(Path folder) {
        config.lastFolder = folder.toString();
        config.addRecentFolder(folder.toString());
        setStatus("Scanning " + folder + " …");

        Thread t = new Thread(() -> {
            List<MediaFile> found = scanFolders(List.of(folder));
            applyStarredState(found);
            Platform.runLater(() -> {
                files.clear();
                files.addAll(found);
                slideshowQueue.clear();
                rebuildQueuePositions();
                thumbPanel.setFiles(List.copyOf(files));
                resetSidebarToPreset();
                if (!files.isEmpty()) navigateTo(0);
                else setStatus("No supported files found in " + folder);
            });
        }, "folder-scanner");
        t.setDaemon(true);
        t.start();

        startWatcher(List.of(folder));
    }

    private void loadPaths(List<Path> paths) {
        List<Path> droppedFiles = new ArrayList<>();
        List<Path> droppedDirs  = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) droppedDirs.add(p);
            else if (MediaFile.isSupported(p)) droppedFiles.add(p);
        }

        if (droppedDirs.isEmpty()) {
            // Only individual files dropped — load exactly those files, no folder expansion.
            if (droppedFiles.isEmpty()) return;
            List<MediaFile> found = droppedFiles.stream()
                    .map(MediaFile::new).collect(Collectors.toList());
            applyStarredState(found);
            files.clear();
            files.addAll(found);
            slideshowQueue.clear();
            rebuildQueuePositions();
            thumbPanel.setFiles(List.copyOf(files));
            resetSidebarToPreset();
            stopWatcher();
            navigateTo(0);
            return;
        }

        // At least one folder dropped — scan folders and add any individual files too.
        setStatus("Loading…");
        Thread t = new Thread(() -> {
            List<MediaFile> found = new ArrayList<>();
            Set<Path> dirsToWatch = new java.util.LinkedHashSet<>();
            for (Path dir : droppedDirs) {
                found.addAll(scanFolders(List.of(dir)));
                dirsToWatch.add(dir);
            }
            for (Path f : droppedFiles) {
                found.add(new MediaFile(f));
                if (f.getParent() != null) dirsToWatch.add(f.getParent());
            }
            applyStarredState(found);
            Platform.runLater(() -> {
                files.clear();
                files.addAll(found);
                slideshowQueue.clear();
                rebuildQueuePositions();
                thumbPanel.setFiles(List.copyOf(files));
                resetSidebarToPreset();
                if (!files.isEmpty()) navigateTo(0);
                startWatcher(new ArrayList<>(dirsToWatch));
            });
        }, "drop-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Clear all loaded files and return to the empty/welcome state. */
    private void clearAllFiles() {
        mediaPane.clearMedia();
        comparePane.clearMedia();
        files.clear();
        slideshowQueue.clear();
        rebuildQueuePositions();
        thumbPanel.setFiles(List.of());
        stopWatcher();
        config.lastFolder = null;
        config.save();
        setStatus("No folder open — use 📂 Open or drag files/folders here");
    }

    private static final Set<String> IGNORED_DIRS = Set.of("thumbnails", "subtitles", ".cache");

    private List<MediaFile> scanFolders(List<Path> dirs) {
        List<MediaFile> result = new ArrayList<>();
        for (Path dir : dirs) {
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        return IGNORED_DIRS.contains(d.getFileName().toString().toLowerCase())
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (MediaFile.isSupported(f)) result.add(new MediaFile(f));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) { System.err.println("[scan] " + e.getMessage()); }
        }
        result.sort(NATURAL_ORDER);
        return result;
    }

    /** Natural sort — delegates to ThumbnailPanel so both use identical logic. */
    private static final Comparator<MediaFile> NATURAL_ORDER =
            (a, b) -> ThumbnailPanel.naturalCompare(a.getFilename(), b.getFilename());

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateTo(int idx) {
        if (files.isEmpty() || idx < 0 || idx >= files.size()) return;
        currentIdx = idx;
        thumbPanel.select(idx);
        if (openBrowserDialog != null) openBrowserDialog.setCurrentIdxAndScroll(idx);
        loadIntoPane(files.get(idx), mediaPane, idx);
        stage.setTitle("Image & Video Viewer");
        MediaFile cur = files.get(idx);
        String folderName = cur.getPath().getParent() != null && cur.getPath().getParent().getFileName() != null
                ? cur.getPath().getParent().getFileName().toString() : "";
        Platform.runLater(() -> folderLabel.setText(folderName));
        refreshIdxLabel();
        setStatus(cur.getFilename());

        // Prefetch next image
        int nextIdx = (idx + 1) % files.size();
        if (!files.isEmpty() && files.get(nextIdx).isImage()) {
            mediaPane.prefetchImage(files.get(nextIdx).getPath());
        }

        // Reset slideshow progress bar on manual navigation
        if (slideshowRunning) resetSlideshowProgress();
    }

    public void nextFile() {
        if (!files.isEmpty()) navigateTo((currentIdx + 1) % files.size());
    }

    public void prevFile() {
        if (!files.isEmpty()) navigateTo((currentIdx - 1 + files.size()) % files.size());
    }

    // ── Slideshow ─────────────────────────────────────────────────────────────

    private void toggleSlideshow() {
        if (slideshowRunning) {
            stopSlideshow();
        } else {
            if (files.isEmpty()) return;
            slideshowRunning  = true;
            slideshowQueueIdx = -1;
            slideshowBtn.setText("⏹ Stop");
            slideshowProgress.setVisible(true);
            mediaPane.setAutoPlay(true);

            boolean hasQueue = !slideshowQueue.isEmpty();
            setStatus(hasQueue
                    ? String.format("Slideshow running (%d queued files)  (Ctrl+S to stop)", slideshowQueue.size())
                    : "Slideshow running — all files  (Ctrl+S to stop)");

            if (hasQueue) {
                // Jump immediately to the first queued file
                slideshowQueueIdx = 0;
                navigateTo(slideshowQueue.get(0));
                // autoPlay=true means showVideo() will not pause — video starts playing via setOnReady.
                // If the first item is an image we still need the interval timer.
                if (!files.get(slideshowQueue.get(0)).isVideo()) {
                    startSlideshowTimer();
                }
            } else {
                // No queue — work from the current file
                if (currentIdx >= 0 && currentIdx < files.size()
                        && files.get(currentIdx).isVideo()) {
                    // Video already loaded and paused — start it playing now
                    if (!mediaPane.isVideoPlaying()) mediaPane.togglePlayPause();
                    // onVideoEnded() will advance to the next file when it finishes
                } else {
                    startSlideshowTimer();
                }
            }
        }
    }

    private void stopSlideshow() {
        if (slideshowTimer != null) { slideshowTimer.stop(); slideshowTimer = null; }
        if (slideshowProgressTimer != null) { slideshowProgressTimer.stop(); slideshowProgressTimer = null; }
        slideshowRunning = false;
        mediaPane.setAutoPlay(false);
        slideshowBtn.setText("⏵ Slideshow");
        slideshowProgress.setProgress(0);
        slideshowProgress.setVisible(false);
        setStatus("Slideshow stopped");
    }

    /** Build a new slideshow timer using the current interval. */
    private void startSlideshowTimer() {
        if (slideshowTimer != null) slideshowTimer.stop();
        int interval = config.slideshowInterval;

        // Check whether current file is a video — if so, wait for it to end
        if (currentIdx >= 0 && currentIdx < files.size() && files.get(currentIdx).isVideo()) {
            // Timer will be started by onVideoEnded callback instead
            setStatus("Slideshow: waiting for video to end…");
            return;
        }

        slideshowTimer = new Timeline(
                new KeyFrame(Duration.seconds(interval), e -> slideshowAdvance()));
        slideshowTimer.setCycleCount(Timeline.INDEFINITE);
        slideshowTimer.play();
        startSlideshowProgressAnimation(interval);
    }

    private void restartSlideshowTimer() {
        stopSlideshow();
        if (!files.isEmpty()) toggleSlideshow();
    }

    /**
     * Builds (or rebuilds) the pre-shuffled deck from the current queue or file list.
     * Ensures the file currently showing is not the first in the new deck so there
     * is no immediate repeat at the start of a new pass.
     */
    private void rebuildShuffleOrder() {
        List<Integer> pool;
        if (!slideshowQueue.isEmpty()) {
            pool = new ArrayList<>(slideshowQueue);          // file indices from queue
        } else {
            pool = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) pool.add(i);
        }
        Collections.shuffle(pool);
        // Move the currently-showing file away from front to avoid an immediate repeat
        if (pool.size() > 1 && !pool.isEmpty() && pool.get(0).equals(currentIdx)) {
            pool.add(pool.remove(0));
        }
        shuffleOrder = pool;
        shufflePos   = 0;
    }

    /** Advance to the next file; called by timer or end-of-video. */
    private void slideshowAdvance() {
        if (slideshowShuffle) {
            // Deck exhausted — either stop (loop off) or deal a fresh shuffled deck
            if (shufflePos >= shuffleOrder.size()) {
                if (!slideshowLoop && !shuffleOrder.isEmpty()) {
                    stopSlideshow(); setStatus("Slideshow finished"); return;
                }
                rebuildShuffleOrder();
            }
            navigateTo(shuffleOrder.get(shufflePos++));
        } else if (!slideshowQueue.isEmpty()) {
            slideshowNextQueued();
        } else {
            // Stop after last file if loop is off
            if (!slideshowLoop && currentIdx >= files.size() - 1) {
                stopSlideshow(); setStatus("Slideshow finished"); return;
            }
            nextFile();
        }
        resetSlideshowProgress();
        // If next file is a video, pause the timer until the video ends
        if (currentIdx >= 0 && currentIdx < files.size() && files.get(currentIdx).isVideo()) {
            if (slideshowTimer != null) { slideshowTimer.stop(); slideshowTimer = null; }
            if (slideshowProgressTimer != null) { slideshowProgressTimer.stop(); }
            slideshowProgress.setProgress(0);
            setStatus("Slideshow: waiting for video to end…");
        }
    }

    /** Called by MediaPane when the current video reaches its natural end. */
    private void onVideoEnded() {
        if (compareMode) updateSyncPlayPauseBtn();
        if (!slideshowRunning) return;
        // Advance immediately — no delay between videos
        slideshowAdvance();
        // If the next file is an image, start the interval timer.
        // If it's a video, autoPlay=true means showVideo() will start it automatically.
        if (currentIdx >= 0 && currentIdx < files.size() && !files.get(currentIdx).isVideo()) {
            startSlideshowTimer();
        }
    }

    private void resetSlideshowProgress() {
        slideshowElapsed = 0;
        slideshowProgress.setProgress(0);
    }

    private void startSlideshowProgressAnimation(int totalSeconds) {
        if (slideshowProgressTimer != null) slideshowProgressTimer.stop();
        slideshowElapsed = 0;
        // Update ~20 fps
        slideshowProgressTimer = new Timeline(new KeyFrame(Duration.millis(50), e -> {
            slideshowElapsed += 0.05;
            slideshowProgress.setProgress(Math.min(1.0, slideshowElapsed / totalSeconds));
        }));
        slideshowProgressTimer.setCycleCount(Timeline.INDEFINITE);
        slideshowProgressTimer.play();
    }

    // ── File system watcher ───────────────────────────────────────────────────

    private void startWatcher(List<Path> folders) {
        stopWatcher();
        if (folders.isEmpty()) return;
        watchedFolders = new ArrayList<>(folders);
        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (Path folder : folders) {
                folder.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
            }
            watchExecutor.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        WatchKey key = watchService.take();
                        key.pollEvents(); // drain events
                        key.reset();
                        scheduleRescan();
                    }
                } catch (InterruptedException | ClosedWatchServiceException ignored) {}
            });
        } catch (IOException e) {
            System.err.println("[watcher] " + e.getMessage());
        }
    }

    private synchronized void stopWatcher() {
        if (watchRescanFuture != null) { watchRescanFuture.cancel(false); watchRescanFuture = null; }
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
            watchService = null;
        }
        watchedFolders.clear();
    }

    /** Debounce: wait 500 ms after the last change before rescanning all watched folders. */
    private synchronized void scheduleRescan() {
        if (watchRescanFuture != null && !watchRescanFuture.isDone())
            watchRescanFuture.cancel(false);
        List<Path> toScan = new ArrayList<>(watchedFolders);
        watchRescanFuture = watchExecutor.schedule(() -> {
            List<MediaFile> found = scanFolders(toScan);
            Platform.runLater(() -> {
                // Preserve current selection if possible
                String currentName = (currentIdx >= 0 && currentIdx < files.size())
                        ? files.get(currentIdx).getFilename() : null;
                files.clear();
                files.addAll(found);
                thumbPanel.setFiles(List.copyOf(files));
                // Re-select by name
                if (currentName != null) {
                    for (int i = 0; i < files.size(); i++) {
                        if (files.get(i).getFilename().equals(currentName)) {
                            final int fi = i;
                            currentIdx = fi;
                            thumbPanel.select(fi);
                            break;
                        }
                    }
                }
                setStatus("Folder refreshed — " + files.size() + " files");
            });
        }, 500, TimeUnit.MILLISECONDS);
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private void cycleTheme() {
        String next = theme.nextTheme();
        theme.applyTheme(scene, next);
        if (presentationStage != null) theme.applyTheme(presentationStage.getScene(), next);
        config.theme = next;
        setStatus("Theme: " + next);
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private void openSettings() {
        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.getIcons().addAll(stage.getIcons());

        // Theme
        Label themeLabel = new Label("Theme:");
        ChoiceBox<String> themeChoice = new ChoiceBox<>();
        themeChoice.getItems().addAll(ThemeManager.THEME_NAMES);
        themeChoice.setValue(theme.getCurrentTheme());
        themeChoice.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            theme.applyTheme(scene, n);
            if (presentationStage != null) theme.applyTheme(presentationStage.getScene(), n);
            config.theme = n;
        });

        // Transition
        Label transLabel = new Label("Image transition:");
        ChoiceBox<String> transChoice = new ChoiceBox<>();
        transChoice.getItems().addAll("None", "Fade", "Dip to Black");
        // Migrate legacy stored value
        if ("DipToBlack".equals(config.transitionMode)) config.transitionMode = "Dip to Black";
        transChoice.setValue(config.transitionMode);
        transChoice.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            config.transitionMode = n;
            applyTransitionModeFromConfig();
        });

        // Loop
        CheckBox loopCheck = new CheckBox("Loop videos by default");
        loopCheck.setSelected(config.loopVideo);
        loopCheck.setOnAction(e -> {
            config.loopVideo = loopCheck.isSelected();
            mediaPane.setLoopEnabled(config.loopVideo);
            comparePane.setLoopEnabled(config.loopVideo);
        });

        // Slideshow interval
        Label intLabel = new Label("Slideshow interval (s):");
        Spinner<Integer> intSpinner = new Spinner<>(1, 300, config.slideshowInterval, 1);
        intSpinner.setPrefWidth(80);
        intSpinner.valueProperty().addListener((obs, o, n) -> {
            config.slideshowInterval = n;
            slideshowSpinner.getValueFactory().setValue(n);
        });

        // RAW decode toggle
        CheckBox rawEmbeddedCheck = new CheckBox("Prefer embedded JPEG preview for RAW files (faster, no ImageMagick needed)");
        rawEmbeddedCheck.setSelected("EmbeddedFirst".equals(config.rawDecodeMode));
        rawEmbeddedCheck.setOnAction(e -> {
            config.rawDecodeMode = rawEmbeddedCheck.isSelected() ? "EmbeddedFirst" : "ImageMagickFirst";
            MediaPane.setRawDecodeMode(config.rawDecodeMode);
        });

        // Alpha background toggle
        CheckBox alphaCheck = new CheckBox("Show checkerboard for transparent areas in alpha .mov / .webp");
        alphaCheck.setSelected(config.alphaCheckerboard);
        alphaCheck.setOnAction(e -> {
            config.alphaCheckerboard = alphaCheck.isSelected();
            mediaPane.setAlphaCheckerboard(config.alphaCheckerboard);
            comparePane.setAlphaCheckerboard(config.alphaCheckerboard);
        });

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 8, 20));
        grid.add(themeLabel,       0, 0); grid.add(themeChoice,      1, 0);
        grid.add(transLabel,       0, 1); grid.add(transChoice,       1, 1);
        grid.add(loopCheck,        0, 2, 2, 1);
        grid.add(intLabel,         0, 3); grid.add(intSpinner,        1, 3);
        grid.add(new javafx.scene.control.Separator(), 0, 4, 2, 1);
        grid.add(rawEmbeddedCheck, 0, 5, 2, 1);
        grid.add(alphaCheck,       0, 6, 2, 1);

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("toolbar-btn");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> dlg.close());

        VBox root = new VBox(0, makeTitleBar("Settings", dlg, buildSettingsIcon()), grid, makeDialogButtonBar(closeBtn));
        Scene sc = new Scene(root);
        if (scene != null) sc.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(sc);
        dlg.setOnHidden(e -> config.save());
        dlg.setOnShown(e -> centerOnOwner(dlg, stage));
        dlg.showAndWait();
    }

    private void applyTransitionModeFromConfig() {
        MediaPane.TransitionMode mode = switch (config.transitionMode) {
            case "Fade"                    -> MediaPane.TransitionMode.FADE;
            case "DipToBlack", "Dip to Black" -> MediaPane.TransitionMode.DIP_TO_BLACK;
            default                        -> MediaPane.TransitionMode.NONE;
        };
        mediaPane.setTransitionMode(mode);
        comparePane.setTransitionMode(mode);
    }

    // ── Shortcuts help ────────────────────────────────────────────────────────

    /** All keyboard shortcuts: {section, keys, description}. */
    private static final String[][] ALL_SHORTCUTS = {
        { "Navigation",   "← / →  or  PgUp / PgDn",   "Previous / Next file"                    },
        { "Navigation",   "Home / End",                 "First / Last file"                       },
        { "Navigation",   "Alt+← / Alt+→",             "Navigate Compare pane"                   },
        { "Navigation",   "O  or  Ctrl+O",               "Open folder dialog"                      },
        { "Image",        "+ / −",                      "Zoom in / out"                           },
        { "Image",        "0",                          "Zoom to fit"                             },
        { "Image",        "R",                          "Rotate right 90°"                        },
        { "Image",        "L",                          "Rotate left 90°"                         },
        { "Image",        "Ctrl+C",                     "Copy image to clipboard"                 },
        { "Image",        "Ctrl+V",                     "Paste image from clipboard"              },
        { "Image",        "Ctrl+P",                     "Print current image / video frame"       },
        { "Image",        "Ctrl+I",                     "File info / EXIF metadata"               },
        { "Image",        "Delete",                     "Delete current file (with confirmation)" },
        { "Video",        "Space",                      "Play / Pause"                            },
        { "Video",        "⏮ ⏪ ⏩ 🔁 🕐 (controls)",   "Restart, skip ±10s, loop, seek"          },
        { "Slideshow",    "Ctrl+S",                     "Start / Stop slideshow"                  },
        { "Slideshow",    "Ctrl+click thumbnail",       "Add / Remove from queue"                 },
        { "Slideshow",    "Shift+click thumbnail",      "Add range to queue"                      },
        { "Slideshow",    "📋 Manage Queue (toolbar)",  "Reorder / remove queued files"           },
        { "Compare mode", "C",                          "Toggle compare mode"                     },
        { "Compare mode", "Alt+click thumbnail",        "Load into compare pane"                  },
        { "UI",           "B",                          "Open Thumbnail Browser"                  },
        { "UI",           "F  or  F11",                 "Toggle fullscreen"                       },
        { "UI",           "D",                          "Toggle dual-display mode"                },
        { "UI",           "T",                          "Cycle theme"                             },
        { "UI",           "Escape",                     "Exit fullscreen"                         },
        { "UI",           "? or /",                     "This help dialog"                        },
    };

    /** Formats a filtered subset of ALL_SHORTCUTS as monospace text for the TextArea. */
    private static String buildShortcutText(String[][] entries) {
        if (entries.length == 0) return "  No matching shortcuts.";
        StringBuilder sb = new StringBuilder();
        String lastSection = null;
        for (String[] e : entries) {
            if (!e[0].equals(lastSection)) {
                if (lastSection != null) sb.append('\n');
                sb.append(e[0]).append('\n');
                sb.append("──────────────────────────────────────────\n");
                lastSection = e[0];
            }
            sb.append(String.format("  %-36s %s%n", e[1], e[2]));
        }
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == '\n') len--;
        return sb.substring(0, len);
    }

    private void showShortcutsHelp() {
        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.getIcons().addAll(stage.getIcons());

        TextArea ta = new TextArea(buildShortcutText(ALL_SHORTCUTS));
        ta.setEditable(false);
        ta.setWrapText(false);
        ta.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        VBox.setVgrow(ta, Priority.ALWAYS);

        // ── Search field ──────────────────────────────────────────────────────
        TextField searchField = new TextField();
        searchField.setPromptText("Filter shortcuts…");
        HBox searchBar = new HBox(searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBar.setPadding(new Insets(6, 8, 4, 8));

        searchField.textProperty().addListener((obs, old, query) -> {
            String q = query.trim().toLowerCase();
            if (q.isEmpty()) {
                ta.setText(buildShortcutText(ALL_SHORTCUTS));
                return;
            }
            // A section whose name matches gets all its entries included
            Set<String> matchedSections = new HashSet<>();
            for (String[] e : ALL_SHORTCUTS) {
                if (e[0].toLowerCase().contains(q)) matchedSections.add(e[0]);
            }
            String[][] filtered = Arrays.stream(ALL_SHORTCUTS)
                    .filter(e -> matchedSections.contains(e[0])
                              || e[1].toLowerCase().contains(q)
                              || e[2].toLowerCase().contains(q))
                    .toArray(String[][]::new);
            ta.setText(buildShortcutText(filtered));
        });

        // First Escape clears the search; second Escape falls through to Close (cancel button)
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !searchField.getText().isEmpty()) {
                searchField.clear();
                e.consume();
            }
        });

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("toolbar-btn");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> dlg.close());

        VBox root = new VBox(0, makeTitleBar("Keyboard Shortcuts", dlg, buildKeyboardIcon()),
                searchBar, ta, makeDialogButtonBar(closeBtn));
        Scene sc = new Scene(root, 500, 480);
        if (scene != null) sc.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(sc);
        dlg.setOnShown(e -> { centerOnOwner(dlg, stage); searchField.requestFocus(); });
        dlg.showAndWait();
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    /** Updates [N / M] using the thumbnail panel's current display position and count. */
    private void refreshIdxLabel() {
        Platform.runLater(() -> {
            if (currentIdx < 0 || files.isEmpty()) { idxLabel.setText(""); return; }
            int displayIdx   = thumbPanel.getDisplayIndex(currentIdx);
            int displayTotal = thumbPanel.getDisplayCount();
            if (displayIdx > 0) {
                idxLabel.setText(String.format("[%d / %d]", displayIdx, displayTotal));
            } else {
                // current file is filtered out of the view
                idxLabel.setText(String.format("[– / %d]", displayTotal));
            }
        });
    }

    // ── Copy to clipboard ─────────────────────────────────────────────────────

    private void copyCurrentImageToClipboard() {
        javafx.scene.image.Image img = mediaPane.getCurrentImage();
        if (img == null) {
            setStatus("Nothing to copy — open an image first");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putImage(img);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("Image copied to clipboard");
    }

    // ── File info / EXIF viewer ───────────────────────────────────────────────

    /** Build a programmatic info icon (16×16) for the File Info dialog title bar. */
    private static WritableImage buildInfoIcon() {
        Canvas c = new Canvas(16, 16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#5a9fd4"));
        g.fillOval(0, 0, 15, 15);
        g.setFill(Color.web("#1a1a1a"));
        g.fillOval(6, 3, 3, 3);    // dot
        g.fillRect(6, 7, 3, 6);    // stem
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    private void showFileInfo() {
        if (files.isEmpty() || currentIdx < 0 || currentIdx >= files.size()) return;
        MediaFile cur = files.get(currentIdx);
        Path path = cur.getPath();

        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.getIcons().addAll(stage.getIcons());

        // ── Basic file attributes ─────────────────────────────────────────────
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"File name", cur.getFilename()});
        rows.add(new String[]{"Type", cur.getType().toString()});
        try {
            rows.add(new String[]{"Size", formatFileSize(java.nio.file.Files.size(path))});
            java.nio.file.attribute.BasicFileAttributes attrs =
                    java.nio.file.Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class);
            rows.add(new String[]{"Modified",
                    new java.text.SimpleDateFormat("yyyy-MM-dd  HH:mm:ss")
                            .format(new java.util.Date(attrs.lastModifiedTime().toMillis()))});
            rows.add(new String[]{"Created",
                    new java.text.SimpleDateFormat("yyyy-MM-dd  HH:mm:ss")
                            .format(new java.util.Date(attrs.creationTime().toMillis()))});
        } catch (IOException ex) {
            rows.add(new String[]{"File attributes", "unavailable"});
        }

        // ── EXIF / image metadata via metadata-extractor ─────────────────────
        if (cur.isImage()) {
            try {
                com.drew.metadata.Metadata meta =
                        com.drew.imaging.ImageMetadataReader.readMetadata(path.toFile());

                // Image dimensions from EXIF / JPEG / PNG directories
                rows.add(new String[]{"─── Image ───", ""});
                for (com.drew.metadata.Directory dir : meta.getDirectories()) {
                    // Surface a curated list of useful tags
                    for (com.drew.metadata.Tag tag : dir.getTags()) {
                        String tagName = tag.getTagName();
                        if (isExifTagUseful(tagName)) {
                            rows.add(new String[]{tagName, tag.getDescription()});
                        }
                    }
                }
            } catch (Exception ex) {
                rows.add(new String[]{"EXIF", "not available (" + ex.getMessage() + ")"});
            }
        } else if (cur.isVideo()) {
            // Basic video info via JavaCPP avformat
            rows.add(new String[]{"─── Video ───", ""});
            try {
                org.bytedeco.ffmpeg.avformat.AVFormatContext fmtCtx =
                        org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context();
                if (org.bytedeco.ffmpeg.global.avformat.avformat_open_input(
                        fmtCtx, path.toString(), null, null) >= 0) {
                    org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info(
                            fmtCtx, (org.bytedeco.javacpp.PointerPointer) null);
                    long durationSec = fmtCtx.duration() / 1_000_000L;
                    rows.add(new String[]{"Duration",
                            String.format("%d:%02d:%02d", durationSec / 3600,
                                    (durationSec % 3600) / 60, durationSec % 60)});
                    rows.add(new String[]{"Streams",
                            String.valueOf(fmtCtx.nb_streams())});
                    for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                        org.bytedeco.ffmpeg.avcodec.AVCodecParameters cp =
                                fmtCtx.streams(i).codecpar();
                        int type = cp.codec_type();
                        String streamType = type == org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
                                ? "Video" : type == org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
                                ? "Audio" : "Other";
                        if (type == org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO) {
                            rows.add(new String[]{streamType + " " + i,
                                    cp.width() + "×" + cp.height()});
                        } else {
                            rows.add(new String[]{streamType + " " + i,
                                    cp.channels() + "ch  " + cp.sample_rate() + " Hz"});
                        }
                    }
                    org.bytedeco.ffmpeg.global.avformat.avformat_close_input(fmtCtx);
                }
            } catch (Exception ex) {
                rows.add(new String[]{"Video info", "unavailable"});
            }
        }

        // ── Build table ───────────────────────────────────────────────────────
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(5);
        grid.setPadding(new Insets(12, 16, 12, 16));
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            boolean isSep = row[0].startsWith("─");
            if (isSep) {
                Label sep = new Label(row[0]);
                sep.setStyle("-fx-text-fill: #666; -fx-font-size: 10px; -fx-padding: 6 0 2 0;");
                grid.add(sep, 0, i, 2, 1);
            } else {
                Label key = new Label(row[0]);
                key.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
                key.setMinWidth(140);
                Label val = new Label(row[1] != null ? row[1] : "");
                val.setStyle("-fx-font-size: 11px;");
                val.setWrapText(true);
                val.setMaxWidth(280);
                grid.add(key, 0, i);
                grid.add(val, 1, i);
            }
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.setPrefViewportHeight(400);

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("toolbar-btn");
        closeBtn.setCancelButton(true);
        closeBtn.setDefaultButton(true);
        closeBtn.setOnAction(e -> dlg.close());

        VBox root = new VBox(0,
                makeTitleBar("File Info — " + cur.getFilename(), dlg, buildInfoIcon()),
                scroll,
                makeDialogButtonBar(closeBtn));
        Scene sc = new Scene(root, 480, 500);
        if (scene != null) sc.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(sc);
        dlg.setResizable(true);
        dlg.setOnShown(e -> centerOnOwner(dlg, stage));
        dlg.showAndWait();
    }

    /** Tags worth surfacing in the EXIF viewer (filters noise like thumbnail bytes). */
    private static boolean isExifTagUseful(String tagName) {
        return switch (tagName) {
            case "Image Width", "Image Height", "Exif Image Width", "Exif Image Height",
                 "Date/Time", "Date/Time Original", "Date/Time Digitized",
                 "Make", "Model", "Software",
                 "F-Number", "Exposure Time", "ISO Speed Ratings",
                 "Focal Length", "Focal Length 35", "Lens Model",
                 "GPS Latitude", "GPS Longitude", "GPS Altitude",
                 "Color Space", "Bits Per Sample", "Compression",
                 "Orientation", "X Resolution", "Y Resolution",
                 "Artist", "Copyright", "Image Description",
                 "Flash", "White Balance", "Metering Mode", "Exposure Program",
                 "Shutter Speed Value", "Aperture Value" -> true;
            default -> false;
        };
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ── Print ─────────────────────────────────────────────────────────────────

    private void printCurrentFile() {
        javafx.scene.image.Image img = mediaPane.getCurrentDisplayImage();
        if (img == null) { setStatus("Nothing to print"); return; }

        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) { setStatus("No printer available"); return; }

        // Show the system print dialog — user picks printer and settings
        boolean proceed = job.showPrintDialog(stage);
        if (!proceed) return;

        // Scale the image to fit the printable page while preserving aspect ratio
        javafx.print.PageLayout layout = job.getJobSettings().getPageLayout();
        double pageW = layout.getPrintableWidth();
        double pageH = layout.getPrintableHeight();
        double imgW  = img.getWidth();
        double imgH  = img.getHeight();
        if (imgW <= 0 || imgH <= 0) { setStatus("Image dimensions unknown — cannot print"); return; }

        double scale = Math.min(pageW / imgW, pageH / imgH);
        ImageView printView = new ImageView(img);
        printView.setFitWidth(imgW * scale);
        printView.setFitHeight(imgH * scale);
        printView.setPreserveRatio(true);
        printView.setSmooth(true);

        boolean printed = job.printPage(printView);
        if (printed) {
            job.endJob();
            setStatus("Sent to printer");
        } else {
            setStatus("Print failed");
        }
    }

    // ── Rename file ───────────────────────────────────────────────────────────

    private void renameFile(int idx) {
        if (files.isEmpty() || idx < 0 || idx >= files.size()) return;
        MediaFile cur = files.get(idx);
        String oldName = cur.getFilename();

        // Build a small inline dialog for the new name
        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.getIcons().addAll(stage.getIcons());

        Label lbl = new Label("New filename:");
        lbl.setStyle("-fx-font-size: 12px;");
        TextField nameField = new TextField(oldName);
        nameField.setPrefWidth(360);
        // Select the stem (everything before the last dot) for convenience
        Platform.runLater(() -> {
            int dot = oldName.lastIndexOf('.');
            nameField.selectRange(0, dot > 0 ? dot : oldName.length());
        });

        VBox content = new VBox(8, lbl, nameField);
        content.setPadding(new Insets(16, 20, 8, 20));

        final boolean[] confirmed = {false};
        Button okBtn = new Button("Rename");
        okBtn.getStyleClass().add("toolbar-btn");
        okBtn.setDefaultButton(true);
        okBtn.setOnAction(e -> { confirmed[0] = true; dlg.close(); });
        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("toolbar-btn");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dlg.close());

        // Also allow Enter in the text field to confirm
        nameField.setOnAction(e -> { confirmed[0] = true; dlg.close(); });

        VBox root = new VBox(0, makeTitleBar("Rename File", dlg), content,
                makeDialogButtonBar(okBtn, cancelBtn));
        Scene sc = new Scene(root);
        if (scene != null) sc.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(sc);
        dlg.setOnShown(e -> centerOnOwner(dlg, stage));
        dlg.showAndWait();
        if (!confirmed[0]) return;

        String newName = nameField.getText().trim();
        if (newName.isEmpty() || newName.equals(oldName)) return;

        Path oldPath = cur.getPath();
        Path newPath = oldPath.resolveSibling(newName);
        if (java.nio.file.Files.exists(newPath)) {
            setStatus("Rename failed: a file named \"" + newName + "\" already exists");
            return;
        }
        try {
            java.nio.file.Files.move(oldPath, newPath);
        } catch (IOException ex) {
            setStatus("Rename failed: " + ex.getMessage());
            return;
        }

        // Rename sidecar thumbnail (if any)
        ThumbnailPanel.renameSidecarThumbnail(oldPath, newPath);

        // Update the MediaFile entry in the list
        MediaFile updated = new MediaFile(newPath);
        updated.setThumbnail(cur.getThumbnail()); // carry cached thumbnail across rename
        files.set(idx, updated);
        thumbPanel.updateFile(idx, updated);

        // If we renamed the currently displayed file, reload it
        if (idx == currentIdx) {
            loadIntoPane(updated, mediaPane, idx);
            setStatus("Renamed → " + newName);
        } else {
            setStatus("Renamed \"" + oldName + "\" → \"" + newName + "\"");
        }
        config.save();
    }

    // ── Batch delete ─────────────────────────────────────────────────────────

    private void batchDeleteFiles(List<Integer> indices) {
        if (indices.isEmpty()) return;
        int n = indices.size();

        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.getIcons().addAll(stage.getIcons());

        Label heading = new Label("Delete " + n + " file" + (n > 1 ? "s" : "") + "?");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label body = new Label("This will permanently delete " + n + " file" +
                (n > 1 ? "s" : "") + " from disk.\nThis action cannot be undone.");
        body.setStyle("-fx-text-fill: #aaa;");

        // Show up to 5 filenames as a preview
        StringBuilder preview = new StringBuilder();
        int shown = Math.min(5, n);
        for (int i = 0; i < shown; i++) {
            int idx = indices.get(i);
            if (idx >= 0 && idx < files.size())
                preview.append("  • ").append(files.get(idx).getFilename()).append("\n");
        }
        if (n > 5) preview.append("  … and ").append(n - 5).append(" more");
        Label fileList = new Label(preview.toString().stripTrailing());
        fileList.setStyle("-fx-font-size: 11px; -fx-text-fill: #bbb;");

        VBox msg = new VBox(6, heading, body, fileList);
        msg.setPadding(new Insets(16, 20, 8, 20));

        final boolean[] confirmed = {false};
        Button okBtn = new Button("Delete " + n + " files");
        okBtn.getStyleClass().add("toolbar-btn");
        okBtn.setStyle("-fx-base: #c42b1c;");
        okBtn.setDefaultButton(true);
        okBtn.setOnAction(e -> { confirmed[0] = true; dlg.close(); });
        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("toolbar-btn");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dlg.close());

        VBox root = new VBox(0, makeTitleBar("Delete Files", dlg, buildDeleteIcon()), msg,
                makeDialogButtonBar(okBtn, cancelBtn));
        Scene sc = new Scene(root);
        if (scene != null) sc.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(sc);
        dlg.setOnShown(e -> centerOnOwner(dlg, stage));
        dlg.showAndWait();
        if (!confirmed[0]) return;

        // Delete in reverse index order so indices stay valid as we remove
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort(Comparator.reverseOrder());
        int deleted = 0, failed = 0;
        for (int idx : sorted) {
            if (idx < 0 || idx >= files.size()) continue;
            MediaFile f = files.get(idx);
            try {
                java.nio.file.Files.delete(f.getPath());
                ThumbnailPanel.deleteSidecarThumbnail(f.getPath());
                if (f.isVideo() && idx == currentIdx) mediaPane.stopVideo();
                files.remove(idx);
                if (idx < currentIdx) currentIdx--;
                else if (idx == currentIdx) currentIdx = Math.min(currentIdx, files.size() - 1);
                deleted++;
            } catch (IOException ex) {
                failed++;
                System.err.println("[batch-delete] " + f.getFilename() + ": " + ex.getMessage());
            }
        }

        slideshowQueue.clear();
        rebuildQueuePositions();
        if (files.isEmpty()) {
            currentIdx = -1;
            thumbPanel.setFiles(List.of());
            mediaPane.clearMedia();
            refreshIdxLabel();
        } else {
            thumbPanel.setFiles(List.copyOf(files));
            if (currentIdx >= 0) navigateTo(Math.min(currentIdx, files.size() - 1));
        }
        String msg2 = "Deleted " + deleted + " file" + (deleted != 1 ? "s" : "");
        setStatus(failed > 0 ? msg2 + "  (" + failed + " failed)" : msg2);
    }

    // ── Batch rename ──────────────────────────────────────────────────────────

    private void batchRenameFiles(List<Integer> indices) {
        if (indices.isEmpty()) return;
        int n = indices.size();

        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.getIcons().addAll(stage.getIcons());

        Label patternLbl = new Label("Name pattern  (use {n} for counter):");
        patternLbl.setStyle("-fx-font-size: 12px;");
        TextField patternField = new TextField("photo_{n}");
        patternField.setPrefWidth(240);

        Label startLbl = new Label("Start at:");
        Spinner<Integer> startSpinner = new Spinner<>(1, 9999, 1, 1);
        startSpinner.setPrefWidth(75);
        startSpinner.setEditable(true);

        Label digitsLbl = new Label("Digits:");
        Spinner<Integer> digitsSpinner = new Spinner<>(1, 6, 3, 1);
        digitsSpinner.setPrefWidth(65);

        // Live preview
        Label previewLbl = new Label("Preview:");
        previewLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        Label previewText = new Label();
        previewText.setStyle("-fx-font-size: 11px; -fx-text-fill: #bbb;");

        Runnable updatePreview = () -> {
            String pattern = patternField.getText().trim();
            int start  = startSpinner.getValue();
            int digits = digitsSpinner.getValue();
            String fmt = "%0" + digits + "d";
            StringBuilder sb = new StringBuilder();
            int show = Math.min(4, n);
            for (int i = 0; i < show; i++) {
                int idx = indices.get(i);
                if (idx < 0 || idx >= files.size()) continue;
                String oldName = files.get(idx).getFilename();
                String oldExt  = oldName.contains(".")
                        ? oldName.substring(oldName.lastIndexOf('.')) : "";
                String counter = String.format(fmt, start + i);
                String newName = pattern.replace("{n}", counter) + oldExt;
                sb.append("  ").append(oldName).append("  →  ").append(newName).append("\n");
            }
            if (n > 4) sb.append("  … and ").append(n - 4).append(" more");
            previewText.setText(sb.toString().stripTrailing());
        };
        patternField.textProperty().addListener((obs, o, v) -> updatePreview.run());
        startSpinner.valueProperty().addListener((obs, o, v) -> updatePreview.run());
        digitsSpinner.valueProperty().addListener((obs, o, v) -> updatePreview.run());
        updatePreview.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        grid.setPadding(new Insets(16, 20, 8, 20));
        grid.add(patternLbl,   0, 0, 3, 1);
        grid.add(patternField, 0, 1, 3, 1);
        HBox numRow = new HBox(10,
                startLbl, startSpinner,
                new Label("  "), digitsLbl, digitsSpinner);
        numRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(numRow, 0, 2, 3, 1);
        grid.add(previewLbl,  0, 3, 3, 1);
        grid.add(previewText, 0, 4, 3, 1);

        final boolean[] confirmed = {false};
        Button okBtn = new Button("Rename " + n + " files");
        okBtn.getStyleClass().add("toolbar-btn");
        okBtn.setDefaultButton(true);
        okBtn.setOnAction(e -> { confirmed[0] = true; dlg.close(); });
        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("toolbar-btn");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dlg.close());

        VBox root = new VBox(0, makeTitleBar("Batch Rename — " + n + " files", dlg), grid,
                makeDialogButtonBar(okBtn, cancelBtn));
        Scene sc = new Scene(root);
        if (scene != null) sc.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(sc);
        dlg.setOnShown(e -> centerOnOwner(dlg, stage));
        dlg.showAndWait();
        if (!confirmed[0]) return;

        String pattern = patternField.getText().trim();
        if (pattern.isEmpty()) return;
        int start  = startSpinner.getValue();
        int digits = digitsSpinner.getValue();
        String fmt = "%0" + digits + "d";

        int renamed = 0, failed = 0;
        for (int i = 0; i < n; i++) {
            int idx = indices.get(i);
            if (idx < 0 || idx >= files.size()) continue;
            MediaFile cur = files.get(idx);
            String oldName = cur.getFilename();
            String ext     = oldName.contains(".") ? oldName.substring(oldName.lastIndexOf('.')) : "";
            String newName = pattern.replace("{n}", String.format(fmt, start + i)) + ext;
            Path oldPath = cur.getPath();
            Path newPath = oldPath.resolveSibling(newName);
            if (java.nio.file.Files.exists(newPath) && !newPath.equals(oldPath)) { failed++; continue; }
            try {
                java.nio.file.Files.move(oldPath, newPath);
                ThumbnailPanel.renameSidecarThumbnail(oldPath, newPath);
                MediaFile updated = new MediaFile(newPath);
                updated.setThumbnail(cur.getThumbnail());
                files.set(idx, updated);
                thumbPanel.updateFile(idx, updated);
                if (idx == currentIdx) loadIntoPane(updated, mediaPane, idx);
                renamed++;
            } catch (IOException ex) {
                failed++;
                System.err.println("[batch-rename] " + oldName + ": " + ex.getMessage());
            }
        }

        slideshowQueue.clear();
        rebuildQueuePositions();
        String msg = "Renamed " + renamed + " file" + (renamed != 1 ? "s" : "");
        setStatus(failed > 0 ? msg + "  (" + failed + " skipped — name conflict)" : msg);
        config.save();
    }

    // ── Copy / Move file to folder ────────────────────────────────────────────

    private void copyFileTo(int idx) {
        if (files.isEmpty() || idx < 0 || idx >= files.size()) return;
        Path src = files.get(idx).getPath();
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("Copy to…");
        if (src.getParent() != null) dc.setInitialDirectory(src.getParent().toFile());
        java.io.File dest = dc.showDialog(stage);
        if (dest == null) return;
        Path target = dest.toPath().resolve(src.getFileName());
        try {
            Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setStatus("Copied to " + dest.getName() + "  ✓");
        } catch (IOException e) {
            setStatus("Copy failed: " + e.getMessage());
        }
    }

    private void moveFileTo(int idx) {
        if (files.isEmpty() || idx < 0 || idx >= files.size()) return;
        Path src = files.get(idx).getPath();
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("Move to…");
        if (src.getParent() != null) dc.setInitialDirectory(src.getParent().toFile());
        java.io.File dest = dc.showDialog(stage);
        if (dest == null) return;
        Path target = dest.toPath().resolve(src.getFileName());
        try {
            Files.move(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ThumbnailPanel.deleteSidecarThumbnail(src);
            // Remove the moved file from the current list
            files.remove(idx);
            slideshowQueue.remove((Integer) idx);
            rebuildQueuePositions();
            thumbPanel.setFiles(List.copyOf(files));
            if (!files.isEmpty()) {
                int next = Math.min(idx, files.size() - 1);
                navigateTo(next);
            } else {
                currentIdx = -1;
                mediaPane.clearMedia();
                refreshIdxLabel();
            }
            setStatus("Moved to " + dest.getName() + "  ✓");
        } catch (IOException e) {
            setStatus("Move failed: " + e.getMessage());
        }
    }

    // ── Star / unstar file ────────────────────────────────────────────────────

    private void toggleStar(int idx) {
        if (files.isEmpty() || idx < 0 || idx >= files.size()) return;
        MediaFile mf  = files.get(idx);
        boolean   now = !mf.isStarred();
        mf.setStarred(now);
        config.setStarred(mf.getPath(), now);
        config.save();
        thumbPanel.refreshStarState();
        setStatus(now ? "⭐ Starred: " + mf.getFilename() : "Unstarred: " + mf.getFilename());
    }

    /** Apply starred state from config to every file in the list. */
    private void applyStarredState(List<MediaFile> list) {
        for (MediaFile mf : list) mf.setStarred(config.isStarred(mf.getPath()));
    }

    // ── Frame capture ─────────────────────────────────────────────────────────

    private Path getCapturesFolder() {
        if (config.capturesFolder != null && !config.capturesFolder.isBlank())
            return Paths.get(config.capturesFolder);
        return Paths.get(System.getProperty("user.home"), ".image_viewer", "FrameCaptures");
    }

    private void openCapturesFolder() {
        Path folder = getCapturesFolder();
        try {
            Files.createDirectories(folder);
            new ProcessBuilder("explorer.exe", folder.toString()).start();
        } catch (IOException ex) {
            setStatus("Could not open captures folder: " + ex.getMessage());
        }
    }

    private void saveFrameToPath(javafx.scene.image.WritableImage frame, Path dest) throws IOException {
        int w = (int) frame.getWidth();
        int h = (int) frame.getHeight();
        int[] pixels = new int[w * h];
        frame.getPixelReader().getPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, w, h, pixels, 0, w);
        javax.imageio.ImageIO.write(bi, "PNG", dest.toFile());
    }

    private void showFrameCaptureDialog() {
        javafx.scene.image.WritableImage frame = mediaPane.captureCurrentFrame();
        if (frame == null) { setStatus("No video frame to capture"); return; }

        // Build default filename: videoname_MMmSSs.png
        String videoName = (!files.isEmpty() && currentIdx >= 0 && currentIdx < files.size())
                ? files.get(currentIdx).getPath().getFileName().toString().replaceFirst("\\.[^.]+$", "")
                : "frame";
        long timeMs = mediaPane.getCurrentVideoTimeMs();
        long totalSec = timeMs / 1000;
        String defaultName = String.format("%s_%02dm%02ds.png", videoName, totalSec / 60, totalSec % 60);

        // ── Dialog ────────────────────────────────────────────────────────────
        Stage dlg = new Stage();
        dlg.setTitle("Save Frame");
        dlg.initOwner(stage);
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dlg.initStyle(javafx.stage.StageStyle.UTILITY);

        // Preview (max 400×225)
        javafx.scene.image.ImageView preview = new javafx.scene.image.ImageView(frame);
        preview.setPreserveRatio(true);
        preview.setFitWidth(400);
        preview.setFitHeight(225);
        StackPane previewBox = new StackPane(preview);
        previewBox.setStyle("-fx-background-color: black;");
        previewBox.setPrefHeight(225);

        // Filename row
        TextField nameField = new TextField(defaultName);
        nameField.setPrefWidth(360);
        HBox nameRow = new HBox(8, new Label("Filename:"), nameField);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // Buttons
        Button browseBtn   = new Button("📁  Browse & Save");
        Button capturesBtn = new Button("📂  Save to Captures Folder");
        Button cancelBtn   = new Button("Cancel");
        browseBtn.setDefaultButton(false);
        capturesBtn.setDefaultButton(false);

        browseBtn.setOnAction(ev -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Choose save location");
            java.io.File dir = dc.showDialog(dlg);
            if (dir == null) return;
            Path dest = dir.toPath().resolve(nameField.getText().trim());
            try {
                saveFrameToPath(frame, dest);
                setStatus("Frame saved → " + dest.getFileName());
                dlg.close();
            } catch (IOException ex) {
                new Alert(Alert.AlertType.ERROR,
                        "Could not save frame:\n" + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        capturesBtn.setOnAction(ev -> {
            Path folder = getCapturesFolder();
            Path dest   = folder.resolve(nameField.getText().trim());
            try {
                Files.createDirectories(folder);
                saveFrameToPath(frame, dest);
                dlg.close();
                mediaPane.showToast("📷 Saved to FrameCaptures ✓");
                setStatus("Frame saved → " + dest.toString());
            } catch (IOException ex) {
                new Alert(Alert.AlertType.ERROR,
                        "Could not save frame:\n" + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        cancelBtn.setOnAction(ev -> dlg.close());

        HBox btnRow = new HBox(10, browseBtn, capturesBtn, cancelBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, previewBox, nameRow, btnRow);
        content.setPadding(new Insets(16));

        Scene dlgScene = new Scene(content);
        if (scene != null) dlgScene.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(dlgScene);
        dlg.sizeToScene();
        Platform.runLater(nameField::requestFocus);
        dlg.showAndWait();
    }

    // ── Clipboard paste ───────────────────────────────────────────────────────

    private void pasteFromClipboard() {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        // 1. File references (video or image files copied in Explorer)
        if (cb.hasFiles()) {
            List<Path> paths = cb.getFiles().stream()
                    .map(java.io.File::toPath)
                    .filter(p -> Files.isDirectory(p) || MediaFile.isSupported(p))
                    .collect(Collectors.toList());
            if (!paths.isEmpty()) {
                loadPaths(paths);
                return;
            }
        }
        // 2. Raw pixel data (Snipping Tool, browser right-click → copy image, etc.)
        if (cb.hasImage()) {
            javafx.scene.image.Image img = cb.getImage();
            mediaPane.showDirectImage(img, "Clipboard image  ("
                    + (int) img.getWidth() + " × " + (int) img.getHeight() + ")");
        } else {
            setStatus("Clipboard does not contain a supported file or image");
        }
    }

    // ── Rename current file ───────────────────────────────────────────────────

    private void renameCurrentFile() {
        if (files.isEmpty() || currentIdx < 0 || currentIdx >= files.size()) return;
        MediaFile mf  = files.get(currentIdx);
        Path      src = mf.getPath();
        String oldName = src.getFileName().toString();

        Stage dlg = new Stage();
        dlg.setTitle("Rename");
        dlg.initOwner(stage);
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dlg.initStyle(javafx.stage.StageStyle.UTILITY);

        TextField nameField = new TextField(oldName);
        nameField.setPrefWidth(380);
        // Pre-select name without extension
        int dot = oldName.lastIndexOf('.');
        if (dot > 0) Platform.runLater(() -> nameField.selectRange(0, dot));

        Button okBtn     = new Button("Rename");
        Button cancelBtn = new Button("Cancel");
        okBtn.setDefaultButton(true);
        cancelBtn.setCancelButton(true);

        okBtn.setOnAction(ev -> {
            String newName = nameField.getText().trim();
            if (newName.isEmpty() || newName.equals(oldName)) { dlg.close(); return; }
            Path dest = src.getParent().resolve(newName);
            try {
                Files.move(src, dest);
                // Update star config if file was starred
                if (config.isStarred(src)) {
                    config.setStarred(src, false);
                    config.setStarred(dest, true);
                    config.save();
                }
                // Replace entry in the files list
                MediaFile newMf = new MediaFile(dest);
                newMf.setStarred(mf.isStarred());
                files.set(currentIdx, newMf);
                thumbPanel.setFiles(files);
                thumbPanel.select(currentIdx);
                setStatus("Renamed → " + newName);
                dlg.close();
            } catch (IOException ex) {
                new Alert(Alert.AlertType.ERROR,
                        "Could not rename:\n" + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        cancelBtn.setOnAction(ev -> dlg.close());

        HBox nameRow = new HBox(8, new Label("New name:"), nameField);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox btnRow = new HBox(8, okBtn, cancelBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, nameRow, btnRow);
        content.setPadding(new Insets(16));

        Scene dlgScene = new Scene(content);
        if (scene != null) dlgScene.getStylesheets().addAll(scene.getStylesheets());
        dlg.setScene(dlgScene);
        dlg.sizeToScene();
        Platform.runLater(nameField::requestFocus);
        dlg.showAndWait();
    }

    // ── Delete current file ───────────────────────────────────────────────────

    private void deleteCurrentFile() {
        if (files.isEmpty() || currentIdx < 0 || currentIdx >= files.size()) return;
        MediaFile cur = files.get(currentIdx);

        Stage confirm = new Stage(StageStyle.UNDECORATED);
        confirm.initOwner(stage);
        confirm.initModality(Modality.WINDOW_MODAL);
        confirm.getIcons().addAll(stage.getIcons());

        Label heading = new Label("Delete  \"" + cur.getFilename() + "\"?");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 0 0 4 0;");
        Label body = new Label("This will permanently delete the file from disk.\nThis action cannot be undone.");
        body.setStyle("-fx-text-fill: #aaa;");
        VBox msg = new VBox(6, heading, body);
        msg.setPadding(new Insets(16, 20, 8, 20));

        final boolean[] confirmed = {false};
        Button okBtn2 = new Button("Delete");
        okBtn2.getStyleClass().add("toolbar-btn");
        okBtn2.setStyle("-fx-base: #c42b1c;");
        okBtn2.setDefaultButton(true);
        okBtn2.setOnAction(e -> { confirmed[0] = true; confirm.close(); });
        Button cancelBtn2 = new Button("Cancel");
        cancelBtn2.getStyleClass().add("toolbar-btn");
        cancelBtn2.setCancelButton(true);
        cancelBtn2.setOnAction(e -> confirm.close());

        VBox confirmRoot = new VBox(0, makeTitleBar("Delete File", confirm, buildDeleteIcon()), msg,
                makeDialogButtonBar(okBtn2, cancelBtn2));
        Scene confirmScene = new Scene(confirmRoot);
        if (scene != null) confirmScene.getStylesheets().addAll(scene.getStylesheets());
        confirm.setScene(confirmScene);
        confirm.setOnShown(e -> centerOnOwner(confirm, stage));
        confirm.showAndWait();
        if (!confirmed[0]) return;

        try {
            java.nio.file.Files.delete(cur.getPath());
        } catch (IOException ex) {
            setStatus("Delete failed: " + ex.getMessage());
            return;
        }
        // Remove sidecar thumbnail (if any) to keep the cache clean
        ThumbnailPanel.deleteSidecarThumbnail(cur.getPath());

        // Stop any media using this file
        if (cur.isVideo()) { mediaPane.stopVideo(); }

        // Remove from master list
        int removedIdx = currentIdx;
        files.remove(removedIdx);

        if (files.isEmpty()) {
            currentIdx = -1;
            thumbPanel.setFiles(List.of());
            mediaPane.clearMedia();
            refreshIdxLabel();
            setStatus("No files remaining");
            return;
        }

        // Refresh thumbnail panel (preserves current sort/filter)
        thumbPanel.setFiles(List.copyOf(files));

        // Navigate to next file (or last if we deleted the last one)
        int nextIdx = Math.min(removedIdx, files.size() - 1);
        navigateTo(nextIdx);
        setStatus("Deleted  " + cur.getFilename());
    }

    // ── Config ────────────────────────────────────────────────────────────────

    /** Returns the {@link Screen} that contains the largest portion of the stage. */
    private Screen getCurrentScreen() {
        ObservableList<Screen> hit = Screen.getScreensForRectangle(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return hit.isEmpty() ? Screen.getPrimary() : hit.get(0);
    }

    /**
     * Un-maximizes the window and restores it to a sensible size on the current monitor.
     * Called synchronously from button/drag handlers so our setX/Y/Width/Height calls
     * run immediately after setMaximized(false) — before JavaFX can overwrite them.
     */
    private void restoreFromMaximized() {
        Rectangle2D vis = getCurrentScreen().getVisualBounds();
        boolean stale = preMaxX < 0 || preMaxY < 0 || preMaxW <= 0 || preMaxH <= 0
                || preMaxW >= vis.getWidth()  - STALE_RESTORE_TOLERANCE
                || preMaxH >= vis.getHeight() - STALE_RESTORE_TOLERANCE;
        if (stale) {
            preMaxW = Math.round(vis.getWidth()  * 0.75);
            preMaxH = Math.round(vis.getHeight() * 0.75);
            preMaxX = Math.round(vis.getMinX() + vis.getWidth()  * 0.125);
            preMaxY = Math.round(vis.getMinY() + vis.getHeight() * 0.125);
        }

        // Capture desired bounds before calling setMaximized(false).
        // Win32 SW_RESTORE fires asynchronously and will corrupt preMax* via trackBounds,
        // so we must hold the target in local finals and apply them after the OS settles.
        final double tx = preMaxX, ty = preMaxY, tw = preMaxW, th = preMaxH;

        stage.setMaximized(false);

        // Win32 SW_RESTORE overwrites setX/Y calls made synchronously here.
        // A short deferred Timeline fires after the OS has finished its restore pass.
        new Timeline(new KeyFrame(Duration.millis(80), ev -> {
            stage.setX(tx); stage.setY(ty);
            stage.setWidth(tw); stage.setHeight(th);
            preMaxX = tx; preMaxY = ty; preMaxW = tw; preMaxH = th;
        })).play();
    }

    private void saveConfig() {
        // Always save the pre-maximize (restored) bounds so that on next launch
        // preMaxW/H is seeded correctly even when the app was closed while maximized.
        config.windowWidth     = preMaxW;
        config.windowHeight    = preMaxH;
        config.windowX         = preMaxX;
        config.windowY         = preMaxY;
        config.windowMaximized = stage.isMaximized();
        if (!splitPane.getDividers().isEmpty())
            config.thumbnailPanelWidth = splitPane.getDividerPositions()[0] * splitPane.getWidth();
        config.save();
    }

    private void restoreLastFolder() {
        // If launched via "Open with" (or CLI), load that path and ignore the saved last-folder.
        if (!launchArgs.isEmpty()) {
            Path p = Path.of(launchArgs.get(0));
            if (Files.isDirectory(p)) {
                Platform.runLater(() -> loadFolder(p));
            } else if (Files.isRegularFile(p) && p.getParent() != null) {
                Platform.runLater(() -> promptOpenFileOrFolder(p));
            }
            return;
        }
        // Normal launch: start on an empty state — don't auto-open the last folder.
    }

    /**
     * When the app is launched via "Open with" for a specific file, ask the user
     * whether they want to load just that one file or the entire containing folder.
     */
    private void promptOpenFileOrFolder(Path filePath) {
        ButtonType btnFolder = new ButtonType("Open Folder");
        ButtonType btnFile   = new ButtonType("Open File Only");
        ButtonType btnCancel = new ButtonType("Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.NONE,
                "How would you like to open:\n" + filePath.getFileName(),
                btnFolder, btnFile, btnCancel);
        alert.setTitle("Open");
        alert.setHeaderText(null);
        alert.setGraphic(null);
        alert.initOwner(stage);

        // Apply current theme stylesheet
        DialogPane dp = alert.getDialogPane();
        if (scene != null) dp.getStylesheets().addAll(scene.getStylesheets());

        // Hide the header panel bar entirely
        dp.setStyle("-fx-padding: 0;");
        dp.lookup(".header-panel");  // force CSS pass
        dp.getChildren().stream()
          .filter(n -> n.getStyleClass().contains("header-panel"))
          .forEach(n -> { n.setManaged(false); n.setVisible(false); });

        // Copy window icon from main stage once the dialog window is available
        alert.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                Stage dlgStage = (Stage) dp.getScene().getWindow();
                dlgStage.getIcons().setAll(stage.getIcons());
            }
        });

        alert.showAndWait().ifPresent(bt -> {
            if (bt == btnFolder) {
                loadFolder(filePath.getParent());
            } else if (bt == btnFile) {
                loadSingleFile(filePath);
            }
        });
    }

    /** Load exactly one file (no folder scan). */
    private void loadSingleFile(Path filePath) {
        if (!Files.isRegularFile(filePath) || !MediaFile.isSupported(filePath)) return;
        MediaFile mf = new MediaFile(filePath);
        applyStarredState(List.of(mf));
        files.clear();
        files.add(mf);
        slideshowQueue.clear();
        rebuildQueuePositions();
        thumbPanel.setFiles(List.copyOf(files));
        resetSidebarToPreset();
        currentIdx = -1;
        navigateTo(0);
        startWatcher(filePath.getParent() != null ? List.of(filePath.getParent()) : List.of());
        config.lastFolder = filePath.getParent() != null ? filePath.getParent().toString() : null;
        config.save();
    }
}

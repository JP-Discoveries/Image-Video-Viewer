package com.imageviewer;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;

import java.nio.ByteBuffer;

import org.bytedeco.javacpp.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Central display pane — image zoom/pan and video playback.
 *
 * Image mode
 * ──────────
 *  • ScrollPane with manual drag-to-pan
 *  • Scroll-wheel zooms in/out
 *  • Optional cross-fade / dip-to-black transition on image switch
 *  • Next-image prefetch via {@link #prefetchImage(Path)}
 *
 * Video mode
 * ──────────
 *  • VLCJ CallbackMediaPlayer → WritableImage → ImageView pipeline (any codec)
 *  • Semi-transparent control bar (auto-hides after 3 s)
 *  • Videos start PAUSED so the user can choose when to play
 *  • Controls: restart, skip ±10s, play/pause, seek slider, time labels,
 *    seek-to-timestamp (🕐), loop toggle (🔁), mute/volume, CC, fullscreen
 *  • CC overlay: auto-loads matching .srt / .vtt from same directory
 *    Right-click CC button → Generate captions via Whisper CLI
 *
 * Toast notifications
 * ───────────────────
 *  • Call {@link #showToast(String)} to display a short overlay message.
 *
 * New in this version vs original
 * ─────────────────────────────────
 *  rotateLeft(), setTransitionMode(), prefetchImage(), showToast(),
 *  setOnEndOfMedia(), setLoopEnabled(), seekToTimestamp().
 */
public class MediaPane extends StackPane {

    // ── Subtitle entry ────────────────────────────────────────────────────────
    private record WordEntry(double start, double end, String word) {}
    private record SubtitleEntry(Duration start, Duration end, String text, List<WordEntry> words) {
        SubtitleEntry(Duration start, Duration end, String text) {
            this(start, end, text, List.of());
        }
    }

    // ── Transition mode ───────────────────────────────────────────────────────
    public enum TransitionMode { NONE, FADE, DIP_TO_BLACK }

    // ── Zoom constants ───────────────────────────────────────────────────────
    private static final double ZOOM_MIN    = 0.05;
    private static final double ZOOM_MAX    = 32.0;
    private static final double ZOOM_FACTOR = 1.12;

    // ── Image mode widgets ───────────────────────────────────────────────────
    private final ScrollPane  imgScroll;
    private final ImageView   imageView;
    private final StackPane   imgContainer;
    /** Checkerboard Region inserted as the bottom layer of imgContainer and videoPane. */
    private final Region      imgChecker;
    private final Region      vidChecker;

    // ── Video mode widgets ───────────────────────────────────────────────────
    private final StackPane   videoPane;
    private       ImageView   videoView;     // replaces MediaView
    private final VBox        controlBar;
    private final Button      playPauseBtn;
    private final Slider      seekSlider;
    private final Label       timeCurrent;
    private final Label       timeDuration;
    private final Slider      volumeSlider;
    private final Button      muteBtn;
    private final Button      fullscreenBtn;
    private final Button      ccBtn;
    private final TextFlow    ccFlow;
    private final StackPane   ccPane;
    private final ToggleButton loopBtn;
    private final Button      restartBtn;
    private final Button      seekTimeBtn;
    private final Button      speedBtn;
    private final Button      bookmarkBtn;
    private final ProgressIndicator loadingSpinner;
    /** Covers videoPane during fade transitions so background colour never bleeds through.
     *  Defaults to the theme background via -fx-background; caller may override for
     *  Dip-to-Black by calling setCoverColor(). */
    private final Region transitionCover;
    private volatile boolean         videoLoading = false;
    /** Called once on the FX thread when the very first decoded frame is rendered.
     *  Used by callers (e.g. fade-in transition) to wait for real pixels before revealing. */
    private volatile Runnable        onFirstFrame;
    /** Fires after SPINNER_DELAY_MS if the first frame still hasn't arrived. */
    private Timeline                 spinnerDelayTimer;
    private static final int         SPINNER_DELAY_MS = 1500;
    private final ProgressIndicator whisperIndicator;
    /** Persistent overlay shown while Whisper is running — stays visible even when controls fade. */
    private final ProgressBar        whisperProgressBar;
    private final Label              whisperStatusLabel;
    private final VBox               whisperOverlay;
    /** Reused so we never stack duplicate video context menus. */
    private ContextMenu videoContextMenu;
    /** Reused so we never stack duplicate image context menus. */
    private ContextMenu imageContextMenu;
    // ── VLCJ video engine ────────────────────────────────────────────────────
    private static MediaPlayerFactory  vlcFactory;      // shared across all panes, init once
    private static boolean             vlcChecked;      // true after first discovery attempt
    private static boolean             vlcAvailable;    // true if libvlc was found
    private        EmbeddedMediaPlayer vlcPlayer;       // one per pane, reused across videos
    /** 60 fps AnimationTimer that drives CC word highlighting for VLC playback.
     *  Polling at frame rate instead of relying on VLC's timeChanged (~100 ms)
     *  ensures small/fast words are never skipped. */
    private javafx.animation.AnimationTimer vlcCCTimer;
    // ── JavaFX MediaPlayer fallback (used when VLC is not installed) ─────────
    private javafx.scene.media.MediaPlayer fxMediaPlayer;
    private javafx.scene.media.MediaView   fxMediaView;
    private boolean                        usingFxMedia = false;
    private volatile WritableImage     videoImage;      // resized per-video in buffer callback
    /** Set to true before each play() call when !autoPlay; cleared by the first render callback
     *  so the pause fires only after the first decoded frame is actually in videoImage. */
    private volatile boolean           pauseOnFirstFrame = false;

    // ── Video resume position ─────────────────────────────────────────────────
    /** Called with (path, positionMs) when a video is disposed. positionMs=0 means "clear". */
    private BiConsumer<Path, Long> onSavePosition = (p, ms) -> {};

    // ── Video bookmarks ───────────────────────────────────────────────────────
    /** Callback to load bookmarks for a given video path string. */
    private Function<String, List<AppConfig.VideoBookmark>> onGetBookmarks  = p -> new ArrayList<>();
    /** Callback to save a new bookmark: (videoPath, name, positionMs). */
    private BookmarkCallback                                onAddBookmark    = (p, n, ms) -> {};
    /** Callback to delete a bookmark by name: (videoPath, name). */
    private BiConsumer<String, String>                      onDeleteBookmark = (p, n) -> {};

    /** Functional interface for saving a named bookmark. */
    @FunctionalInterface
    public interface BookmarkCallback {
        void save(String videoPath, String name, long ms);
    }
    /** Pending seek position applied on the next 'playing' event (used when player isn't ready yet). */
    private volatile long          pendingSeekMs  = -1;
    // Resume-prompt UI nodes (built in constructor, added to videoPane)
    private HBox     resumeBar;
    private Label    resumeTimeLabel;
    private Button   resumeResumeBtn;
    private Timeline resumeDismissTimer;

    // ── Seek-bar preview thumbnails ───────────────────────────────────────────
    private final ImageView    seekPrevView  = new ImageView();
    private final Label        seekPrevTime  = new Label();
    private       VBox         seekPrevPopup;   // built in constructor after videoPane exists
    @SuppressWarnings("serial")
    private final Map<Integer, Image> seekPrevCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, Image> e) {
                    return size() > 60;
                }
            });
    private final AtomicLong   seekPrevGen  = new AtomicLong();
    /** Single-thread executor; bounded queue with DiscardOldest keeps only the latest request. */
    private final ExecutorService seekPrevExec = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            r -> { Thread t = new Thread(r, "seek-preview"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    // ── Zoom state ───────────────────────────────────────────────────────────
    private double  zoomScale  = 1.0;
    private double  rotation   = 0;

    // ── Pan drag state ───────────────────────────────────────────────────────
    private double  dragStartX, dragStartY;
    private double  dragStartH, dragStartV;
    private boolean hasPanned = false;   // true only after a real mouse drag; reset on fit/fill/new image

    // ── Video state ──────────────────────────────────────────────────────────
    private boolean seekInProgress      = false;
    private boolean wasPlayingBeforeSeek = false;
    private Timeline controlFadeTimer;
    private boolean muted           = false;
    /** When true, showVideo() will start playing immediately instead of pausing on first frame. */
    private boolean autoPlay        = false;
    /** Current playback rate; 1.0 = normal speed. Persists across video loads. */
    private float   playbackRate    = 1.0f;
    private static final float[] SPEED_RATES = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    // ── Alpha-video mode (FFmpeg BGRA pipe for .webm / .mov with alpha) ────────
    // VLC's hardware (and software) BGRA output always sets A=0xFF — it composites over
    // a background before we ever see the pixels.  For formats that carry a real alpha
    // channel (VP9-alpha WebM, ProRes 4444 / Animation .mov) we bypass VLC's video path
    // entirely: FFmpeg pipes raw BGRA frames (alpha intact) into the WritableImage while
    // VLC is kept alive solely for audio playback (:no-video).
    private volatile boolean alphaMode;       // true → FFmpeg video + VLC audio-only
    private Thread           apThread;        // frame reader / pacer thread
    private volatile boolean apStopped;       // signals reader to stop
    private volatile boolean apPaused;        // signals reader to pause
    private volatile boolean pendingAlphaStart; // start FFmpeg thread on next VLC 'playing' event
    private int              apW, apH;        // display/pipe frame dimensions (scaled)
    private int              apRawW, apRawH; // native dimensions from ffprobe
    private double           apFps = 30.0;    // frames per second
    private long             apDurationMs;    // total duration in ms (0 = unknown)

    // ── Subtitle state ───────────────────────────────────────────────────────
    private final List<SubtitleEntry> subtitles    = new ArrayList<>();
    private boolean                   ccVisible        = false;
    private boolean                   karaokeEnabled   = false;
    private Path                      currentVideoPath = null;
    private Path                      currentImagePath = null;
    private boolean                   ccGenerating = false;
    /** Incremented each time a new video loads; used to discard stale Whisper callbacks. */
    private volatile long             ccGenToken   = 0;
    /** Whisper model selected by the user — persists for the session. */
    private String                    whisperModel = "base";   // matches the bundled model
    /** True while an Argos Translate background job is running. */
    private volatile boolean          translating  = false;

    // ── Animated GIF state ───────────────────────────────────────────────────
    private Timeline gifTimeline;

    // ── Transition state ─────────────────────────────────────────────────────
    private TransitionMode transitionMode = TransitionMode.NONE;

    // ── Prefetch cache ───────────────────────────────────────────────────────
    private final Map<String, Image> prefetchCache = new ConcurrentHashMap<>();

    // ── Toast state ──────────────────────────────────────────────────────────
    private final Label    toastLabel = new Label();
    private       Timeline toastTimer;

    // ── Resize debounce ───────────────────────────────────────────────────────
    private Timeline zoomFitDebounce;

    // ── Callbacks ────────────────────────────────────────────────────────────
    private Consumer<String> onStatusUpdate      = s -> {};
    private Runnable         onFullscreenRequest  = () -> {};
    private Runnable         onEndOfMedia         = () -> {};
    private Runnable         onFrameCapture       = () -> {};
    private Runnable         onCopyImageToClipboard = () -> {};

    // ── Constructor ──────────────────────────────────────────────────────────
    public MediaPane() {
        getStyleClass().add("media-pane");
        setStyle("-fx-background-color: #1a1a1a;");

        // ── Image mode ───────────────────────────────────────────────────────
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        imgChecker = buildCheckerRegion();
        imgChecker.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> imageView.getBoundsInParent().getWidth(),
                imageView.boundsInParentProperty()));
        imgChecker.prefHeightProperty().bind(Bindings.createDoubleBinding(
                () -> imageView.getBoundsInParent().getHeight(),
                imageView.boundsInParentProperty()));
        imgContainer = new StackPane(imgChecker, imageView);
        imgContainer.setAlignment(Pos.CENTER);
        imgContainer.setStyle("-fx-background-color: black;");

        imgScroll = new ScrollPane(imgContainer);
        imgScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        imgScroll.setPannable(false);
        imgScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        imgScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        imgScroll.setFocusTraversable(false);
        imgScroll.setVisible(false);

        // ── Video mode ───────────────────────────────────────────────────────
        videoView = new ImageView();
        videoView.setPreserveRatio(true);
        videoView.setSmooth(true);

        seekSlider = new Slider(0, 1, 0);
        seekSlider.getStyleClass().add("seek-slider");
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        timeCurrent  = new Label("0:00");
        timeDuration = new Label("0:00");
        timeCurrent.getStyleClass().add("time-label");
        timeDuration.getStyleClass().add("time-label");

        // ── New control buttons ───────────────────────────────────────────────
        restartBtn = new Button("⏮");
        restartBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        restartBtn.setTooltip(new Tooltip("Restart / replay from beginning"));
        restartBtn.setOnAction(e -> restartVideo());

        Button skipBack = new Button("⏪");
        skipBack.getStyleClass().addAll("ctrl-btn", "icon-btn");
        skipBack.setTooltip(new Tooltip("Skip back 10 s"));
        skipBack.setOnAction(e -> seekRelative(-10));

        Button skipFwd = new Button("⏩");
        skipFwd.getStyleClass().addAll("ctrl-btn", "icon-btn");
        skipFwd.setTooltip(new Tooltip("Skip forward 10 s"));
        skipFwd.setOnAction(e -> seekRelative(10));

        playPauseBtn = new Button("▶");
        playPauseBtn.getStyleClass().addAll("ctrl-btn", "play-btn");
        playPauseBtn.setOnAction(e -> togglePlayPause());

        seekTimeBtn = new Button("🕐");
        seekTimeBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        seekTimeBtn.setTooltip(new Tooltip("Seek to timestamp…"));
        seekTimeBtn.setOnAction(e -> openSeekDialog());

        bookmarkBtn = new Button("🔖");
        bookmarkBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        bookmarkBtn.setTooltip(new Tooltip("Bookmarks"));
        bookmarkBtn.setOnAction(e -> showBookmarkPopup());

        loopBtn = new ToggleButton("🔁");
        loopBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        loopBtn.setTooltip(new Tooltip("Loop video"));
        loopBtn.setOnAction(e -> { /* loop state is read inside the finished() event callback */ });
        loopBtn.selectedProperty().addListener((obs, wasOn, isOn) -> updateLoopButtonStyle(isOn));

        muteBtn = new Button("🔊");
        muteBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        muteBtn.setOnAction(e -> toggleMute());

        volumeSlider = new Slider(0, 1, 1);
        volumeSlider.setPrefWidth(70);
        volumeSlider.getStyleClass().add("volume-slider");

        ccBtn = new Button("CC");
        ccBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        ccBtn.setTooltip(new Tooltip("Toggle subtitles\nRight-click → Generate CC with Whisper"));
        ccBtn.setOnAction(e -> toggleCC());
        ccBtn.setOnContextMenuRequested(e -> { showCCContextMenu(e.getScreenX(), e.getScreenY()); e.consume(); });

        speedBtn = new Button("1×");
        speedBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        speedBtn.setTooltip(new Tooltip("Playback speed"));
        speedBtn.setOnAction(e -> showSpeedMenu());

        whisperIndicator = new ProgressIndicator(-1);
        whisperIndicator.setPrefSize(16, 16);
        whisperIndicator.setVisible(false);
        whisperIndicator.setMouseTransparent(true);
        updateCCButtonStyle();

        fullscreenBtn = new Button("⛶");
        fullscreenBtn.getStyleClass().addAll("ctrl-btn", "icon-btn");
        fullscreenBtn.setOnAction(e -> onFullscreenRequest.run());

        HBox timeRow = new HBox(5,
                restartBtn, skipBack, playPauseBtn, skipFwd,
                timeCurrent, seekSlider, timeDuration,
                seekTimeBtn, bookmarkBtn, loopBtn, speedBtn, muteBtn, volumeSlider,
                ccBtn, whisperIndicator, fullscreenBtn);
        timeRow.setAlignment(Pos.CENTER_LEFT);
        timeRow.setPadding(new Insets(6, 10, 6, 10));

        controlBar = new VBox(timeRow);
        controlBar.getStyleClass().add("video-controls");
        controlBar.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-background-radius: 0 0 6 6;");
        controlBar.setMaxHeight(Region.USE_PREF_SIZE);

        // ── CC subtitle overlay ──────────────────────────────────────────────
        ccFlow = new TextFlow();
        ccFlow.setTextAlignment(TextAlignment.CENTER);
        ccFlow.setMaxWidth(700);
        ccFlow.setMouseTransparent(true);
        ccFlow.setStyle(
                "-fx-background-color: rgba(0,0,0,0.72); -fx-background-radius: 4; " +
                "-fx-padding: 4 14 4 14;");

        ccPane = new StackPane(ccFlow);
        ccPane.setVisible(false);
        ccPane.setMouseTransparent(true);
        ccPane.setMaxWidth(700);
        ccPane.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(ccPane, Pos.BOTTOM_CENTER);
        StackPane.setMargin(ccPane, new Insets(0, 20, 54, 20));

        vidChecker = buildCheckerRegion();
        vidChecker.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> videoView.getBoundsInParent().getWidth(),
                videoView.boundsInParentProperty()));
        vidChecker.prefHeightProperty().bind(Bindings.createDoubleBinding(
                () -> videoView.getBoundsInParent().getHeight(),
                videoView.boundsInParentProperty()));
        videoPane = new StackPane(vidChecker, videoView, ccPane, controlBar);
        StackPane.setAlignment(controlBar, Pos.BOTTOM_CENTER);
        videoPane.setVisible(false);
        videoPane.setStyle("-fx-background-color: black;");

        // ── JavaFX MediaPlayer fallback view (shown when VLC not installed) ──
        fxMediaView = new javafx.scene.media.MediaView();
        fxMediaView.setPreserveRatio(true);
        fxMediaView.setSmooth(true);
        fxMediaView.setVisible(false);
        fxMediaView.fitWidthProperty().bind(videoPane.widthProperty());
        fxMediaView.fitHeightProperty().bind(videoPane.heightProperty());
        // Insert above vidChecker (index 0) but below the existing videoView layer
        videoPane.getChildren().add(1, fxMediaView);

        // ── Whisper progress overlay (persists when control bar fades) ────────
        whisperProgressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        whisperProgressBar.setPrefWidth(280);
        whisperProgressBar.setPrefHeight(8);
        // Orange tint matches the CC-generating button colour.
        whisperProgressBar.setStyle("-fx-accent: #f5a623;");

        whisperStatusLabel = new Label("Generating captions with Whisper…");
        whisperStatusLabel.setStyle("-fx-text-fill: white; -fx-font-size: 12px;");

        whisperOverlay = new VBox(10, whisperStatusLabel, whisperProgressBar);
        whisperOverlay.setAlignment(Pos.CENTER);
        whisperOverlay.setStyle(
                "-fx-background-color: rgba(0,0,0,0.82); " +
                "-fx-padding: 16 24 16 24; -fx-background-radius: 10;");
        whisperOverlay.setMaxWidth(320);
        whisperOverlay.setMaxHeight(Region.USE_PREF_SIZE);
        whisperOverlay.setVisible(false);
        whisperOverlay.setMouseTransparent(true);
        videoPane.getChildren().add(whisperOverlay);

        // ── Seek preview popup ────────────────────────────────────────────────
        seekPrevView.setFitWidth(160);
        seekPrevView.setFitHeight(90);
        seekPrevView.setPreserveRatio(true);
        seekPrevView.setSmooth(true);

        javafx.scene.shape.Rectangle seekPrevBg = new javafx.scene.shape.Rectangle(160, 90, javafx.scene.paint.Color.web("#111"));
        StackPane seekPrevImgStack = new StackPane(seekPrevBg, seekPrevView);
        seekPrevImgStack.setPrefSize(160, 90);
        seekPrevImgStack.setMaxSize(160, 90);

        seekPrevTime.setStyle("-fx-text-fill: #ddd; -fx-font-size: 10px;");
        seekPrevTime.setAlignment(javafx.geometry.Pos.CENTER);
        seekPrevTime.setMaxWidth(Double.MAX_VALUE);

        seekPrevPopup = new VBox(3, seekPrevImgStack, seekPrevTime);
        seekPrevPopup.setStyle(
                "-fx-background-color: rgba(0,0,0,0.88); -fx-padding: 4; " +
                "-fx-background-radius: 4; -fx-border-color: rgba(255,255,255,0.18); " +
                "-fx-border-radius: 4; -fx-border-width: 1;");
        seekPrevPopup.setAlignment(javafx.geometry.Pos.CENTER);
        seekPrevPopup.setMaxSize(168, Region.USE_PREF_SIZE);
        seekPrevPopup.setMouseTransparent(true);
        seekPrevPopup.setVisible(false);
        StackPane.setAlignment(seekPrevPopup, javafx.geometry.Pos.TOP_LEFT);
        videoPane.getChildren().add(seekPrevPopup);

        // ── Resume-position prompt bar ────────────────────────────────────────
        resumeTimeLabel = new Label();
        resumeTimeLabel.setStyle("-fx-text-fill: #ddd; -fx-font-size: 12px;");

        resumeResumeBtn = new Button("▶ Resume");
        resumeResumeBtn.getStyleClass().addAll("ctrl-btn");
        resumeResumeBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 10 3 10;");

        Button resumeDismissBtn = new Button("✕");
        resumeDismissBtn.getStyleClass().addAll("ctrl-btn");
        resumeDismissBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8 3 8;");
        resumeDismissBtn.setOnAction(e -> hideResumePrompt());

        resumeBar = new HBox(10, resumeTimeLabel, resumeResumeBtn, resumeDismissBtn);
        resumeBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        resumeBar.setPadding(new javafx.geometry.Insets(7, 14, 7, 14));
        resumeBar.setStyle(
                "-fx-background-color: rgba(0,0,0,0.80); " +
                "-fx-background-radius: 6; " +
                "-fx-border-color: rgba(255,255,255,0.15); " +
                "-fx-border-radius: 6; -fx-border-width: 1;");
        resumeBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        resumeBar.setVisible(false);
        StackPane.setAlignment(resumeBar, javafx.geometry.Pos.BOTTOM_CENTER);
        StackPane.setMargin(resumeBar, new javafx.geometry.Insets(0, 0, 56, 0));
        videoPane.getChildren().add(resumeBar);

        // ── Transition cover for fade transitions ────────────────────────────
        // Region fills the StackPane (videoPane) automatically — no size bindings needed.
        transitionCover = new Region();
        transitionCover.setStyle("-fx-background-color: -fx-background;");
        transitionCover.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        transitionCover.setOpacity(0);
        transitionCover.setMouseTransparent(true);
        videoPane.getChildren().add(transitionCover);

        // ── Video loading spinner ─────────────────────────────────────────────
        loadingSpinner = new ProgressIndicator(-1);
        loadingSpinner.setPrefSize(52, 52);
        loadingSpinner.setStyle("-fx-progress-color: rgba(255,255,255,0.8);");
        loadingSpinner.setMouseTransparent(true);
        loadingSpinner.setVisible(false);
        videoPane.getChildren().add(loadingSpinner);

        // ── Toast overlay ────────────────────────────────────────────────────
        toastLabel.setStyle(
                "-fx-background-color: rgba(0,0,0,0.80); -fx-text-fill: white; " +
                "-fx-font-size: 12px; -fx-padding: 6 14 6 14; -fx-background-radius: 4;");
        toastLabel.setMouseTransparent(true);
        toastLabel.setVisible(false);
        StackPane.setAlignment(toastLabel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(toastLabel, new Insets(0, 0, 58, 10));

        getChildren().addAll(imgScroll, videoPane, toastLabel);

        wireImageInteractions();
        wireVideoInteractions();
        wireLayoutBindings();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void showImage(Path path) {
        disposeVideo();
        currentImagePath = path;
        videoPane.setVisible(false);

        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".webp") && isAnimatedWebP(path)) {
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            Thread webpLoader = new Thread(() -> {
                List<Image> frames = new ArrayList<>();
                List<Integer> delays = new ArrayList<>();
                loadWebPFrames(path, frames, delays);
                Platform.runLater(() -> {
                    if (!path.equals(currentImagePath)) return;
                    if (frames.isEmpty()) {
                        imageView.setImage(new Image(path.toUri().toString(), true));
                        onStatusUpdate.accept(path.getFileName().toString() + "  (WebP — frames unavailable)");
                        return;
                    }
                    startGifAnimation(frames, delays); // same engine works for WebP frames
                    onStatusUpdate.accept(path.getFileName().toString() +
                            "  (animated WebP  " + frames.size() + " frames)");
                });
            }, "webp-loader");
            webpLoader.setDaemon(true);
            webpLoader.start();
            return;
        }
        // Static WebP and ICO are not natively supported by JavaFX —
        // skip straight to the ImageIO / ImageMagick fallback instead of
        // waiting for JavaFX to load and fail asynchronously.
        if (name.endsWith(".webp") || name.endsWith(".ico")) {
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            fallbackLoadImageIO(path);
            return;
        }
        // CMYK JPEGs load without isError()=true in JavaFX but render as blank/black —
        // detect them upfront and route to the ImageIO/ImageMagick fallback.
        if ((name.endsWith(".jpg") || name.endsWith(".jpeg")) && isCmykJpeg(path)) {
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            fallbackLoadImageIO(path);
            return;
        }
        if (name.endsWith(".gif")) {
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            // Load GIF asynchronously then animate on FX thread
            Thread gifLoader = new Thread(() -> {
                List<Image> frames = new ArrayList<>();
                List<Integer> delays = new ArrayList<>();
                loadGifFrames(path, frames, delays);
                Platform.runLater(() -> {
                    if (!path.equals(currentImagePath)) return; // navigated away
                    if (frames.isEmpty()) {
                        // Fallback to static load
                        imageView.setImage(new Image(path.toUri().toString(), true));
                        onStatusUpdate.accept(path.getFileName().toString() + "  (GIF — no frames decoded)");
                        return;
                    }
                    startGifAnimation(frames, delays);
                    onStatusUpdate.accept(path.getFileName().toString() +
                            "  (GIF  " + frames.size() + " frames)");
                });
            }, "gif-loader");
            gifLoader.setDaemon(true);
            gifLoader.start();
            return;
        }

        // ── RAW camera file — ImageMagick subprocess → embedded JPEG fallback ──
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (MediaFile.RAW_EXTS.contains(ext)) {
            boolean isHeic = ext.equals("heic") || ext.equals("heif");
            String fmtLabel = isHeic ? "HEIC" : "RAW";
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            onStatusUpdate.accept(path.getFileName() + "  (" + fmtLabel + " — loading…)");
            Thread rawLoader = new Thread(() -> {
                Image img = loadRawImage(path);
                Platform.runLater(() -> {
                    if (!path.equals(currentImagePath)) return;
                    if (img != null) {
                        imageView.setImage(img);
                        onStatusUpdate.accept(path.getFileName().toString() + "  (" + fmtLabel + ")");
                    } else {
                        onStatusUpdate.accept(path.getFileName().toString() + "  (" + fmtLabel + " — no preview available)");
                    }
                });
            }, "raw-loader");
            rawLoader.setDaemon(true);
            rawLoader.start();
            return;
        }

        String uri = path.toUri().toString();
        // Use prefetch cache if available, fall back to background load
        Image cached = prefetchCache.remove(uri);
        final Image img = (cached != null) ? cached : new Image(uri, true);

        if (transitionMode == TransitionMode.NONE || !imgScroll.isVisible()) {
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            applyImageWhenReady(img, path);
        } else {
            imgScroll.setVisible(true);
            FadeTransition out = new FadeTransition(
                    Duration.millis(transitionMode == TransitionMode.DIP_TO_BLACK ? 100 : 120), imgScroll);
            out.setToValue(0.0);
            out.setOnFinished(e -> applyImageWithFadeIn(img, path));
            out.play();
        }
    }

    // ── Alpha background ──────────────────────────────────────────────────────

    private boolean alphaCheckerboard = false;

    /**
     * Build a Region tiled with a 16×16 two-tone checkerboard using a WritableImage.
     * JavaFX CSS {@code linear-gradient(…,transparent,…)} does not composite correctly
     * for checkerboard patterns, so we paint it explicitly as a BackgroundImage tile.
     */
    private static Region buildCheckerRegion() {
        int tile = 8;
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(tile * 2, tile * 2);
        javafx.scene.image.PixelWriter pw = img.getPixelWriter();
        javafx.scene.paint.Color light = javafx.scene.paint.Color.web("#aaaaaa");
        javafx.scene.paint.Color dark  = javafx.scene.paint.Color.web("#666666");
        for (int y = 0; y < tile * 2; y++)
            for (int x = 0; x < tile * 2; x++)
                pw.setColor(x, y, ((x / tile + y / tile) % 2 == 0) ? light : dark);

        javafx.scene.layout.BackgroundImage bi = new javafx.scene.layout.BackgroundImage(
                img,
                javafx.scene.layout.BackgroundRepeat.REPEAT,
                javafx.scene.layout.BackgroundRepeat.REPEAT,
                javafx.scene.layout.BackgroundPosition.DEFAULT,
                javafx.scene.layout.BackgroundSize.DEFAULT);
        Region r = new Region();
        r.setBackground(new javafx.scene.layout.Background(bi));
        r.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                     javafx.scene.layout.Region.USE_PREF_SIZE);
        r.setVisible(false);   // hidden until setAlphaCheckerboard(true)
        r.setMouseTransparent(true);
        return r;
    }

    /** Toggle checkerboard vs solid-black background for alpha .mov / .webp video frames. */
    public void setAlphaCheckerboard(boolean enabled) {
        alphaCheckerboard = enabled;
        Platform.runLater(() -> {
            imgChecker.setVisible(enabled);
            vidChecker.setVisible(enabled);
            imgContainer.setStyle(enabled ? "-fx-background-color: transparent;" : "-fx-background-color: black;");
            videoPane.setStyle(enabled ? "-fx-background-color: transparent;" : "-fx-background-color: black;");
        });
    }

    // ── RAW camera file decoder ───────────────────────────────────────────────

    /** "ImageMagickFirst" (default) or "EmbeddedFirst" — set from AppConfig at startup. */
    private static String rawDecodeMode = "ImageMagickFirst";

    public static void setRawDecodeMode(String mode) { rawDecodeMode = (mode != null) ? mode : "ImageMagickFirst"; }

    /**
     * Load a RAW camera file as a JavaFX Image using the configured decode mode:
     *   EmbeddedFirst    — embedded JPEG preview (fast, no install), ImageMagick fallback
     *   ImageMagickFirst — ImageMagick subprocess (full quality), embedded JPEG fallback
     */
    private static Image loadRawImage(Path path) {
        if ("ImageMagickFirst".equals(rawDecodeMode)) {
            Image img = loadRawViaImageMagick(path);
            if (img != null) return img;
        }

        // Embedded JPEG — pure Java, always available
        byte[] jpegBytes = ThumbnailPanel.extractEmbeddedJpeg(path, Integer.MAX_VALUE);
        if (jpegBytes != null) {
            try {
                Image img = new Image(new java.io.ByteArrayInputStream(jpegBytes));
                if (!img.isError()) return img;
            } catch (Exception ignored) {}
        }

        // Last-resort: try ImageMagick if not already tried above
        if (!"ImageMagickFirst".equals(rawDecodeMode)) {
            return loadRawViaImageMagick(path);
        }
        return null;
    }

    /**
     * Decode a RAW file via ImageMagick, piping PNG output to avoid temp files.
     * Respects {@link #imageMagickPath}: if set, uses that executable directly;
     * otherwise tries "magick convert" (v7) then "convert" (v6) from PATH.
     */
    /** Returns the path to the bundled magick.exe, or null if not bundled. */
    private static Path findBundledMagick() {
        try {
            java.net.URL loc = MediaPane.class.getProtectionDomain()
                                              .getCodeSource().getLocation();
            if (loc == null) return null;
            Path jarOrDir = Path.of(loc.toURI());
            Path appDir = Files.isRegularFile(jarOrDir) ? jarOrDir.getParent() : jarOrDir;
            Path magick = appDir.resolve("imagemagick").resolve("magick.exe");
            return Files.exists(magick) ? magick : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Image loadRawViaImageMagick(Path path) {
        String filePath = path.toAbsolutePath().toString();
        // Build candidate command lists — bundled magick.exe takes priority
        Path bundled = findBundledMagick();
        String bundledPath = bundled != null ? bundled.toAbsolutePath().toString() : null;
        String[][] cmds = bundledPath != null ? new String[][] {
            {bundledPath, "convert", filePath, "png:-"},  // bundled ImageMagick v7
            {"magick",    "convert", filePath, "png:-"},  // system ImageMagick v7
            {"convert",              filePath, "png:-"}   // system ImageMagick v6
        } : new String[][] {
            {"magick", "convert", filePath, "png:-"},  // ImageMagick v7
            {"convert",           filePath, "png:-"}   // ImageMagick v6
        };
        for (String[] cmd : cmds) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                Thread errDrain = new Thread(() -> {
                    try { p.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream()); }
                    catch (java.io.IOException ignored) {}
                }, "magick-stderr");
                errDrain.setDaemon(true);
                errDrain.start();

                byte[] pngBytes = p.getInputStream().readAllBytes();
                p.waitFor(120, TimeUnit.SECONDS);
                p.destroyForcibly();

                if (pngBytes.length > 100) {
                    Image img = new Image(new java.io.ByteArrayInputStream(pngBytes));
                    if (!img.isError()) return img;
                }
            } catch (java.io.IOException ignored) {
                // Tool not on PATH — try next command
            } catch (Exception e) {
                System.err.println("[raw] ImageMagick error: " + e.getMessage());
            }
        }
        return null;
    }

    // ── Animated GIF engine ───────────────────────────────────────────────────

    /**
     * Decode all frames and per-frame delays from a GIF using javax.imageio.
     * Must be called on a background thread (may be slow for large GIFs).
     */
    private static void loadGifFrames(Path path, List<Image> frames, List<Integer> delays) {
        try {
            javax.imageio.stream.ImageInputStream iis =
                    javax.imageio.ImageIO.createImageInputStream(path.toFile());
            if (iis == null) return;
            java.util.Iterator<javax.imageio.ImageReader> readers =
                    javax.imageio.ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return;

            javax.imageio.ImageReader reader = readers.next();
            reader.setInput(iis, false);
            int numFrames = reader.getNumImages(true);

            // ── Determine logical screen dimensions from stream metadata ────────
            int canvasW = 0, canvasH = 0;
            try {
                javax.imageio.metadata.IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    org.w3c.dom.Node streamRoot =
                            streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
                    org.w3c.dom.NodeList nodes = streamRoot.getChildNodes();
                    for (int n = 0; n < nodes.getLength(); n++) {
                        org.w3c.dom.Node node = nodes.item(n);
                        if ("LogicalScreenDescriptor".equals(node.getNodeName())) {
                            org.w3c.dom.Element el = (org.w3c.dom.Element) node;
                            canvasW = Integer.parseInt(el.getAttribute("logicalScreenWidth"));
                            canvasH = Integer.parseInt(el.getAttribute("logicalScreenHeight"));
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Fall back to first frame size if stream metadata was unavailable
            if (canvasW <= 0 || canvasH <= 0) {
                canvasW = reader.getWidth(0);
                canvasH = reader.getHeight(0);
            }

            // The composite canvas that accumulates frames
            java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                    canvasW, canvasH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            // Snapshot before applying current frame (needed for restoreToPrevious)
            java.awt.image.BufferedImage prevCanvas = null;

            for (int i = 0; i < numFrames; i++) {
                javax.imageio.IIOImage iioImg = reader.readAll(i, null);
                java.awt.image.BufferedImage frameImg =
                        (java.awt.image.BufferedImage) iioImg.getRenderedImage();

                // ── Parse per-frame metadata ──────────────────────────────────
                int  delayMs       = 100;
                int  frameLeft     = 0;
                int  frameTop      = 0;
                String disposalMethod = "none";

                javax.imageio.metadata.IIOMetadata meta = iioImg.getMetadata();
                if (meta != null) {
                    try {
                        org.w3c.dom.Node metaRoot =
                                meta.getAsTree("javax_imageio_gif_image_1.0");
                        org.w3c.dom.NodeList children = metaRoot.getChildNodes();
                        for (int j = 0; j < children.getLength(); j++) {
                            org.w3c.dom.Node child = children.item(j);
                            String nodeName = child.getNodeName();
                            org.w3c.dom.Element el = (org.w3c.dom.Element) child;
                            if ("GraphicControlExtension".equals(nodeName)) {
                                String d = el.getAttribute("delayTime");
                                if (d != null && !d.isEmpty()) {
                                    int raw = Integer.parseInt(d);
                                    delayMs = Math.max(20, raw * 10);
                                }
                                String dm = el.getAttribute("disposalMethod");
                                if (dm != null && !dm.isEmpty()) disposalMethod = dm;
                            } else if ("ImageDescriptor".equals(nodeName)) {
                                String l = el.getAttribute("imageLeftPosition");
                                String t = el.getAttribute("imageTopPosition");
                                if (l != null && !l.isEmpty()) frameLeft = Integer.parseInt(l);
                                if (t != null && !t.isEmpty()) frameTop  = Integer.parseInt(t);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // ── Handle disposal of PREVIOUS frame ────────────────────────
                // "restoreToPrevious" means after drawing this frame, the canvas
                // should revert; we snapshot *before* compositing.
                if ("restoreToPrevious".equalsIgnoreCase(disposalMethod) && prevCanvas != null) {
                    // Restore canvas to state before this frame
                    java.awt.Graphics2D gr = canvas.createGraphics();
                    gr.setComposite(java.awt.AlphaComposite.Src);
                    gr.drawImage(prevCanvas, 0, 0, null);
                    gr.dispose();
                }

                // Save a copy of the canvas before drawing (for next frame's restoreToPrevious)
                prevCanvas = new java.awt.image.BufferedImage(canvasW, canvasH,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D prevG = prevCanvas.createGraphics();
                prevG.setComposite(java.awt.AlphaComposite.Src);
                prevG.drawImage(canvas, 0, 0, null);
                prevG.dispose();

                // ── Composite this frame onto the canvas ──────────────────────
                java.awt.Graphics2D g = canvas.createGraphics();
                g.drawImage(frameImg, frameLeft, frameTop, null);
                g.dispose();

                // ── Snapshot the composed canvas as a JavaFX Image ────────────
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(canvas, "png", baos);
                frames.add(new Image(new java.io.ByteArrayInputStream(baos.toByteArray())));
                delays.add(delayMs);

                // ── Apply disposal for NEXT iteration ────────────────────────
                if ("restoreToBackgroundColor".equalsIgnoreCase(disposalMethod)) {
                    // Clear the area this frame occupied to transparent
                    java.awt.Graphics2D clr = canvas.createGraphics();
                    clr.setComposite(java.awt.AlphaComposite.Clear);
                    clr.fillRect(frameLeft, frameTop, frameImg.getWidth(), frameImg.getHeight());
                    clr.dispose();
                }
                // "none" / "doNotDispose" — leave canvas as-is for next frame
            }
            reader.dispose();
            iis.close();
        } catch (Exception e) {
            System.err.println("[gif] failed to decode " + path.getFileName() + ": " + e.getMessage());
        }
    }

    // ── Animated WebP engine ──────────────────────────────────────────────────

    /**
     * Returns true if the WebP file has a video stream with more than one frame
     * (i.e. it is an animated WebP rather than a static one).
     */
    private static boolean isAnimatedWebP(Path path) {
        try {
            AVFormatContext fmtCtx = avformat_alloc_context();
            if (avformat_open_input(fmtCtx, path.toString(), null, null) < 0) return false;
            if (avformat_find_stream_info(fmtCtx, (PointerPointer) null) < 0) {
                avformat_close_input(fmtCtx); return false;
            }
            // Animated WebP container has a video stream with duration > 1 frame
            boolean animated = false;
            for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                AVStream st = fmtCtx.streams(i);
                if (st.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO && st.nb_frames() > 1) {
                    animated = true; break;
                }
            }
            avformat_close_input(fmtCtx);
            return animated;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decode all frames from an animated WebP using JavaCPP FFmpeg.
     * Fills {@code frames} with JavaFX Images and {@code delays} with per-frame ms durations.
     */
    private static void loadWebPFrames(Path path, List<Image> frames, List<Integer> delays) {
        try {
            AVFormatContext fmtCtx = avformat_alloc_context();
            if (avformat_open_input(fmtCtx, path.toString(), null, null) < 0) return;
            if (avformat_find_stream_info(fmtCtx, (PointerPointer) null) < 0) {
                avformat_close_input(fmtCtx); return;
            }

            int vidStream = -1;
            for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                if (fmtCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    vidStream = i; break;
                }
            }
            if (vidStream < 0) { avformat_close_input(fmtCtx); return; }

            AVStream st = fmtCtx.streams(vidStream);
            AVCodec codec = avcodec_find_decoder(st.codecpar().codec_id());
            if (codec == null || codec.isNull()) { avformat_close_input(fmtCtx); return; }

            AVCodecContext codecCtx = avcodec_alloc_context3(codec);
            avcodec_parameters_to_context(codecCtx, st.codecpar());
            if (avcodec_open2(codecCtx, codec, (AVDictionary) null) < 0) {
                avcodec_free_context(codecCtx); avformat_close_input(fmtCtx); return;
            }

            // Compute time-base in ms for per-frame delay
            AVRational tb = st.time_base();
            double tbMs = (tb.den() > 0) ? (double) tb.num() / tb.den() * 1000.0 : 0;

            AVPacket pkt   = av_packet_alloc();
            AVFrame  frame = av_frame_alloc();
            long     prevPts = 0;

            while (av_read_frame(fmtCtx, pkt) >= 0) {
                if (pkt.stream_index() != vidStream) { av_packet_unref(pkt); continue; }
                if (avcodec_send_packet(codecCtx, pkt) < 0) { av_packet_unref(pkt); continue; }
                av_packet_unref(pkt);
                while (avcodec_receive_frame(codecCtx, frame) >= 0) {
                    int srcW = frame.width(), srcH = frame.height();
                    if (srcW <= 0 || srcH <= 0) continue;

                    // Convert to RGB24 via swscale
                    SwsContext swsCtx = sws_getContext(
                            srcW, srcH, frame.format(),
                            srcW, srcH, AV_PIX_FMT_RGB24,
                            SWS_BILINEAR, null, null, (DoublePointer) null);
                    if (swsCtx == null) continue;

                    AVFrame dst = av_frame_alloc();
                    dst.format(AV_PIX_FMT_RGB24); dst.width(srcW); dst.height(srcH);
                    av_frame_get_buffer(dst, 1);
                    sws_scale(swsCtx, frame.data(), frame.linesize(), 0, srcH,
                              dst.data(), dst.linesize());

                    int    stride   = dst.linesize(0);
                    byte[] rgbBytes = new byte[srcH * stride];
                    dst.data(0).get(rgbBytes);

                    java.awt.image.BufferedImage bi =
                            new java.awt.image.BufferedImage(srcW, srcH,
                                    java.awt.image.BufferedImage.TYPE_INT_RGB);
                    int[] pixels = new int[srcW * srcH];
                    for (int row = 0; row < srcH; row++)
                        for (int col = 0; col < srcW; col++) {
                            int b = row * stride + col * 3;
                            pixels[row * srcW + col] = ((rgbBytes[b]   & 0xFF) << 16)
                                                      | ((rgbBytes[b+1] & 0xFF) << 8)
                                                      |  (rgbBytes[b+2] & 0xFF);
                        }
                    bi.getRaster().setDataElements(0, 0, srcW, srcH, pixels);

                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(bi, "png", baos);
                    frames.add(new Image(new java.io.ByteArrayInputStream(baos.toByteArray())));

                    // Per-frame delay from PTS difference
                    long pts = frame.pts();
                    int delayMs = (tbMs > 0 && pts > prevPts)
                            ? (int) Math.max(20, (pts - prevPts) * tbMs)
                            : 100;
                    delays.add(delayMs);
                    prevPts = pts;

                    sws_freeContext(swsCtx);
                    av_frame_free(dst);
                }
            }

            av_frame_free(frame);
            av_packet_free(pkt);
            avcodec_free_context(codecCtx);
            avformat_close_input(fmtCtx);
        } catch (Exception e) {
            System.err.println("[webp] animated frame decode failed: " + e.getMessage());
        }
    }

    /** Start (or restart) the GIF animation Timeline on the FX thread. */
    private void startGifAnimation(List<Image> frames, List<Integer> delays) {
        stopGifAnimation();
        if (frames.isEmpty()) return;

        // Set first frame immediately so there's no blank flash
        imageView.setImage(frames.get(0));
        zoomFit();

        if (frames.size() == 1) return; // single-frame GIF — no animation needed

        final int[] idx = {0};
        // Accumulate keyframe times
        List<KeyFrame> keyFrames = new ArrayList<>();
        long accMs = 0;
        for (int i = 0; i < frames.size(); i++) {
            final Image frame = frames.get(i);
            accMs += delays.get(i);
            final long t = accMs;
            keyFrames.add(new KeyFrame(Duration.millis(t), e -> imageView.setImage(frame)));
        }
        gifTimeline = new Timeline(keyFrames.toArray(new KeyFrame[0]));
        gifTimeline.setCycleCount(Timeline.INDEFINITE);
        gifTimeline.play();
    }

    public void showVideo(Path path) {
        onFirstFrame = null;   // clear any pending fade-in from a previous load
        disposeVideo();
        imgScroll.setVisible(false);
        videoPane.setVisible(true);
        videoLoading = true;
        if (spinnerDelayTimer != null) spinnerDelayTimer.stop();
        spinnerDelayTimer = new Timeline(new KeyFrame(
                Duration.millis(SPINNER_DELAY_MS),
                e -> { if (videoLoading) loadingSpinner.setVisible(true); }));
        spinnerDelayTimer.play();
        currentVideoPath = path;
        subtitles.clear();
        ccGenerating = false;   // detach any in-flight Whisper job for the previous video
        ccGenToken++;           // invalidate stale Whisper callbacks
        whisperOverlay.setVisible(false);
        updateCCButtonStyle();

        if (!ensureVlcFactory()) {
            // VLC not installed — fall back to JavaFX MediaPlayer for common formats.
            // setOnError inside playWithFxMedia will display a friendly message if the
            // format is also unsupported by the JavaFX media engine.
            playWithFxMedia(path);
            return;
        }
        if (vlcPlayer == null) createVlcPlayer();

        vlcPlayer.audio().setVolume(muted ? 0 : (int)(volumeSlider.getValue() * 100));

        String fname = path.getFileName().toString().toLowerCase();
        boolean mayHaveAlpha = fname.endsWith(".webm") || fname.endsWith(".mov");
        if (mayHaveAlpha) {
            // Alpha path: FFmpeg pipes BGRA frames (alpha intact) + VLC audio-only.
            // VLC's BGRA callback always produces A=0xFF so we bypass its video pipeline.
            startAlphaVideo(path);
        } else {
            // Normal path: VLC handles everything.
            pauseOnFirstFrame = !autoPlay;
            vlcPlayer.media().play(path.toString());
        }

        onStatusUpdate.accept(path.getFileName().toString());
        Thread st = new Thread(() -> loadSubtitles(path), "subtitle-loader");
        st.setDaemon(true);
        st.start();
        startControlFadeTimer();
    }

    /** Set a one-shot callback that fires on the FX thread when the first decoded
     *  video frame is rendered.  Cleared automatically after it fires or when a new
     *  video is loaded.  Pass {@code null} to cancel any pending callback. */
    public void setOnFirstFrame(Runnable r) { this.onFirstFrame = r; }

    // ── JavaFX MediaPlayer fallback ───────────────────────────────────────────

    /**
     * Play {@code path} using JavaFX's built-in {@link javafx.scene.media.MediaPlayer}.
     * Used when libVLC is not installed.  Supports H.264 MP4, MOV, M4V and other
     * formats handled by the platform media engine (Windows Media Foundation on Win).
     * If the format is unsupported a friendly "install VLC" error overlay is shown.
     */
    private void playWithFxMedia(Path path) {
        usingFxMedia = true;
        videoView.setVisible(false);
        fxMediaView.setVisible(true);
        try {
            javafx.scene.media.Media media =
                    new javafx.scene.media.Media(path.toUri().toString());
            fxMediaPlayer = new javafx.scene.media.MediaPlayer(media);
            fxMediaView.setMediaPlayer(fxMediaPlayer);
            fxMediaPlayer.setVolume(muted ? 0 : volumeSlider.getValue());

            fxMediaPlayer.setOnReady(() -> Platform.runLater(() -> {
                javafx.util.Duration total = fxMediaPlayer.getTotalDuration();
                timeDuration.setText(formatTime(total));
                if (currentVideoPath != null)
                    onStatusUpdate.accept(formatVideoStatus(currentVideoPath, total));
                videoLoading = false;
                if (spinnerDelayTimer != null) spinnerDelayTimer.stop();
                loadingSpinner.setVisible(false);
                onFirstFrameArrived();
                if (autoPlay) {
                    fxMediaPlayer.play();
                    playPauseBtn.setText("⏸");
                }
            }));

            fxMediaPlayer.currentTimeProperty().addListener((obs, o, n) ->
                    Platform.runLater(() -> {
                        if (!seekInProgress) {
                            javafx.util.Duration total = fxMediaPlayer.getTotalDuration();
                            if (total != null && total.greaterThan(javafx.util.Duration.ZERO))
                                seekSlider.setValue(n.toMillis() / total.toMillis());
                            timeCurrent.setText(formatTime(n));
                            updateCC(n);
                        }
                    }));

            fxMediaPlayer.statusProperty().addListener((obs, o, n) ->
                    Platform.runLater(() -> {
                        if (n == javafx.scene.media.MediaPlayer.Status.PLAYING)
                            playPauseBtn.setText("⏸");
                        else if (n == javafx.scene.media.MediaPlayer.Status.PAUSED ||
                                 n == javafx.scene.media.MediaPlayer.Status.STOPPED)
                            playPauseBtn.setText("▶");
                    }));

            fxMediaPlayer.setOnEndOfMedia(() -> {
                if (loopBtn.isSelected()) {
                    fxMediaPlayer.seek(javafx.util.Duration.ZERO);
                    fxMediaPlayer.play();
                } else {
                    Platform.runLater(() -> {
                        playPauseBtn.setText("▶");
                        onEndOfMedia.run();
                    });
                }
            });

            fxMediaPlayer.setOnError(() -> Platform.runLater(() ->
                    showError("VLC media player is required for full video support.\n\n" +
                            "Download and install VLC from videolan.org,\nthen restart the app.")));

            onStatusUpdate.accept(path.getFileName().toString());
            Thread st = new Thread(() -> loadSubtitles(path), "subtitle-loader");
            st.setDaemon(true);
            st.start();
            startControlFadeTimer();

        } catch (Exception e) {
            showError("VLC media player is required for full video support.\n\n" +
                    "Download and install VLC from videolan.org,\nthen restart the app.\n(" +
                    e.getMessage() + ")");
        }
    }

    public void clearMedia() {
        onFirstFrame = null;
        disposeVideo();
        videoLoading = false;
        if (spinnerDelayTimer != null) { spinnerDelayTimer.stop(); spinnerDelayTimer = null; }
        loadingSpinner.setVisible(false);
        imgScroll.setVisible(false);
        videoPane.setVisible(false);
        imageView.setImage(null);
        currentVideoPath = null;
        currentImagePath = null;
        subtitles.clear();
        ccPane.setVisible(false);
        updateCCButtonStyle();
    }

    // ── Zoom / pan ────────────────────────────────────────────────────────────

    public void zoomIn()     { applyZoom(zoomScale * ZOOM_FACTOR); }
    public void zoomOut()    { applyZoom(zoomScale / ZOOM_FACTOR); }
    public void zoomActual() { applyZoom(1.0); }

    public void zoomFit() {
        Image img = imageView.getImage();
        if (img == null || img.getWidth() == 0) return;
        double vw = imgScroll.getViewportBounds().getWidth();
        double vh = imgScroll.getViewportBounds().getHeight();
        if (vw <= 0 || vh <= 0) { Platform.runLater(this::zoomFit); return; }
        applyZoom(Math.min(vw / img.getWidth(), vh / img.getHeight()));
        resetPanAndCenter();
    }

    public void zoomFill() {
        Image img = imageView.getImage();
        if (img == null || img.getWidth() == 0) return;
        double vw = imgScroll.getViewportBounds().getWidth();
        double vh = imgScroll.getViewportBounds().getHeight();
        if (vw <= 0 || vh <= 0) { Platform.runLater(this::zoomFill); return; }
        applyZoom(Math.max(vw / img.getWidth(), vh / img.getHeight()));
        resetPanAndCenter();
    }

    /** Clears the panned flag and centers the scroll position on the next layout pass. */
    private void resetPanAndCenter() {
        hasPanned = false;
        Platform.runLater(() -> { imgScroll.setHvalue(0.5); imgScroll.setVvalue(0.5); });
    }

    public void rotateRight() {
        rotation = (rotation + 90) % 360;
        imageView.setRotate(rotation);
    }

    public void rotateLeft() {
        rotation = (rotation - 90 + 360) % 360;
        imageView.setRotate(rotation);
    }

    // ── Video controls ────────────────────────────────────────────────────────

    public void togglePlayPause() {
        if (usingFxMedia) {
            if (fxMediaPlayer == null) return;
            if (fxMediaPlayer.getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING)
                fxMediaPlayer.pause();
            else fxMediaPlayer.play();
            flashControls();
            return;
        }
        if (alphaMode) {
            apPaused = !apPaused;
            if (vlcPlayer != null) {
                if (apPaused) vlcPlayer.controls().pause();
                else          vlcPlayer.controls().play();
            }
            playPauseBtn.setText(apPaused ? "▶" : "⏸");
            flashControls();
            return;
        }
        if (vlcPlayer == null) return;
        if (vlcPlayer.status().isPlaying()) vlcPlayer.controls().pause();
        else vlcPlayer.controls().play();
        flashControls();
    }

    public void restartVideo() {
        if (usingFxMedia) {
            if (fxMediaPlayer == null) return;
            fxMediaPlayer.seek(javafx.util.Duration.ZERO);
            fxMediaPlayer.play();
            playPauseBtn.setText("⏸");
            flashControls();
            return;
        }
        if (alphaMode) {
            if (vlcPlayer != null) { vlcPlayer.controls().setTime(0); vlcPlayer.controls().play(); }
            startAlphaFfmpegAt(0);
            apPaused = false;
            playPauseBtn.setText("⏸");
            flashControls();
            return;
        }
        if (vlcPlayer == null) return;
        vlcPlayer.controls().setTime(0);
        vlcPlayer.controls().play();
        flashControls();
    }

    public void seekRelative(double seconds) {
        if (usingFxMedia) {
            if (fxMediaPlayer == null) return;
            javafx.util.Duration cur   = fxMediaPlayer.getCurrentTime();
            javafx.util.Duration total = fxMediaPlayer.getTotalDuration();
            javafx.util.Duration target = cur.add(javafx.util.Duration.seconds(seconds));
            if (target.lessThan(javafx.util.Duration.ZERO)) target = javafx.util.Duration.ZERO;
            if (total != null && target.greaterThan(total)) target = total;
            fxMediaPlayer.seek(target);
            flashControls();
            return;
        }
        if (alphaMode) {
            long cur   = vlcPlayer != null ? vlcPlayer.status().time() : 0;
            long total = vlcPlayer != null ? vlcPlayer.status().length() : apDurationMs;
            long target = Math.max(0, total > 0 ? Math.min(total, cur + (long)(seconds * 1000))
                                                : cur + (long)(seconds * 1000));
            if (vlcPlayer != null) vlcPlayer.controls().setTime(target);
            startAlphaFfmpegAt(target);
            flashControls();
            return;
        }
        if (vlcPlayer == null) return;
        long cur    = vlcPlayer.status().time();
        long total  = vlcPlayer.status().length();
        long target = Math.max(0, Math.min(total, cur + (long)(seconds * 1000)));
        vlcPlayer.controls().setTime(target);
        flashControls();
    }

    /**
     * Open an input dialog to seek to a specific timestamp (mm:ss or hh:mm:ss).
     */
    public void seekToTimestamp() { openSeekDialog(); }

    public void setVolume(double vol) {
        volumeSlider.setValue(Math.max(0, Math.min(1, vol)));
        if (!muted) {
            if (usingFxMedia && fxMediaPlayer != null) fxMediaPlayer.setVolume(vol);
            else if (vlcPlayer != null) vlcPlayer.audio().setVolume((int)(vol * 100));
        }
    }

    public void toggleMute() {
        muted = !muted;
        muteBtn.setText(muted ? "🔇" : "🔊");
        if (usingFxMedia && fxMediaPlayer != null)
            fxMediaPlayer.setVolume(muted ? 0 : volumeSlider.getValue());
        else if (vlcPlayer != null)
            vlcPlayer.audio().setVolume(muted ? 0 : (int)(volumeSlider.getValue() * 100));
    }

    public void stopVideo() {
        if (usingFxMedia) {
            if (fxMediaPlayer != null) {
                fxMediaPlayer.stop();
                fxMediaPlayer.dispose();
                fxMediaPlayer = null;
            }
            fxMediaView.setMediaPlayer(null);
            fxMediaView.setVisible(false);
            videoView.setVisible(true);
            usingFxMedia = false;
            return;
        }
        if (vlcPlayer != null) {
            // Save resume position before releasing the player
            if (currentVideoPath != null) {
                long pos = vlcPlayer.status().time();
                long len = vlcPlayer.status().length();
                boolean nearEnd = len > 0 && pos >= len - 30_000;
                onSavePosition.accept(currentVideoPath, (nearEnd || pos < 10_000) ? 0L : pos);
            }
            vlcPlayer.controls().stop();
            vlcPlayer.release();
            vlcPlayer = null;
        }
    }

    /**
     * Returns true if a video is currently loaded and playing.
     * Used by the slideshow to decide whether to wait for end-of-media.
     */
    public boolean isVideoPlaying() {
        if (usingFxMedia)
            return fxMediaPlayer != null &&
                   fxMediaPlayer.getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING;
        if (alphaMode) return !apPaused;
        return vlcPlayer != null && vlcPlayer.status().isPlaying();
    }

    /** Returns true if a video is loaded and currently paused (not playing). */
    public boolean isVideoPaused() {
        return videoPane.isVisible() && !isVideoPlaying();
    }

    /** Returns the current playback position in milliseconds, or 0 if no video is loaded. */
    public long getCurrentVideoTimeMs() {
        if (usingFxMedia && fxMediaPlayer != null)
            return (long) fxMediaPlayer.getCurrentTime().toMillis();
        if (alphaMode) return vlcPlayer != null ? vlcPlayer.status().time() : 0;
        return vlcPlayer != null ? vlcPlayer.status().time() : 0;
    }

    /**
     * Advance or rewind by one frame while paused.
     * Forward uses VLC's native nextFrame(); backward seeks back ~33 ms (≈1 frame at 30 fps).
     */
    public void stepFrame(boolean forward) {
        if (alphaMode) {
            long frameMs = apFps > 0 ? Math.max(1, (long)(1000.0 / apFps)) : 33;
            long current = vlcPlayer != null ? vlcPlayer.status().time() : 0;
            seekToMs(forward ? current + frameMs : Math.max(0, current - frameMs));
            return;
        }
        if (vlcPlayer == null) return;
        if (forward) {
            vlcPlayer.controls().nextFrame();
        } else {
            long current = vlcPlayer.status().time();
            seekToMs(Math.max(0, current - 33));
        }
    }

    /**
     * Returns a snapshot of the current video frame as a new WritableImage,
     * or {@code null} if no video frame is available.
     */
    public WritableImage captureCurrentFrame() {
        WritableImage src = videoImage;
        if (!videoPane.isVisible() || src == null) return null;
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        if (w <= 0 || h <= 0) return null;
        return new WritableImage(src.getPixelReader(), 0, 0, w, h);
    }

    /**
     * Returns true if a video is currently loaded (regardless of play state).
     */
    public boolean hasVideo() { return vlcPlayer != null; }

    /**
     * Returns the best available image for printing:
     *   • image mode  → the currently loaded Image
     *   • video mode  → a snapshot of the current videoImage (WritableImage)
     * Returns null if nothing is displayed.
     */
    public Image getCurrentDisplayImage() {
        if (imgScroll.isVisible() && imageView.getImage() != null)
            return imageView.getImage();
        if (videoPane.isVisible() && videoImage != null)
            return videoImage;
        return null;
    }

    // ── Transitions & prefetch ────────────────────────────────────────────────

    public void setTransitionMode(TransitionMode mode) { this.transitionMode = mode; }
    public TransitionMode getTransitionMode()           { return transitionMode; }

    /** Override the transition cover colour.  Pass {@code null} to revert to the
     *  theme background ({@code -fx-background}).  Used by Dip-to-Black mode. */
    public void setCoverColor(javafx.scene.paint.Color c) {
        if (c == null) transitionCover.setStyle("-fx-background-color: -fx-background;");
        else           transitionCover.setStyle("-fx-background-color: " + toWeb(c) + ";");
    }
    private static String toWeb(javafx.scene.paint.Color c) {
        return String.format("rgba(%d,%d,%d,%.3f)",
                (int)(c.getRed()*255), (int)(c.getGreen()*255),
                (int)(c.getBlue()*255), c.getOpacity());
    }

    /** Fades the transition cover to opacity 1 (hides video).
     *  @param ms      duration in milliseconds
     *  @param onDone  called on the FX thread when the fade completes */
    public void fadeToBlack(int ms, Runnable onDone) {
        FadeTransition ft = new FadeTransition(Duration.millis(ms), transitionCover);
        ft.setFromValue(transitionCover.getOpacity());
        ft.setToValue(1.0);
        if (onDone != null) ft.setOnFinished(e -> onDone.run());
        ft.play();
    }

    /** Fades the transition cover back to opacity 0 (reveals video). */
    public void fadeFromBlack(int ms) {
        FadeTransition ft = new FadeTransition(Duration.millis(ms), transitionCover);
        ft.setFromValue(transitionCover.getOpacity());
        ft.setToValue(0.0);
        ft.play();
    }

    /**
     * Display a JavaFX {@link Image} directly — used for clipboard paste and similar
     * sources that have no corresponding file path.
     * Applies the current transition mode and zoom-fit, just like {@link #showImage}.
     */
    public void showDirectImage(Image img, String label) {
        disposeVideo();
        currentImagePath = null;
        videoPane.setVisible(false);
        if (transitionMode == TransitionMode.NONE || !imgScroll.isVisible()) {
            imgScroll.setOpacity(1.0);
            imgScroll.setVisible(true);
            imageView.setImage(img);
            Platform.runLater(() -> { zoomFit(); onStatusUpdate.accept(label); });
        } else {
            imgScroll.setVisible(true);
            FadeTransition out = new FadeTransition(
                    Duration.millis(transitionMode == TransitionMode.DIP_TO_BLACK ? 100 : 120), imgScroll);
            out.setToValue(0.0);
            out.setOnFinished(e -> {
                imageView.setImage(img);
                Platform.runLater(() -> {
                    zoomFit();
                    FadeTransition in = new FadeTransition(Duration.millis(180), imgScroll);
                    in.setFromValue(0.0);
                    in.setToValue(1.0);
                    in.play();
                    onStatusUpdate.accept(label);
                });
            });
            out.play();
        }
    }

    /**
     * Prefetch an image in the background so it is ready when {@link #showImage} is called.
     * Keeps a small cache (max 4 entries); evicts all when full.
     */
    public void prefetchImage(Path path) {
        String key = path.toUri().toString();
        if (prefetchCache.containsKey(key)) return;
        if (prefetchCache.size() >= 4) prefetchCache.clear();
        // Background load — the Image constructor with backgroundLoading=true
        Image img = new Image(key, true);
        prefetchCache.put(key, img);
    }

    // ── Toast ─────────────────────────────────────────────────────────────────

    /** Show a short overlay notification message that fades out after ~3 s. */
    public void showToast(String msg) {
        Platform.runLater(() -> {
            toastLabel.setText(msg);
            toastLabel.setOpacity(1.0);
            toastLabel.setVisible(true);
            if (toastTimer != null) toastTimer.stop();
            toastTimer = new Timeline(new KeyFrame(Duration.seconds(2.8), e -> {
                FadeTransition ft = new FadeTransition(Duration.millis(500), toastLabel);
                ft.setToValue(0.0);
                ft.setOnFinished(ev -> toastLabel.setVisible(false));
                ft.play();
            }));
            toastTimer.play();
        });
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    public void setLoopEnabled(boolean loop) {
        loopBtn.setSelected(loop);
        updateLoopButtonStyle(loop);
        // Loop behaviour is handled in the finished() event callback inside createVlcPlayer()
    }

    public boolean isLoopEnabled() { return loopBtn.isSelected(); }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Returns the currently displayed image or current video frame, or {@code null} if nothing is loaded. */
    public Image getCurrentImage() {
        if (imgScroll.isVisible())  return imageView.getImage();
        if (videoPane.isVisible())  return videoView.getImage();
        return null;
    }

    /** Returns the current zoom scale (1.0 = 100%). */
    public double getZoomScale() { return zoomScale; }

    /** Called with (path, ms) whenever a video is disposed — ms=0 means clear saved position. */
    public void setOnSavePosition(BiConsumer<Path, Long> cb) { onSavePosition = cb; }

    public void setOnGetBookmarks(Function<String, List<AppConfig.VideoBookmark>> cb) { onGetBookmarks = cb; }
    public void setOnAddBookmark(BookmarkCallback cb)                                  { onAddBookmark = cb; }
    public void setOnDeleteBookmark(BiConsumer<String, String> cb)                     { onDeleteBookmark = cb; }

    public void setOnFrameCapture(Runnable cb)         { onFrameCapture = cb; }
    public void setOnCopyImageToClipboard(Runnable cb) { onCopyImageToClipboard = cb; }

    /** Seek to {@code ms} immediately, or defer until the player fires its first 'playing' event. */
    public void seekToMs(long ms) {
        if (usingFxMedia && fxMediaPlayer != null) {
            fxMediaPlayer.seek(javafx.util.Duration.millis(ms));
            return;
        }
        if (vlcPlayer != null) {
            vlcPlayer.controls().setTime(ms);
            if (alphaMode) startAlphaFfmpegAt(ms);
        } else {
            pendingSeekMs = ms;
        }
    }

    /**
     * Show a non-blocking resume prompt above the video controls.
     * The prompt auto-dismisses after 6 s if the user does nothing.
     */
    public void showResumePrompt(long posMs, Runnable onResume) {
        if (resumeBar == null) return;
        hideResumePrompt();   // cancel any previous prompt
        resumeTimeLabel.setText("Resume from  " + formatTime(Duration.millis(posMs)) + "?");
        resumeResumeBtn.setOnAction(e -> { hideResumePrompt(); onResume.run(); });
        resumeBar.setOpacity(1.0);
        resumeBar.setVisible(true);

        // Auto-dismiss: fade out after 5 s, hide at 6 s
        resumeDismissTimer = new Timeline(
                new KeyFrame(Duration.seconds(5), ev -> {
                    FadeTransition ft = new FadeTransition(Duration.millis(800), resumeBar);
                    ft.setToValue(0.0);
                    ft.setOnFinished(f -> resumeBar.setVisible(false));
                    ft.play();
                }));
        resumeDismissTimer.play();
    }

    private void hideResumePrompt() {
        if (resumeDismissTimer != null) { resumeDismissTimer.stop(); resumeDismissTimer = null; }
        if (resumeBar != null) resumeBar.setVisible(false);
    }

    public void setOnStatusUpdate(Consumer<String> cb)  { onStatusUpdate = cb; }
    public void setOnFullscreenRequest(Runnable cb)     { onFullscreenRequest = cb; }
    /**
     * Called when a video reaches its natural end (not when stopped/looping).
     * Used by the slideshow to advance to the next file.
     */
    public void setOnEndOfMedia(Runnable cb)            { onEndOfMedia = cb; }
    /** When true, the next video loaded via showVideo() will auto-play instead of pausing on the first frame. */
    public void setAutoPlay(boolean v)                  { this.autoPlay = v; }

    // ── CC / Subtitle ─────────────────────────────────────────────────────────

    private void toggleCC() {
        if (subtitles.isEmpty() && currentVideoPath != null) {
            List<Path> candidates = findSubtitleFiles(currentVideoPath);
            if (!candidates.isEmpty()) {
                if (candidates.size() == 1) {
                    applySubtitleFile(candidates.get(0), currentVideoPath);
                    ccVisible = true;
                    updateCCButtonStyle();
                } else {
                    pickAndLoadSubtitle(candidates, currentVideoPath);
                }
                return;
            }
        }

        if (subtitles.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "No subtitle file found (.srt / .vtt).\n\nGenerate captions using Whisper AI?\n" +
                    "(Requires faster-whisper — pip install faster-whisper)",
                    ButtonType.YES, ButtonType.NO);
            a.setTitle("Generate Captions");
            a.setHeaderText(null);
            styledAlert(a);
            a.showAndWait().ifPresent(bt -> { if (bt == ButtonType.YES) generateCaptions(); });
            return;
        }

        ccVisible = !ccVisible;
        if (!ccVisible) ccPane.setVisible(false);
        updateCCButtonStyle();
    }

    private void showCCContextMenu(double screenX, double screenY) {
        ContextMenu cm = new ContextMenu();

        // ── Whisper model submenu ─────────────────────────────────────────────
        Menu genMenu = new Menu("🎙 Generate CC with Whisper");
        javafx.scene.control.ToggleGroup modelGroup = new javafx.scene.control.ToggleGroup();
        for (String model : new String[]{"tiny", "base", "small", "medium", "large-v3"}) {
            javafx.scene.control.RadioMenuItem item = new javafx.scene.control.RadioMenuItem(model);
            item.setToggleGroup(modelGroup);
            item.setSelected(model.equals(whisperModel));
            item.setOnAction(e -> { whisperModel = model; generateCaptions(); });
            genMenu.getItems().add(item);
        }

        MenuItem reloadItem = new MenuItem("↺ Reload subtitle file");
        reloadItem.setOnAction(e -> {
            if (currentVideoPath != null) {
                subtitles.clear();
                updateCCButtonStyle();
                List<Path> candidates = findSubtitleFiles(currentVideoPath);
                if (!candidates.isEmpty()) {
                    if (candidates.size() == 1) {
                        applySubtitleFile(candidates.get(0), currentVideoPath);
                        ccVisible = true;
                        updateCCButtonStyle();
                    } else {
                        pickAndLoadSubtitle(candidates, currentVideoPath);
                    }
                } else {
                    onStatusUpdate.accept("No subtitle file found.");
                }
            }
        });
        MenuItem translateItem = new MenuItem("🌐 Translate CC…");
        translateItem.setDisable(subtitles.isEmpty());
        translateItem.setOnAction(e -> showTranslateDialog());

        javafx.scene.control.CheckMenuItem karaokeItem =
                new javafx.scene.control.CheckMenuItem("✨ Karaoke word highlighting");
        karaokeItem.setSelected(karaokeEnabled);
        karaokeItem.setOnAction(e -> { karaokeEnabled = karaokeItem.isSelected(); lastCCIndex = -1; });

        cm.getItems().addAll(genMenu, new SeparatorMenuItem(),
                translateItem, reloadItem, new SeparatorMenuItem(), karaokeItem);
        cm.show(ccBtn, screenX, screenY);
    }

    // =========================================================================
    // CC TRANSLATION  (Argos Translate via Python bridge)
    // =========================================================================

    /** Simple holder for a language code + display name shown in combo boxes. */
    private record LangEntry(String code, String name) {
        @Override public String toString() { return name + " (" + code + ")"; }
    }

    /**
     * Opens the Translate CC dialog.  Starts the TranslationService on first call,
     * loads installed language packs, lets the user pick source → target language,
     * and runs the translation on a background thread.
     */
    private void showTranslateDialog() {
        if (subtitles.isEmpty()) return;

        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.initOwner(ccBtn.getScene().getWindow());
        dlg.setResizable(false);

        // ── Controls ────────────────────────────────────────────────────────
        Label srcLabel = new Label("From:");
        Label tgtLabel = new Label("To:");
        ComboBox<LangEntry> srcBox = new ComboBox<>();
        ComboBox<LangEntry> tgtBox = new ComboBox<>();
        srcBox.setPrefWidth(230);
        tgtBox.setPrefWidth(230);

        Label statusLbl = new Label("Loading installed language packs…");
        statusLbl.setWrapText(true);
        statusLbl.setMaxWidth(420);

        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(420);
        progress.setVisible(false);

        Button downloadBtn  = new Button("⬇ Download language pack…");
        Button translateBtn = new Button("Translate");
        Button cancelBtn    = new Button("Close");
        translateBtn.setDisable(true);
        downloadBtn.setVisible(false);

        translateBtn.setDefaultButton(true);
        cancelBtn.setCancelButton(true);

        // ── Layout ──────────────────────────────────────────────────────────
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, srcLabel, srcBox);
        grid.addRow(1, tgtLabel, tgtBox);
        GridPane.setColumnSpan(statusLbl, 2);
        GridPane.setColumnSpan(progress,  2);
        GridPane.setColumnSpan(downloadBtn, 2);
        grid.add(statusLbl,  0, 2);
        grid.add(progress,   0, 3);
        grid.add(downloadBtn, 0, 4);

        HBox btnRow = new HBox(8, translateBtn, cancelBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(0, 16, 12, 16));

        VBox root = new VBox(0, makeDialogTitleBar("Translate Subtitles", dlg), grid, btnRow);
        Scene dlgScene = new Scene(root);
        styledScene(dlg, dlgScene);
        dlg.setScene(dlgScene);
        dlg.setOnShown(e -> centerOnOwner(dlg));

        cancelBtn.setOnAction(e -> dlg.close());

        // ── Load installed packs (background) ───────────────────────────────
        Thread loader = new Thread(() -> {
            TranslationService svc = TranslationService.getInstance();
            boolean started = svc.start();
            if (!started) {
                Platform.runLater(() -> statusLbl.setText(
                        "❌ argostranslate not found.\n" +
                        "Install it with:  pip install argostranslate"));
                return;
            }
            try {
                List<Map<String, String>> installed = svc.listInstalled();

                // Auto-detect source language from first 10 subtitle entries.
                String detectedCode = "unknown";
                try {
                    String sample = subtitles.stream()
                            .limit(10)
                            .map(SubtitleEntry::text)
                            .collect(Collectors.joining(" "));
                    detectedCode = svc.detect(sample);
                } catch (Exception e) {
                    System.err.println("[translate] language detection failed: " + e.getMessage());
                    // keep detectedCode as "unknown" — user can still select the source manually
                }
                final String detCode = detectedCode;

                Platform.runLater(() -> {
                    if (installed.isEmpty()) {
                        statusLbl.setText(
                                "No language packs installed yet.\n" +
                                "Click below to browse & download packs.");
                        downloadBtn.setVisible(true);
                        return;
                    }

                    // Build source list: distinct "from" codes in installed packs.
                    Map<String, LangEntry> fromMap = new LinkedHashMap<>();
                    for (Map<String, String> p : installed) {
                        fromMap.putIfAbsent(p.get("from"),
                                new LangEntry(p.get("from"), p.get("from_name")));
                    }
                    srcBox.getItems().setAll(fromMap.values());

                    // Pre-select detected source language if present.
                    srcBox.getItems().stream()
                            .filter(e -> e.code().equals(detCode))
                            .findFirst()
                            .ifPresentOrElse(
                                    e -> srcBox.getSelectionModel().select(e),
                                    ()  -> srcBox.getSelectionModel().selectFirst());

                    // Populate targets for the selected source.
                    refreshTargets(srcBox, tgtBox, installed, downloadBtn, translateBtn, statusLbl);

                    statusLbl.setText(installed.size() + " language pack(s) installed.  " +
                            (detCode.equals("unknown") ? "" : "Detected: " + detCode));
                    translateBtn.setDisable(false);
                });

                // Re-populate target list whenever source changes.
                srcBox.setOnAction(e -> {
                    tgtBox.getItems().clear();
                    translateBtn.setDisable(true);
                    refreshTargets(srcBox, tgtBox, installed, downloadBtn, translateBtn, statusLbl);
                });

            } catch (Exception e) {
                Platform.runLater(() ->
                        statusLbl.setText("Error: " + e.getMessage()));
            }
        }, "translate-loader");
        loader.setDaemon(true);
        loader.start();

        // ── Download pack button ─────────────────────────────────────────────
        downloadBtn.setOnAction(e -> showDownloadDialog(dlg));

        // ── Translate button ─────────────────────────────────────────────────
        translateBtn.setOnAction(e -> {
            LangEntry src = srcBox.getValue();
            LangEntry tgt = tgtBox.getValue();
            if (src == null || tgt == null) return;
            if (src.code().equals(tgt.code())) {
                statusLbl.setText("Source and target language are the same.");
                return;
            }

            translateBtn.setDisable(true);
            downloadBtn.setVisible(false);
            progress.setVisible(true);
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            statusLbl.setText("Translating…");
            translating = true;

            // Snapshot of current subtitle texts (immutable during translation).
            List<String> texts = subtitles.stream()
                    .map(SubtitleEntry::text)
                    .collect(Collectors.toList());
            final String fromCode = src.code();
            final String toCode   = tgt.code();
            final Path   vidPath  = currentVideoPath;

            Thread worker = new Thread(() -> {
                try {
                    TranslationService svc = TranslationService.getInstance();
                    // Translate in chunks of 50 so we can report progress.
                    int    chunkSize  = 50;
                    int    total      = texts.size();
                    List<String> translated = new ArrayList<>(total);

                    for (int i = 0; i < total; i += chunkSize) {
                        int end   = Math.min(i + chunkSize, total);
                        List<String> chunk = texts.subList(i, end);
                        List<String> result = svc.translateList(chunk, fromCode, toCode);
                        translated.addAll(result);
                        final double pct = (double) translated.size() / total;
                        Platform.runLater(() -> {
                            progress.setProgress(pct);
                            statusLbl.setText(String.format("Translating… %d / %d", translated.size(), total));
                        });
                    }

                    // Rebuild subtitle list with translated text.
                    List<SubtitleEntry> newSubs = new ArrayList<>(total);
                    for (int i = 0; i < total; i++) {
                        SubtitleEntry orig = subtitles.get(i);
                        newSubs.add(new SubtitleEntry(orig.start(), orig.end(), translated.get(i)));
                    }

                    // Save translated .srt alongside the originals.
                    String savedMsg = "";
                    if (vidPath != null && vidPath.getParent() != null) {
                        try {
                            Path subsDir = vidPath.getParent().resolve("subtitles");
                            Files.createDirectories(subsDir);
                            String stem = vidPath.getFileName().toString()
                                    .replaceAll("\\.[^.]+$", "");
                            Path outFile = subsDir.resolve(stem + "_" + fromCode + "_" + toCode + ".srt");
                            saveSrt(newSubs, outFile);
                            savedMsg = "\nSaved: " + outFile.getFileName();
                        } catch (IOException ioEx) {
                            savedMsg = "\n(Could not save .srt: " + ioEx.getMessage() + ")";
                        }
                    }

                    final List<SubtitleEntry> finalSubs = newSubs;
                    final String              finalMsg  = savedMsg;
                    Platform.runLater(() -> {
                        subtitles.clear();
                        subtitles.addAll(finalSubs);
                        ccVisible = true;
                        translating = false;
                        updateCCButtonStyle();
                        progress.setProgress(1.0);
                        statusLbl.setText("✓ Translation complete!  " + total + " subtitles." + finalMsg);
                        translateBtn.setDisable(false);
                        onStatusUpdate.accept("CC translated: " + fromCode + " → " + toCode +
                                "  (" + total + " entries)");
                        showToast("Translation complete ✓");
                    });

                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        translating = false;
                        progress.setVisible(false);
                        translateBtn.setDisable(false);
                        statusLbl.setText("❌ Error: " + ex.getMessage());
                    });
                }
            }, "translate-worker");
            worker.setDaemon(true);
            worker.start();
        });

        dlg.showAndWait();
    }

    /**
     * Populates {@code tgtBox} with "to" languages reachable from the currently
     * selected source language.  Shows / hides the download button accordingly.
     */
    private void refreshTargets(ComboBox<LangEntry> srcBox,
                                ComboBox<LangEntry> tgtBox,
                                List<Map<String, String>> installed,
                                Button downloadBtn,
                                Button translateBtn,
                                Label  statusLbl) {
        LangEntry src = srcBox.getValue();
        if (src == null) return;
        Map<String, LangEntry> toMap = new LinkedHashMap<>();
        for (Map<String, String> p : installed) {
            if (p.get("from").equals(src.code())) {
                toMap.putIfAbsent(p.get("to"),
                        new LangEntry(p.get("to"), p.get("to_name")));
            }
        }
        tgtBox.getItems().setAll(toMap.values());
        if (toMap.isEmpty()) {
            downloadBtn.setVisible(true);
            translateBtn.setDisable(true);
            statusLbl.setText("No packs installed for " + src.name() +
                    ".  Download one first.");
        } else {
            tgtBox.getSelectionModel().selectFirst();
            downloadBtn.setVisible(false);
            translateBtn.setDisable(false);
        }
    }

    /**
     * Opens a secondary dialog listing all available Argos Translate language packs
     * so the user can download the ones they need.
     */
    private void showDownloadDialog(Stage owner) {
        Stage dlg = new Stage(StageStyle.UNDECORATED);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.initOwner(owner);

        Label info = new Label("Fetching available packs from Argos index…");
        info.setWrapText(true);
        info.setMaxWidth(380);

        ListView<String> listView = new ListView<>();
        listView.setPrefSize(400, 260);

        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setPrefWidth(400);

        Button installBtn = new Button("⬇ Install selected pack");
        Button closeBtn   = new Button("Close");
        installBtn.setDisable(true);
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> dlg.close());

        HBox btns = new HBox(8, installBtn, closeBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);
        btns.setPadding(new Insets(0, 16, 12, 16));

        VBox body = new VBox(8, info, bar, listView, btns);
        body.setPadding(new Insets(16));
        VBox root = new VBox(0, makeDialogTitleBar("Download Language Pack", dlg), body);
        Scene dlgScene2 = new Scene(root);
        styledScene(dlg, dlgScene2);
        dlg.setScene(dlgScene2);

        // Map: display string → {from, to}
        Map<String, String[]> packMap = new LinkedHashMap<>();

        Thread fetcher = new Thread(() -> {
            try {
                List<Map<String, Object>> available =
                        TranslationService.getInstance().listAvailable();
                Platform.runLater(() -> {
                    bar.setVisible(false);
                    for (Map<String, Object> p : available) {
                        boolean inst = Boolean.TRUE.equals(p.get("installed"));
                        String  label = p.get("from_name") + " → " + p.get("to_name") +
                                (inst ? "  ✓" : "");
                        packMap.put(label, new String[]{
                                String.valueOf(p.get("from")),
                                String.valueOf(p.get("to"))});
                    }
                    listView.getItems().setAll(packMap.keySet());
                    info.setText("Select a language pack to install:");
                    listView.getSelectionModel().selectedItemProperty().addListener(
                            (obs, o, n) -> installBtn.setDisable(n == null || n.endsWith("✓")));
                });
            } catch (Exception e) {
                Platform.runLater(() -> info.setText("Error: " + e.getMessage()));
            }
        }, "pack-fetcher");
        fetcher.setDaemon(true);
        fetcher.start();

        installBtn.setOnAction(e -> {
            String sel = listView.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String[] codes = packMap.get(sel);
            installBtn.setDisable(true);
            bar.setVisible(true);
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            info.setText("Downloading " + sel + " …  (this may take a minute)");
            Thread installer = new Thread(() -> {
                try {
                    TranslationService.getInstance().install(codes[0], codes[1]);
                    Platform.runLater(() -> {
                        bar.setVisible(false);
                        info.setText("✓ Installed!  Close this window and retry translation.");
                        // Mark as installed in list.
                        int idx = listView.getItems().indexOf(sel);
                        if (idx >= 0) listView.getItems().set(idx, sel + "  ✓");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        bar.setVisible(false);
                        installBtn.setDisable(false);
                        info.setText("❌ Install failed: " + ex.getMessage());
                    });
                }
            }, "pack-installer");
            installer.setDaemon(true);
            installer.start();
        });

        dlg.setOnShown(e -> centerOnOwner(dlg));
        dlg.showAndWait();
    }

    /** Writes a list of SubtitleEntry objects to an SRT file. */
    private static void saveSrt(List<SubtitleEntry> entries, Path dest) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry e = entries.get(i);
            sb.append(i + 1).append('\n');
            sb.append(srtTime(e.start())).append(" --> ").append(srtTime(e.end())).append('\n');
            sb.append(e.text()).append('\n').append('\n');
        }
        Files.writeString(dest, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String srtTime(Duration d) {
        long ms    = (long) d.toMillis();
        long hh    = ms / 3_600_000;    ms %= 3_600_000;
        long mm    = ms / 60_000;       ms %= 60_000;
        long ss    = ms / 1_000;        ms %= 1_000;
        return String.format("%02d:%02d:%02d,%03d", hh, mm, ss, ms);
    }

    // =========================================================================

    private void updateCCButtonStyle() {
        if (ccGenerating) {
            ccBtn.setText("CC…");
            ccBtn.setStyle("-fx-text-fill: #f5a623;");
            whisperIndicator.setVisible(true);
        } else if (!subtitles.isEmpty() && ccVisible) {
            ccBtn.setText("CC●");
            ccBtn.setStyle("-fx-text-fill: #66d966;");
            whisperIndicator.setVisible(false);
        } else if (!subtitles.isEmpty()) {
            ccBtn.setText("CC");
            ccBtn.setStyle("-fx-text-fill: #aaaaaa;");
            whisperIndicator.setVisible(false);
        } else {
            ccBtn.setText("CC");
            ccBtn.setStyle("-fx-text-fill: #555;");
            whisperIndicator.setVisible(false);
        }
    }

    private void loadSubtitles(Path videoPath) {
        List<Path> candidates = findSubtitleFiles(videoPath);
        if (candidates.isEmpty()) return;
        Platform.runLater(() -> {
            if (!videoPath.equals(currentVideoPath)) return;
            if (candidates.size() == 1) {
                applySubtitleFile(candidates.get(0), videoPath);
            } else {
                pickAndLoadSubtitle(candidates, videoPath);
            }
        });
    }

    /**
     * Find all .srt / .vtt subtitle files for the given video path.
     * Searches the same dir and a {@code subtitles/} subdir.
     * Returns paths in discovery order (stem-exact matches first).
     */
    private static List<Path> findSubtitleFiles(Path videoPath) {
        Path parent = videoPath.getParent();
        if (parent == null) return List.of();
        String name = videoPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;

        List<Path> results = new ArrayList<>();
        List<Path> searchDirs = new ArrayList<>(2);
        searchDirs.add(parent);
        Path subDir = parent.resolve("subtitles");
        if (Files.isDirectory(subDir)) searchDirs.add(subDir);

        for (Path dir : searchDirs) {
            // Exact stem matches first
            for (String ext : List.of(".srt", ".vtt")) {
                Path p = dir.resolve(stem + ext);
                if (Files.exists(p) && !results.contains(p)) results.add(p);
            }
            // Then any file whose name starts with "stem." and ends in .srt/.vtt
            try (var stream = Files.list(dir)) {
                stream.filter(p -> {
                    String fn = p.getFileName().toString();
                    return fn.startsWith(stem + ".") &&
                           (fn.endsWith(".srt") || fn.endsWith(".vtt")) &&
                           !results.contains(p);
                }).sorted().forEach(results::add);
            } catch (IOException ignored) {}
        }
        return results;
    }

    /**
     * Load subtitles from {@code sp} into {@link #subtitles} (must be called on the FX thread).
     * Also loads a matching {@code .words.json} sidecar (produced by faster-whisper) to enable
     * word-level karaoke highlighting in the CC overlay.
     */
    private void applySubtitleFile(Path sp, Path videoPath) {
        try {
            List<SubtitleEntry> loaded = sp.toString().toLowerCase().endsWith(".vtt")
                    ? parseVtt(sp) : parseSrt(sp);

            // Attempt to merge word-level timestamps from the .words.json sidecar.
            String spName = sp.getFileName().toString();
            String baseName = spName.contains(".")
                    ? spName.substring(0, spName.lastIndexOf('.')) : spName;
            Path wordsJson = sp.resolveSibling(baseName + ".words.json");
            if (Files.exists(wordsJson)) {
                try {
                    loaded = mergeWordTimestamps(loaded, wordsJson);
                } catch (Exception ex) {
                    // Word data is optional — ignore errors and keep plain subtitles.
                }
            }

            if (!videoPath.equals(currentVideoPath)) return;
            subtitles.clear();
            subtitles.addAll(loaded);
            lastCCIndex = -1; // reset karaoke state
            updateCCButtonStyle();
            Duration totalDur = (vlcPlayer != null)
                    ? Duration.millis(vlcPlayer.status().length()) : null;
            String base = formatVideoStatus(videoPath, totalDur);
            onStatusUpdate.accept(base + "  |  CC: " + loaded.size() + " entries from " + sp.getFileName());
        } catch (IOException e) {
            onStatusUpdate.accept("Failed to load subtitles: " + e.getMessage());
        }
    }

    /**
     * Merge per-word timestamps from a {@code .words.json} sidecar into the subtitle list.
     * JSON format (produced by the faster-whisper runner script):
     * {@code [{"s":0.0,"e":1.2,"words":[{"s":0.0,"e":0.5,"w":" Hello"},...]}, ...]}
     * Each subtitle entry is matched by segment index; if word data is present the entry is
     * replaced with a new one that includes the word list.
     */
    @SuppressWarnings("unchecked")
    private static List<SubtitleEntry> mergeWordTimestamps(List<SubtitleEntry> entries, Path wordsJson)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<Map<String, Object>> segs = mapper.readValue(wordsJson.toFile(), List.class);
        List<SubtitleEntry> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry e = entries.get(i);
            if (i < segs.size()) {
                List<Map<String, Object>> wList = (List<Map<String, Object>>) segs.get(i).get("words");
                if (wList != null && !wList.isEmpty()) {
                    List<WordEntry> words = new ArrayList<>(wList.size());
                    for (Map<String, Object> w : wList) {
                        double ws = ((Number) w.get("s")).doubleValue();
                        double we = ((Number) w.get("e")).doubleValue();
                        String ww = (String) w.get("w");
                        words.add(new WordEntry(ws, we, ww));
                    }
                    result.add(new SubtitleEntry(e.start(), e.end(), e.text(), Collections.unmodifiableList(words)));
                    continue;
                }
            }
            result.add(e);
        }
        return result;
    }

    /**
     * Show a subtitle-file picker dialog (FX thread) when multiple files are available,
     * then load the chosen one.
     */
    private void pickAndLoadSubtitle(List<Path> candidates, Path videoPath) {
        ChoiceDialog<String> dlg = new ChoiceDialog<>(
                candidates.get(0).getFileName().toString(),
                candidates.stream().map(p -> p.getFileName().toString())
                                   .collect(Collectors.toList()));
        dlg.setTitle("Select Subtitle File");
        dlg.setHeaderText(null);
        dlg.setContentText("Multiple subtitle files found. Choose one:");
        styledAlert(dlg);
        dlg.showAndWait().ifPresent(chosen -> {
            candidates.stream()
                      .filter(p -> p.getFileName().toString().equals(chosen))
                      .findFirst()
                      .ifPresent(sp -> {
                          applySubtitleFile(sp, videoPath);
                          ccVisible = true;
                          updateCCButtonStyle();
                      });
        });
    }

    // Track the last subtitle index shown to avoid rebuilding TextFlow on every tick.
    private int lastCCIndex = -1;

    private void updateCC(Duration now) {
        if (!ccVisible || subtitles.isEmpty()) {
            if (ccPane.isVisible()) { ccPane.setVisible(false); lastCCIndex = -1; }
            return;
        }
        double nowMs = now.toMillis();
        double nowSec = nowMs / 1000.0;

        // Binary-search for the last entry whose start ≤ now.
        int lo = 0, hi = subtitles.size() - 1, candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (subtitles.get(mid).start().toMillis() <= nowMs) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        if (candidate < 0 || subtitles.get(candidate).end().toMillis() <= nowMs) {
            if (ccPane.isVisible()) { ccPane.setVisible(false); lastCCIndex = -1; }
            return;
        }

        SubtitleEntry entry = subtitles.get(candidate);

        // If same segment as before, just update word highlight colours (fast path).
        if (candidate == lastCCIndex) {
            if (karaokeEnabled) updateWordHighlights(entry.words(), nowSec);
            return;
        }

        // Build TextFlow nodes for this new segment.
        lastCCIndex = candidate;
        ccFlow.getChildren().clear();
        List<WordEntry> words = karaokeEnabled ? entry.words() : List.of();
        if (words.isEmpty()) {
            // No word-level data, or karaoke is off — show plain text.
            Text t = new Text(entry.text());
            t.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
            t.setFill(Color.WHITE);
            ccFlow.getChildren().add(t);
        } else {
            // One Text node per word token, preserving spacing.
            for (int i = 0; i < words.size(); i++) {
                WordEntry w = words.get(i);
                // Whisper word tokens already include leading space in most cases.
                // Add trailing space only if the next word doesn't start with one.
                String token = w.word();
                Text t = new Text(token + (token.endsWith(" ") ? "" : (i < words.size() - 1 ? " " : "")));
                t.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
                t.setFill(wordColor(w, i, words, nowSec));
                ccFlow.getChildren().add(t);
            }
        }
        ccPane.setVisible(true);
    }

    /**
     * Returns the highlight colour for a word at position {@code i}.
     * Sticky handoff: a word stays yellow until the next word starts, so fast
     * speech never leaves a gap with no word highlighted.
     */
    private static Color wordColor(WordEntry w, int i, List<WordEntry> words, double nowSec) {
        double effectiveEnd = (i < words.size() - 1) ? words.get(i + 1).start() : Double.MAX_VALUE;
        return (nowSec >= w.start() && nowSec < effectiveEnd) ? Color.YELLOW : Color.WHITE;
    }

    /** Fast path: only update word fill colours without rebuilding the TextFlow. */
    private void updateWordHighlights(List<WordEntry> words, double nowSec) {
        if (words.isEmpty()) return;
        var nodes = ccFlow.getChildren();
        int count = Math.min(words.size(), nodes.size());
        for (int i = 0; i < count; i++) {
            if (nodes.get(i) instanceof Text t) {
                Color desired = wordColor(words.get(i), i, words, nowSec);
                if (!desired.equals(t.getFill())) t.setFill(desired);
            }
        }
    }

    // ── Subtitle parsers ──────────────────────────────────────────────────────

    private static final Pattern SRT_TIME =
            Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})");

    private static List<SubtitleEntry> parseSrt(Path path) throws IOException {
        return parseTimedText(Files.readString(path, StandardCharsets.UTF_8), SRT_TIME);
    }

    private static final Pattern VTT_TIME =
            Pattern.compile("(\\d{1,2}:\\d{2}[:.\\d]*)\\s*-->\\s*(\\d{1,2}:\\d{2}[:.\\d]*)");

    private static List<SubtitleEntry> parseVtt(Path path) throws IOException {
        return parseTimedText(Files.readString(path, StandardCharsets.UTF_8), VTT_TIME);
    }

    private static List<SubtitleEntry> parseTimedText(String content, Pattern timePattern) {
        List<SubtitleEntry> result = new ArrayList<>();
        String[] blocks = content.replace("\r\n", "\n").replace("\r", "\n").split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.strip().split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = timePattern.matcher(lines[i]);
                if (m.find()) {
                    Duration start = parseTimestamp(m.group(1));
                    Duration end   = parseTimestamp(m.group(2));
                    StringBuilder text = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].strip().replaceAll("<[^>]+>", "");
                        if (!l.isEmpty()) {
                            if (text.length() > 0) text.append('\n');
                            text.append(l);
                        }
                    }
                    if (text.length() > 0)
                        result.add(new SubtitleEntry(start, end, text.toString()));
                    break;
                }
            }
        }
        return result;
    }

    private static Duration parseTimestamp(String ts) {
        ts = ts.trim().replace(',', '.').split("\\s+")[0];
        String[] parts = ts.split(":");
        try {
            if (parts.length == 3) {
                return Duration.seconds(
                        Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]));
            } else if (parts.length == 2) {
                return Duration.seconds(
                        Integer.parseInt(parts[0]) * 60
                        + Double.parseDouble(parts[1]));
            }
        } catch (NumberFormatException ignored) {}
        return Duration.ZERO;
    }

    // ── Seek-to-timestamp dialog ──────────────────────────────────────────────

    private void openSeekDialog() {
        if (vlcPlayer == null) return;
        String cur = formatTime(Duration.millis(vlcPlayer.status().time()));
        TextInputDialog dlg = new TextInputDialog(cur);
        dlg.setTitle("Seek to Timestamp");
        dlg.setHeaderText(null);
        dlg.setContentText("Enter time (mm:ss or hh:mm:ss):");
        styledAlert(dlg);
        dlg.showAndWait().ifPresent(input -> {
            Duration target = parseTimestamp(input.trim());
            long total = vlcPlayer.status().length();
            long ms = Math.max(0, Math.min(total, (long) target.toMillis()));
            vlcPlayer.controls().setTime(ms);
            if (alphaMode) startAlphaFfmpegAt(ms);
            flashControls();
        });
    }

    // ── Whisper CC generation ─────────────────────────────────────────────────

    private void generateCaptions() {
        if (currentVideoPath == null || ccGenerating) return;

        // If CC is already loaded, ask before overwriting
        if (!subtitles.isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "A subtitle file is already loaded.\nRegenerate and overwrite it with Whisper (" + whisperModel + ")?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Regenerate Captions");
            confirm.setHeaderText(null);
            styledAlert(confirm);
            if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        }

        Path videoPath = currentVideoPath;
        Path parent = videoPath.getParent();
        if (parent == null) return;
        // Save generated .srt into the same subtitles/ folder that findSubtitleFile() already searches.
        Path outputDir = parent.resolve("subtitles");
        try { java.nio.file.Files.createDirectories(outputDir); }
        catch (java.io.IOException e) {
            onStatusUpdate.accept("Cannot create subtitles folder: " + e.getMessage());
            return;
        }

        // Capture the token before setting ccGenerating so the check inside the
        // background thread is always for the generation we are about to start.
        final long myToken = ++ccGenToken;
        ccGenerating = true;
        updateCCButtonStyle();
        onStatusUpdate.accept("Generating captions with Whisper (" + whisperModel + ")…");
        showToast("Generating captions with Whisper (" + whisperModel + ")…");

        // Show the persistent progress overlay (stays visible even when controls fade).
        whisperStatusLabel.setText("Generating captions with Whisper (" + whisperModel + ")…");
        whisperProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        whisperOverlay.setVisible(true);

        Thread t = new Thread(() -> {
            try {
                // Progress callback: throttled to ≤ 1 FX-thread post per 200 ms so we
                // don't flood the event queue (tqdm can fire many updates per second).
                final long[] lastProgressTime = {0L};
                IntConsumer onProgress = pct -> {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime[0] >= 200 || pct >= 100) {
                        lastProgressTime[0] = now;
                        Platform.runLater(() -> {
                            if (ccGenToken != myToken) return;
                            whisperProgressBar.setProgress(pct / 100.0);
                            whisperStatusLabel.setText(
                                    String.format("Generating captions… %d%%", pct));
                        });
                    }
                };
                Consumer<String> onStatus = msg -> Platform.runLater(() -> {
                    if (ccGenToken != myToken) return;
                    whisperStatusLabel.setText(msg);
                    onStatusUpdate.accept(msg);
                });

                deleteExistingSubtitles(videoPath);
                String result = runWhisper(videoPath, outputDir, onProgress, onStatus);

                // Keep ccGenerating = true while we load the generated file —
                // the indicator should stay on until the whole pipeline is done.
                Platform.runLater(() -> {
                    if (ccGenToken != myToken) return; // user navigated away
                    whisperStatusLabel.setText("Loading captions…");
                    whisperProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    onStatusUpdate.accept("Loading captions…");
                    showToast("Captions generated — loading…");
                });
                // Load on the same background thread so the indicator stays alive.
                loadSubtitles(videoPath);
                Platform.runLater(() -> {
                    if (ccGenToken != myToken) return; // user navigated away — discard result
                    whisperOverlay.setVisible(false);
                    ccGenerating = false;
                    onStatusUpdate.accept(result);
                    showToast("Captions ready ✓");
                    if (!subtitles.isEmpty()) {
                        ccVisible = true;
                    }
                    updateCCButtonStyle();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (ccGenToken != myToken) return; // user navigated away — discard error
                    whisperOverlay.setVisible(false);
                    ccGenerating = false;
                    updateCCButtonStyle();
                    onStatusUpdate.accept("Whisper error: " + ex.getMessage());
                    showToast("Whisper error — see status bar");
                    Alert err = new Alert(Alert.AlertType.ERROR,
                            "Whisper caption generation failed:\n" + ex.getMessage(),
                            ButtonType.OK);
                    styledAlert(err);
                    err.showAndWait();
                });
            }
        }, "whisper-gen");
        t.setDaemon(true);
        t.start();
    }

    // ── Bulk CC generation (called from Queue Manager) ────────────────────────

    /**
     * Generate captions for a list of video files in sequence on a background thread.
     *
     * @param videoPaths    video files to caption
     * @param skipExisting  skip files that already have an .srt or .vtt alongside them
     * @param onFileStart   called at the start of each file: (fileIndex 0-based, totalFiles, Path)
     * @param onFileProgress  called with whisper progress 0-100 for the current file
     * @param onStatus      called with a short status string at each step
     * @param onDone        called when all files are done (background thread)
     * @param cancelled     set to {@code true} externally to abort after the current file
     * @return the background Thread (daemon) — interrupt it to abort mid-file
     */
    public Thread generateCaptionsBulk(
            List<Path> videoPaths,
            boolean skipExisting,
            BiConsumer<Integer, Integer> onFileStart,
            IntConsumer onFileProgress,
            Consumer<String> onStatus,
            Runnable onDone,
            AtomicBoolean cancelled) {

        Thread t = new Thread(() -> {
            int total = videoPaths.size();
            int done = 0, skipped = 0, failed = 0;
            for (int i = 0; i < total; i++) {
                if (cancelled.get()) {
                    onStatus.accept("Cancelled.");
                    break;
                }
                Path video = videoPaths.get(i);

                // Skip if subtitle already exists
                if (skipExisting) {
                    List<Path> existing = findSubtitleFiles(video);
                    if (!existing.isEmpty()) {
                        onStatus.accept("Skipped (CC exists): " + video.getFileName());
                        onFileStart.accept(i, total);
                        onFileProgress.accept(100);
                        skipped++;
                        continue;
                    }
                }

                onFileStart.accept(i, total);
                onStatus.accept("Starting: " + video.getFileName());

                Path outputDir = video.getParent() != null
                        ? video.getParent().resolve("subtitles") : Path.of(".");
                try { Files.createDirectories(outputDir); }
                catch (IOException ex) { onStatus.accept("Cannot create subtitles folder: " + ex.getMessage()); failed++; continue; }
                // Throttle progress callbacks to ≤1 post per 200ms to avoid flooding FX thread
                final long[] lastProg = {0L};
                IntConsumer throttledProgress = pct -> {
                    long now = System.currentTimeMillis();
                    if (now - lastProg[0] >= 200 || pct >= 100) { lastProg[0] = now; onFileProgress.accept(pct); }
                };
                try {
                    deleteExistingSubtitles(video);
                    runWhisper(video, outputDir, throttledProgress, onStatus);
                    onFileProgress.accept(100);
                    done++;
                    onStatus.accept("Done: " + video.getFileName());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    onStatus.accept("Cancelled.");
                    break;
                } catch (Exception ex) {
                    failed++;
                    onStatus.accept("Failed: " + video.getFileName() + "\n  " + ex.getMessage());
                }
            }
            int fd = done, fs = skipped, ff = failed;
            onStatus.accept(String.format(
                    "Bulk CC complete — %d generated, %d skipped, %d failed", fd, fs, ff));
            onDone.run();
        }, "bulk-cc-gen");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Returns the Whisper model currently selected (e.g. "small", "large-v3"). */
    public String getWhisperModel() { return whisperModel; }

    /** Matches tqdm-style progress lines like "  45%|████…" emitted by Whisper to stderr/stdout. */
    private static final Pattern TQDM_PCT = Pattern.compile("(\\d+)%\\|");

    /** Cached list of Python executables confirmed to have faster-whisper installed. */
    private static volatile List<String> cachedWhisperPythons;
    /** "cuda" if a CUDA-capable GPU was detected during probe, "cpu" otherwise. */
    private static volatile String cachedWhisperDevice;

    /**
     * Delete all existing subtitle files (.srt, .vtt, .words.json) for the given video
     * from both the video's directory and its subtitles/ subdirectory, so a fresh
     * generation always results in exactly one subtitle file.
     */
    private static void deleteExistingSubtitles(Path videoPath) {
        String stem = videoPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        List<Path> dirs = new ArrayList<>();
        if (videoPath.getParent() != null) {
            dirs.add(videoPath.getParent());
            dirs.add(videoPath.getParent().resolve("subtitles"));
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                stream.filter(p -> {
                    String fn = p.getFileName().toString();
                    return (fn.equals(stem + ".srt") || fn.equals(stem + ".vtt") || fn.equals(stem + ".words.json"));
                }).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    /**
     * Run faster-whisper and stream tqdm progress to {@code onProgress}.
     * {@code onStatus} receives short human-readable status strings (called from
     * the background thread — the caller must post to the FX thread if needed).
     */
    private String runWhisper(Path videoPath, Path outputDir,
                              IntConsumer onProgress, Consumer<String> onStatus)
            throws IOException, InterruptedException {

        // ── Resolve Python list + device (cached after first probe) ──────────
        List<String> pythons = cachedWhisperPythons;
        if (pythons == null) {
            onStatus.accept("Detecting Python with faster-whisper…");
            String bundledPy = BundledTools.python();
            List<String> candidates0 = new ArrayList<>();
            candidates0.add(bundledPy);
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null)
                localAppData = System.getProperty("user.home") + "\\AppData\\Local";
            for (String ver : new String[]{"313", "312", "311", "310", "39"}) {
                Path p = Path.of(localAppData, "Programs", "Python", "Python" + ver, "python.exe");
                if (Files.exists(p)) candidates0.add(p.toString());
            }
            candidates0.add("python3");
            candidates0.add("python");

            List<String> found = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String py : candidates0) {
                if (!seen.add(py)) continue;
                try {
                    ProcessBuilder chk = new ProcessBuilder(py, "-c", "import faster_whisper");
                    chk.redirectErrorStream(true);
                    Process cp = chk.start();
                    cp.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    boolean done = cp.waitFor(30, TimeUnit.SECONDS);
                    if (!done) { cp.destroyForcibly(); continue; }
                    if (cp.exitValue() == 0) found.add(py);
                } catch (IOException | InterruptedException ignored) {}
            }
            cachedWhisperPythons = pythons = Collections.unmodifiableList(found);

            // Probe CUDA once — use the first Python that has faster-whisper.
            String device = "cpu";
            if (!found.isEmpty()) {
                onStatus.accept("Checking GPU availability…");
                try {
                    ProcessBuilder cudaChk = new ProcessBuilder(
                            found.get(0), "-c",
                            "import ctranslate2; assert ctranslate2.get_cuda_device_count()>0");
                    cudaChk.redirectErrorStream(true);
                    Process cp = cudaChk.start();
                    cp.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    boolean done = cp.waitFor(30, TimeUnit.SECONDS);
                    if (done && cp.exitValue() == 0) device = "cuda";
                    else if (!done) cp.destroyForcibly();
                } catch (IOException | InterruptedException ignored) {}
            }
            cachedWhisperDevice = device;
            onStatus.accept("Whisper will use: " + device.toUpperCase());
        }
        if (pythons.isEmpty())
            throw new IOException("faster-whisper is not installed in any Python on this machine.\n"
                    + "Install it with:  pip install faster-whisper");

        String device = cachedWhisperDevice;
        if (device == null) device = "cpu";

        // ── Write runner script ───────────────────────────────────────────────
        Path fwScript = writeFasterWhisperScript();
        if (fwScript == null)
            throw new IOException("Could not write faster-whisper runner script.");

        // ── Build candidate list: only the detected device, no silent fallback ─
        List<List<String>> candidates = new ArrayList<>();
        for (String py : pythons) {
            List<String> cmd = new ArrayList<>(List.of(
                    py, fwScript.toString(), videoPath.toString(),
                    "--model", whisperModel, "--device", device,
                    "--output_dir", outputDir.toString()));
            candidates.add(cmd);
        }

        // ── Try each candidate ────────────────────────────────────────────────
        IOException lastErr = null;
        for (List<String> cmd : candidates) {
            String pyName = Path.of(cmd.get(0)).getFileName().toString();
            onStatus.accept("Whisper (" + whisperModel + ") — " + device.toUpperCase() + " via " + pyName + "…");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // Point faster-whisper at the bundled model cache (HF hub layout) when
            // present, so the pre-downloaded model is used instead of fetching it.
            Path bundledModels = BundledTools.whisperModelDir();
            if (bundledModels != null)
                pb.environment().put("HF_HUB_CACHE", bundledModels.toAbsolutePath().toString());
            try {
                Process proc = pb.start();
                StringBuilder log = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("[fw-info]")) {
                            // Device/model confirmation printed by the script at startup
                            onStatus.accept("Whisper — " + line.substring(9).trim());
                        } else if (!line.isEmpty()) {
                            log.append(line).append('\n');
                            Matcher m = TQDM_PCT.matcher(line);
                            if (m.find()) {
                                int pct = Integer.parseInt(m.group(1));
                                onProgress.accept(pct);
                            }
                        }
                    }
                }
                int exit = proc.waitFor();
                String stem2 = videoPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                boolean srtExists = java.nio.file.Files.exists(outputDir.resolve(stem2 + ".srt"));
                if (exit == 0 || srtExists) {
                    return "Captions generated successfully ✓ (" + device.toUpperCase() + ")";
                }
                throw new IOException("Whisper exited with code " + exit + "\n" + log);
            } catch (IOException e) {
                lastErr = e;
            }
        }
        throw new IOException("faster-whisper not found or failed. Install with:\n  pip install faster-whisper\n\n"
                + (lastErr != null ? lastErr.getMessage() : ""));
    }

    /**
     * Writes the faster-whisper runner script to a temp file (once) and returns its path.
     * Returns {@code null} if writing fails.  The temp file is deleted on JVM exit.
     */
    private static volatile Path fasterWhisperScript;
    /** Call to force re-write of the runner script (e.g. after an update). */
    static void invalidateFasterWhisperScript() { fasterWhisperScript = null; cachedWhisperPythons = null; cachedWhisperDevice = null; }
    private static Path writeFasterWhisperScript() {
        if (fasterWhisperScript != null) return fasterWhisperScript;
        try {
            Path script = Files.createTempFile("fw_run_", ".py");
            script.toFile().deleteOnExit();
            String nl = "\n";
            String code =
                "import os, sys, argparse, json" + nl +
                "" + nl +
                "def fmt(t):" + nl +
                "    h,rem=divmod(t,3600); m,s=divmod(rem,60); ms=(t-int(t))*1000" + nl +
                "    return f\"{int(h):02d}:{int(m):02d}:{int(s):02d},{int(ms):03d}\"" + nl +
                "" + nl +
                "def main():" + nl +
                "    p=argparse.ArgumentParser()" + nl +
                "    p.add_argument('audio')" + nl +
                "    p.add_argument('--model',default='small')" + nl +
                "    p.add_argument('--device',default='cuda')" + nl +
                "    p.add_argument('--output_dir',default='.')" + nl +
                "    a=p.parse_args()" + nl +
                "    if a.device=='cuda':" + nl +
                "        import ctranslate2" + nl +
                "        n=ctranslate2.get_cuda_device_count()" + nl +
                "        if n==0:" + nl +
                "            print('No CUDA devices found',file=sys.stderr,flush=True); sys.exit(2)" + nl +
                "    from faster_whisper import WhisperModel" + nl +
                "    ct='float16' if a.device=='cuda' else 'int8'" + nl +
                "    model=WhisperModel(a.model,device=a.device,compute_type=ct)" + nl +
                "    print(f'[fw-info] device={a.device.upper()} model={a.model} compute={ct}',flush=True)" + nl +
                "    segs,info=model.transcribe(a.audio,beam_size=5,word_timestamps=True," + nl +
                "        no_speech_threshold=0.6," + nl +
                "        condition_on_previous_text=False," + nl +
                "        log_prob_threshold=-1.0)" + nl +
                "    total=info.duration or 1.0" + nl +
                "    base=os.path.splitext(os.path.basename(a.audio))[0]" + nl +
                "    out_srt=os.path.join(a.output_dir,base+'.srt')" + nl +
                "    out_words=os.path.join(a.output_dir,base+'.words.json')" + nl +
                "    prev=-1" + nl +
                "    all_segs=[]" + nl +
                "    with open(out_srt,'w',encoding='utf-8') as f:" + nl +
                "        for i,seg in enumerate(segs,1):" + nl +
                "            pct=min(int(seg.end/total*100),99)" + nl +
                "            if pct!=prev: print(f'{pct}%|',flush=True); prev=pct" + nl +
                "            if seg.no_speech_prob>0.7 and seg.avg_logprob<-1.5:" + nl +
                "                text='♪'" + nl +
                "                words_out=[]" + nl +
                "            else:" + nl +
                "                text=seg.text.strip()" + nl +
                "                words_out=[]" + nl +
                "                if seg.words:" + nl +
                "                    for w in seg.words:" + nl +
                "                        words_out.append({'s':round(w.start,3),'e':round(w.end,3),'w':w.word})" + nl +
                "            f.write(f'{i}\\n{fmt(seg.start)} --> {fmt(seg.end)}\\n{text}\\n\\n')" + nl +
                "            all_segs.append({'s':seg.start,'e':seg.end,'words':words_out})" + nl +
                "    with open(out_words,'w',encoding='utf-8') as jf:" + nl +
                "        json.dump(all_segs,jf)" + nl +
                "    print('100%|',flush=True)" + nl +
                "" + nl +
                "main()" + nl;
            Files.writeString(script, code, StandardCharsets.UTF_8);
            fasterWhisperScript = script;
            return script;
        } catch (IOException e) {
            return null;
        }
    }

    // ── Dialog theme helpers ──────────────────────────────────────────────────

    /**
     * Creates a themed UNDECORATED title bar matching the active theme.
     * Uses CSS classes .title-bar / .wm-btn / .wm-close defined in every theme stylesheet.
     * The bar is draggable and contains a close button.
     */
    private HBox makeDialogTitleBar(String title, Stage dlg) {
        Label lbl = new Label(title);
        lbl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lbl, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("wm-btn", "wm-close");
        closeBtn.setOnAction(e -> dlg.close());

        HBox bar = new HBox(6);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 0, 5, 10));

        // Add app icon if available
        Scene s = getScene();
        if (s != null && s.getWindow() instanceof Stage owner && !owner.getIcons().isEmpty()) {
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(owner.getIcons().get(0));
            iv.setFitWidth(16); iv.setFitHeight(16); iv.setPreserveRatio(true);
            bar.getChildren().add(iv);
        }
        bar.getChildren().addAll(lbl, closeBtn);

        double[] drag = {0, 0};
        bar.setOnMousePressed(e -> { drag[0] = e.getScreenX(); drag[1] = e.getScreenY(); });
        bar.setOnMouseDragged(e -> {
            dlg.setX(dlg.getX() + e.getScreenX() - drag[0]);
            dlg.setY(dlg.getY() + e.getScreenY() - drag[1]);
            drag[0] = e.getScreenX(); drag[1] = e.getScreenY();
        });
        return bar;
    }

    /** Centre an UNDECORATED dialog over its owner after it is shown. */
    private static void centerOnOwner(Stage dlg) {
        Window owner = dlg.getOwner();
        if (owner != null) {
            dlg.setX(owner.getX() + (owner.getWidth()  - dlg.getWidth())  / 2);
            dlg.setY(owner.getY() + (owner.getHeight() - dlg.getHeight()) / 2);
        }
    }

    /**
     * Apply the main scene's CSS stylesheets and window icon to a dialog {@link Stage}.
     * Call this before {@code dlg.setScene(...)}.
     */
    private void styledScene(Stage dlg, Scene dialogScene) {
        Scene s = getScene();
        if (s == null) return;
        dialogScene.getStylesheets().addAll(s.getStylesheets());
        if (s.getWindow() instanceof Stage owner)
            dlg.getIcons().addAll(owner.getIcons());
    }

    /**
     * Set the owner and apply CSS stylesheets + app icon to a {@link Dialog}
     * (Alert, TextInputDialog, ChoiceDialog, …).
     * Uses the OS title bar (DECORATED) so that modal showAndWait() is reliable
     * on all Windows configurations. dialog_base.css themes the inner pane areas.
     */
    private void styledAlert(Dialog<?> dlg) {
        Scene s = getScene();
        if (s == null) return;
        dlg.initOwner(s.getWindow());
        DialogPane dp = dlg.getDialogPane();
        dp.getStylesheets().addAll(s.getStylesheets());
        var url = MediaPane.class.getResource("/com/imageviewer/css/dialog_base.css");
        if (url != null) dp.getStylesheets().add(url.toExternalForm());
        if (s.getWindow() instanceof Stage owner) {
            dlg.setOnShown(e -> {
                if (dp.getScene().getWindow() instanceof Stage ds) {
                    if (!owner.getIcons().isEmpty()) ds.getIcons().setAll(owner.getIcons());
                    centerOnOwner(ds);
                }
            });
        }
    }

    // ── Image helpers ─────────────────────────────────────────────────────────

    /** Apply image immediately (no transition) — handles async loading. */
    private void applyImageWhenReady(Image img, Path path) {
        if (img.getProgress() >= 1.0) {
            if (!img.isError() && img.getWidth() > 0) {
                imageView.setImage(img);
                Platform.runLater(this::zoomFit);
                onStatusUpdate.accept(formatImageStatus(path, img));
            } else {
                fallbackLoadImageIO(path);
            }
        } else {
            img.progressProperty().addListener((obs, o, n) -> {
                if (n.doubleValue() >= 1.0) {
                    if (!img.isError() && img.getWidth() > 0) {
                        Platform.runLater(() -> {
                            imageView.setImage(img);
                            zoomFit();
                            onStatusUpdate.accept(formatImageStatus(path, img));
                        });
                    } else {
                        Platform.runLater(() -> fallbackLoadImageIO(path));
                    }
                }
            });
        }
    }

    /** Apply image with fade-in — called after the fade-out completes. */
    private void applyImageWithFadeIn(Image img, Path path) {
        Runnable doFadeIn = () -> {
            imageView.setImage(img);
            Platform.runLater(() -> {
                zoomFit();
                FadeTransition in = new FadeTransition(Duration.millis(180), imgScroll);
                in.setFromValue(0.0);
                in.setToValue(1.0);
                in.play();
            });
            onStatusUpdate.accept(formatImageStatus(path, img));
        };

        if (img.getProgress() >= 1.0) {
            if (!img.isError() && img.getWidth() > 0) doFadeIn.run();
            else fallbackLoadImageIO(path);
        } else {
            img.progressProperty().addListener((obs, o, n) -> {
                if (n.doubleValue() >= 1.0) {
                    if (!img.isError() && img.getWidth() > 0) Platform.runLater(doFadeIn);
                    else Platform.runLater(() -> fallbackLoadImageIO(path));
                }
            });
        }
    }

    // ── ImageIO fallback (CMYK JPEG, TIFF, ICO, WebP static, etc.) ──────────

    /**
     * Returns {@code true} if the file is a JPEG encoded in CMYK colour space.
     * JavaFX loads CMYK JPEGs without setting {@code isError()=true} but renders
     * them as a blank/black image, so we detect them upfront and skip to the
     * ImageIO fallback which handles CMYK correctly.
     *
     * <p>Detection strategy: scan for a JPEG SOF (Start Of Frame) marker that
     * reports 4 colour components, which is the signature of CMYK/YCCK.</p>
     */
    private static boolean isCmykJpeg(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            // Check JPEG magic bytes
            if (is.read() != 0xFF || is.read() != 0xD8) return false;
            byte[] buf = new byte[4];
            while (true) {
                int b = is.read();
                if (b == -1) break;
                if ((b & 0xFF) != 0xFF) continue;
                int marker = is.read() & 0xFF;
                if (marker == 0xD9 || marker == 0xDA) break; // EOI or SOS — stop
                // SOF markers: C0..C3, C5..C7, C9..CB, CD..CF (baseline and progressive)
                if ((marker >= 0xC0 && marker <= 0xC3) ||
                    (marker >= 0xC5 && marker <= 0xC7) ||
                    (marker >= 0xC9 && marker <= 0xCB) ||
                    (marker >= 0xCD && marker <= 0xCF)) {
                    // SOF segment: length(2) + precision(1) + height(2) + width(2) + components(1)
                    // Skip the first 7 bytes, then read components byte
                    is.skipNBytes(7);
                    int components = is.read() & 0xFF;
                    return components == 4; // CMYK or YCCK
                } else {
                    // Skip this segment
                    if (is.read(buf, 0, 2) < 2) break;
                    int segLen = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
                    if (segLen < 2) break;
                    is.skipNBytes(segLen - 2);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Decode an image via Java ImageIO + AWT.  Handles CMYK JPEG, TIFF (Java 9+),
     * and any other format that JavaFX's built-in decoder refuses.
     * Falls back to ImageMagick when ImageIO also fails (e.g. ICO).
     * Runs on a background thread; must NOT be called on the FX thread.
     */
    private void fallbackLoadImageIO(Path path) {
        if (!path.equals(currentImagePath)) return;
        Thread t = new Thread(() -> {
            Image result = null;
            try {
                result = loadViaImageIO(path);
            } catch (Throwable ignored) {}
            if (result == null) {
                try {
                    result = loadViaFfmpeg(path); // handles CMYK, WebP, large files, all formats
                } catch (Throwable ignored) {}
            }
            if (result == null) {
                try {
                    result = loadRawViaImageMagick(path);
                } catch (Throwable ignored) {}
            }
            final Image img = result;
            Platform.runLater(() -> {
                if (!path.equals(currentImagePath)) return;
                if (img != null) {
                    // Restore visibility in case a fade-out transition left imgScroll at opacity 0
                    imgScroll.setOpacity(1.0);
                    imgScroll.setVisible(true);
                    imageView.setImage(img);
                    zoomFit();
                    onStatusUpdate.accept(formatImageStatus(path, img));
                } else {
                    onStatusUpdate.accept(path.getFileName() + "  (cannot display)");
                }
            });
        }, "imageio-fallback");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronous image load via {@code javax.imageio.ImageIO}.
     * Converts the decoded {@link java.awt.image.BufferedImage} to a JavaFX
     * {@link WritableImage} using ARGB pixel transfer — works on any platform,
     * no extra native library required.
     */
    private static Image loadViaImageIO(Path path) {
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(path.toFile());
            if (bi == null) return null;
            int w = bi.getWidth(), h = bi.getHeight();
            WritableImage fx = new WritableImage(w, h);
            javafx.scene.image.PixelWriter pw = fx.getPixelWriter();
            // Row-by-row transfer avoids allocating a single w*h int[] which can OOM
            // on large images.  getRGB() also handles CMYK→sRGB colour conversion.
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                bi.getRGB(0, y, w, 1, row, 0, w);
                pw.setPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), row, 0, w);
            }
            return fx;
        } catch (Throwable e) {
            System.err.println("[imageio] " + path.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Decode an image by piping it through ffmpeg → PNG stdout.
     * Handles formats Java ImageIO cannot (WebP, HEIC, etc.) when ffmpeg is available.
     */
    private static Image loadViaFfmpeg(Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                BundledTools.ffmpeg(), "-y", "-i", path.toAbsolutePath().toString(),
                "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            byte[] pngBytes = p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
            if (pngBytes.length == 0) return null;
            return new Image(new java.io.ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            System.err.println("[ffmpeg-img] " + path.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyZoom(double newScale) {
        Image img = imageView.getImage();
        if (img == null) return;
        zoomScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, newScale));

        imageView.setFitWidth(img.getWidth()  * zoomScale);
        imageView.setFitHeight(img.getHeight() * zoomScale);

        int pct = (int) Math.round(zoomScale * 100);
        String name = currentImagePath != null ? currentImagePath.getFileName() + "  |  " : "";
        onStatusUpdate.accept(String.format("%s%.0f × %.0f  |  Zoom: %d%%",
                name, img.getWidth(), img.getHeight(), pct));
    }

    /**
     * Zoom so that the image point currently under (pivotViewX, pivotViewY) — measured in
     * the viewport's local coordinate space — stays fixed after the scale change.
     */
    private void applyZoomAt(double newScale, double pivotViewX, double pivotViewY) {
        Image img = imageView.getImage();
        if (img == null) return;

        double viewportW = imgScroll.getViewportBounds().getWidth();
        double viewportH = imgScroll.getViewportBounds().getHeight();
        double containerW = imgContainer.getWidth();
        double containerH = imgContainer.getHeight();
        double scrollW = containerW - viewportW;
        double scrollH = containerH - viewportH;

        // Viewport top-left in container coordinates (before zoom)
        double vpLeft = (scrollW > 0) ? imgScroll.getHvalue() * scrollW : 0;
        double vpTop  = (scrollH > 0) ? imgScroll.getVvalue() * scrollH : 0;

        // Pivot position in container coords
        double pivContX = vpLeft + pivotViewX;
        double pivContY = vpTop  + pivotViewY;

        // Image top-left in container (StackPane centers it)
        double imgLeft = (containerW - imageView.getFitWidth())  / 2.0;
        double imgTop  = (containerH - imageView.getFitHeight()) / 2.0;

        // Pivot in natural (un-scaled) image coordinates — invariant across zoom levels
        double oldScale  = zoomScale;
        double pivNatX = (pivContX - imgLeft) / oldScale;
        double pivNatY = (pivContY - imgTop)  / oldScale;

        // Apply the new scale (updates zoomScale, fitWidth, fitHeight)
        applyZoom(newScale);

        // Recompute container size using the same formula as the layout binding
        double imageW_new = imageView.getFitWidth();
        double imageH_new = imageView.getFitHeight();
        double contW_new  = Math.max(imageW_new, viewportW) + viewportW;
        double contH_new  = Math.max(imageH_new, viewportH) + viewportH;
        double scrollW_new = contW_new - viewportW;
        double scrollH_new = contH_new - viewportH;

        // Pivot position in new container coords
        double imgLeft_new  = (contW_new - imageW_new) / 2.0;
        double imgTop_new   = (contH_new - imageH_new) / 2.0;
        double pivContX_new = imgLeft_new + pivNatX * zoomScale;
        double pivContY_new = imgTop_new  + pivNatY * zoomScale;

        // Place viewport so the pivot stays under the same view position
        double vpLeft_new = pivContX_new - pivotViewX;
        double vpTop_new  = pivContY_new - pivotViewY;

        if (scrollW_new > 0) imgScroll.setHvalue(clamp(vpLeft_new / scrollW_new, 0, 1));
        if (scrollH_new > 0) imgScroll.setVvalue(clamp(vpTop_new  / scrollH_new, 0, 1));
    }

    /** Zoom toward the center of the viewport (used when the user has not panned). */
    private void applyZoomCenter(double newScale) {
        double vw = imgScroll.getViewportBounds().getWidth();
        double vh = imgScroll.getViewportBounds().getHeight();
        applyZoomAt(newScale, vw / 2.0, vh / 2.0);
    }

    private void wireImageInteractions() {
        // Use addEventFilter (capture phase) so we intercept scroll events before the
        // ScrollPane skin can use them to scroll the content.  Scroll wheel = zoom only.
        imgScroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (imageView.getImage() == null) return;
            double newScale = zoomScale * (e.getDeltaY() > 0 ? ZOOM_FACTOR : 1.0 / ZOOM_FACTOR);
            if (hasPanned) applyZoomAt(newScale, e.getX(), e.getY());
            else           applyZoomCenter(newScale);
            e.consume();
        });

        imgScroll.setOnMouseEntered(e -> {
            if (imageView.getImage() != null) imgScroll.setCursor(Cursor.OPEN_HAND);
        });
        imgScroll.setOnMouseExited(e -> imgScroll.setCursor(Cursor.DEFAULT));

        // Handlers on imgContainer (direct image wrapper) instead of imgScroll so that
        // the ScrollPane skin cannot intercept and swallow the drag events.
        imgContainer.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && imageView.getImage() != null) {
                dragStartX = e.getSceneX();
                dragStartY = e.getSceneY();
                dragStartH = imgScroll.getHvalue();
                dragStartV = imgScroll.getVvalue();
                imgScroll.setCursor(Cursor.CLOSED_HAND);
                e.consume();
            }
        });

        imgContainer.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && imageView.getImage() != null) {
                double dx = e.getSceneX() - dragStartX;
                double dy = e.getSceneY() - dragStartY;

                double scrollW = imgContainer.getWidth()  - imgScroll.getViewportBounds().getWidth();
                double scrollH = imgContainer.getHeight() - imgScroll.getViewportBounds().getHeight();

                if (scrollW > 0)
                    imgScroll.setHvalue(clamp(dragStartH - dx / scrollW, 0, 1));
                if (scrollH > 0)
                    imgScroll.setVvalue(clamp(dragStartV - dy / scrollH, 0, 1));

                hasPanned = true;
                e.consume();
            }
        });

        imgContainer.setOnMouseReleased(e -> {
            if (imageView.getImage() != null) imgScroll.setCursor(Cursor.OPEN_HAND);
        });
        imgContainer.setOnContextMenuRequested(e -> {
            showImageContextMenu(e.getScreenX(), e.getScreenY());
            e.consume();
        });

        widthProperty().addListener((obs, o, n)  -> scheduleZoomFit());
        heightProperty().addListener((obs, o, n) -> scheduleZoomFit());
    }

    /** Debounced zoomFit — defers until layout has settled (50 ms after last resize event). */
    private void scheduleZoomFit() {
        if (!imgScroll.isVisible()) return;
        if (zoomFitDebounce == null)
            zoomFitDebounce = new Timeline(new KeyFrame(Duration.millis(50), e -> zoomFit()));
        zoomFitDebounce.playFromStart();
    }

    // ── Loop button style ─────────────────────────────────────────────────────

    private void updateLoopButtonStyle(boolean on) {
        loopBtn.setStyle(on ? "-fx-text-fill: #66d966;" : "");
    }

    private void showVideoContextMenu(double sx, double sy) {
        if (videoContextMenu != null) videoContextMenu.hide();
        videoContextMenu = new ContextMenu();
        MenuItem captureItem = new MenuItem("📷  Save Frame…");
        captureItem.setDisable(videoImage == null);
        captureItem.setOnAction(e -> onFrameCapture.run());
        SeparatorMenuItem sep = new SeparatorMenuItem();
        MenuItem playItem = new MenuItem(isVideoPlaying() ? "⏸  Pause" : "▶  Play");
        playItem.setOnAction(ev -> togglePlayPause());
        videoContextMenu.getItems().addAll(captureItem, sep, playItem);
        videoContextMenu.show(videoPane, sx, sy);
    }

    private void showImageContextMenu(double sx, double sy) {
        if (imageView.getImage() == null) return;
        if (imageContextMenu != null) imageContextMenu.hide();
        imageContextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("📋  Copy to Clipboard");
        copyItem.setOnAction(e -> onCopyImageToClipboard.run());
        imageContextMenu.getItems().add(copyItem);
        imageContextMenu.show(imgContainer, sx, sy);
    }

    // ── Playback speed ────────────────────────────────────────────────────────

    private void showSpeedMenu() {
        float[] rates = SPEED_RATES;
        ContextMenu cm = new ContextMenu();
        for (float r : rates) {
            String label = (r == (int) r)
                    ? (int) r + "×"
                    : r + "×";
            MenuItem item = new MenuItem(label);
            if (r == playbackRate) item.setStyle("-fx-font-weight: bold;");
            item.setOnAction(e -> setPlaybackRate(r));
            cm.getItems().add(item);
        }
        cm.show(speedBtn, javafx.geometry.Side.TOP, 0, 0);
    }

    private void setPlaybackRate(float rate) {
        playbackRate = rate;
        String label = (rate == (int) rate)
                ? (int) rate + "×"
                : rate + "×";
        speedBtn.setText(label);
        if (usingFxMedia && fxMediaPlayer != null) fxMediaPlayer.setRate(rate);
        else if (vlcPlayer != null) vlcPlayer.controls().setRate(rate);
    }

    /** Step playback speed to the next higher preset (. key). No-op if already at max. */
    public void stepSpeedUp() {
        if (!videoPane.isVisible()) return;
        for (int i = 0; i < SPEED_RATES.length - 1; i++) {
            if (playbackRate < SPEED_RATES[i + 1] - 0.01f) { setPlaybackRate(SPEED_RATES[i + 1]); return; }
        }
    }

    /** Step playback speed to the next lower preset (, key). No-op if already at min. */
    public void stepSpeedDown() {
        if (!videoPane.isVisible()) return;
        for (int i = SPEED_RATES.length - 1; i > 0; i--) {
            if (playbackRate > SPEED_RATES[i - 1] + 0.01f) { setPlaybackRate(SPEED_RATES[i - 1]); return; }
        }
    }

    private void wireVideoInteractions() {
        seekSlider.setOnMousePressed(e -> {
            seekInProgress = true;
            if (usingFxMedia) {
                wasPlayingBeforeSeek = fxMediaPlayer != null &&
                        fxMediaPlayer.getStatus() == javafx.scene.media.MediaPlayer.Status.PLAYING;
                if (fxMediaPlayer != null) fxMediaPlayer.pause();
            } else if (vlcPlayer != null) {
                wasPlayingBeforeSeek = vlcPlayer.status().isPlaying();
                vlcPlayer.controls().pause();
            }
        });
        seekSlider.setOnMouseReleased(e -> {
            if (usingFxMedia) {
                if (fxMediaPlayer != null) {
                    javafx.util.Duration total = fxMediaPlayer.getTotalDuration();
                    if (total != null && total.greaterThan(javafx.util.Duration.ZERO)) {
                        fxMediaPlayer.seek(total.multiply(seekSlider.getValue()));
                    }
                    if (wasPlayingBeforeSeek) fxMediaPlayer.play();
                }
            } else if (vlcPlayer != null) {
                long total = vlcPlayer.status().length();
                if (total > 0) {
                    long targetMs = (long)(total * seekSlider.getValue());
                    vlcPlayer.controls().setTime(targetMs);
                    if (alphaMode) startAlphaFfmpegAt(targetMs);
                    if (wasPlayingBeforeSeek) {
                        vlcPlayer.controls().play();
                    } else {
                        // Video stays paused — timeChanged won't fire, so update CC manually
                        // so the subtitle display reflects the new seek position.
                        updateCC(Duration.millis(targetMs));
                    }
                } else if (wasPlayingBeforeSeek) {
                    vlcPlayer.controls().play();
                }
            }
            seekInProgress = false;
            flashControls();
        });

        volumeSlider.valueProperty().addListener((obs, o, n) -> {
            if (muted) return;
            if (usingFxMedia && fxMediaPlayer != null) fxMediaPlayer.setVolume(n.doubleValue());
            else if (vlcPlayer != null) vlcPlayer.audio().setVolume((int)(n.doubleValue() * 100));
        });

        // ── Seek-bar preview on hover ─────────────────────────────────────────
        seekSlider.setOnMouseMoved(this::updateSeekPreview);
        seekSlider.setOnMouseExited(e -> {
            seekPrevPopup.setVisible(false);
            seekPrevGen.incrementAndGet();   // invalidate any in-flight FFmpeg task
        });

        videoPane.setOnMouseMoved(e   -> flashControls());
        videoPane.setOnMouseEntered(e -> showControls());
        videoPane.setOnContextMenuRequested(e -> {
            showVideoContextMenu(e.getScreenX(), e.getScreenY());
            e.consume();
        });
        // Prevent right-clicks on the control bar from bubbling up to videoPane
        // and triggering a second context menu.
        controlBar.setOnContextMenuRequested(javafx.event.Event::consume);

        videoView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1)
                togglePlayPause();
        });
        fxMediaView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1)
                togglePlayPause();
        });
    }

    private void wireLayoutBindings() {
        // The container is always one extra viewport-width/height wider/taller than the image
        // (half on each side).  This ensures scrollW and scrollH are always positive so that
        // the user can drag-to-pan in any direction at any zoom level, even when the image
        // is smaller than the viewport.
        imgContainer.minWidthProperty().bind(Bindings.createDoubleBinding(() -> {
                double vw = imgScroll.getViewportBounds().getWidth();
                return Math.max(imageView.getFitWidth(), vw) + vw;
        }, imageView.fitWidthProperty(), imgScroll.viewportBoundsProperty()));
        imgContainer.minHeightProperty().bind(Bindings.createDoubleBinding(() -> {
                double vh = imgScroll.getViewportBounds().getHeight();
                return Math.max(imageView.getFitHeight(), vh) + vh;
        }, imageView.fitHeightProperty(), imgScroll.viewportBoundsProperty()));

        videoView.fitWidthProperty().bind(videoPane.widthProperty());
        videoView.fitHeightProperty().bind(videoPane.heightProperty().subtract(46));

        // Allow ScrollPane to grow to fill the MediaPane (StackPane).
        // ScrollPane is a Control whose default max = pref, so without this it won't
        // expand beyond its content's preferred size during window resize.
        imgScroll.setMaxWidth(Double.MAX_VALUE);
        imgScroll.setMaxHeight(Double.MAX_VALUE);

        // Prevent internal content sizes from propagating outward as layout constraints.
        // When compare mode is entered, the compareSplitPane measures each wrapped MediaPane's
        // minWidth to determine how far the divider can travel.  Without these overrides:
        //
        //  • imgScroll: imgContainer.minWidth is bound to max(imageView.fitWidth, viewportW),
        //    so a large displayed image would leak out through the ScrollPane and pin the divider.
        //
        //  • videoPane: videoView.fitWidthProperty() is bound to videoPane.widthProperty(), so
        //    videoView.fitWidth == videoPane.width (e.g. 1726 px).  ImageView.minWidth(-1)
        //    returns its fitWidth, so videoPane.minWidth(-1) == 1726 px, which propagates up
        //    through MediaPane (StackPane) and forces the SplitPane divider to ~0.78+.
        //
        // Setting minWidth/Height = 0 on both containers lets the SplitPane place the divider
        // freely at 50/50.  The internal content still has its large minWidth so pan/scroll and
        // video rendering continue to work correctly inside the pane.
        imgScroll.setMinWidth(0);
        imgScroll.setMinHeight(0);
        videoPane.setMinWidth(0);
        videoPane.setMinHeight(0);
    }

    private void showControls() {
        controlBar.setVisible(true);
        controlBar.setOpacity(1.0);
    }

    private void flashControls() {
        showControls();
        startControlFadeTimer();
    }

    private void startControlFadeTimer() {
        if (controlFadeTimer != null) controlFadeTimer.stop();
        controlFadeTimer = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
            FadeTransition ft = new FadeTransition(Duration.millis(400), controlBar);
            ft.setToValue(0.0);
            ft.setOnFinished(ev -> controlBar.setVisible(false));
            ft.play();
        }));
        controlFadeTimer.play();
    }

    // ── VLCJ factory & player creation ────────────────────────────────────────

    /**
     * Initialises the shared {@link MediaPlayerFactory} on the first call.
     * Subsequent calls return immediately.
     *
     * <p>Discovery order:
     * <ol>
     *   <li>Bundled {@code vlc/} directory next to the running JAR
     *       (populated by the build scripts when packaging the app).</li>
     *   <li>Standard VLCJ {@link NativeDiscovery} (registry, PATH, env vars).</li>
     *   <li>Manual scan of well-known Windows install paths — catches cases where
     *       VLC is installed but not on PATH (common in jpackage environments).</li>
     * </ol>
     *
     * @return {@code true} if VLC is available; {@code false} otherwise.
     */
    private static synchronized boolean ensureVlcFactory() {
        if (vlcChecked) return vlcAvailable;
        vlcChecked = true;

        // NOTE: NativeDiscovery is intentionally NOT used here.
        // In a jpackage app-image, calling NativeDiscovery.discover() before
        // VLC_PLUGIN_PATH is set causes libvlc to scan for plugins in a missing
        // directory, crashing the JVM via native code before any Java exception
        // can be caught.  We set --plugin-path explicitly for each candidate.
        List<Path> vlcDirs = new ArrayList<>();

        // 1. Bundled VLC (highest priority)
        Path bundled = findBundledVlc();
        if (bundled != null) vlcDirs.add(bundled);

        // 2. Hard-coded Windows install paths (fallback / dev machines)
        String pf   = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        for (String dir : new String[]{
                pf   != null ? pf   + "\\VideoLAN\\VLC" : null,
                pf   != null ? pf   + "\\VLC"           : null,
                pf86 != null ? pf86 + "\\VideoLAN\\VLC" : null,
                pf86 != null ? pf86 + "\\VLC"           : null,
                "C:\\Program Files\\VideoLAN\\VLC",
                "C:\\Program Files\\VLC",
                "C:\\Program Files (x86)\\VideoLAN\\VLC",
                "C:\\Program Files (x86)\\VLC",
        }) {
            if (dir != null && Files.exists(Path.of(dir, "libvlc.dll")))
                vlcDirs.add(Path.of(dir));
        }

        for (Path dir : vlcDirs) {
            try {
                applyVlcNativePath(dir);
                String pluginPath = dir.resolve("plugins").toAbsolutePath().toString();
                List<String> args = new ArrayList<>(List.of(
                        "--no-video-title-show",
                        "--quiet",
                        "--no-sub-autodetect-file",
                        "--no-spu",
                        "--aout=directsound",
                        "--no-audio-time-stretch",
                        "--plugin-path=" + pluginPath));
                vlcFactory = new MediaPlayerFactory(args.toArray(new String[0]));
                vlcAvailable = true;
                return true;
            } catch (Throwable t) {
                System.err.println("[vlc] factory failed for " + dir + ": " + t.getMessage());
            }
        }

        vlcAvailable = false;
        return false;
    }

    /**
     * Looks for a {@code vlc/} directory bundled alongside the running JAR.
     * In the packaged app the JAR lives in {@code <install>/app/}; the build
     * scripts copy VLC into {@code <install>/app/vlc/}.
     *
     * @return the path to the bundled {@code vlc/} directory, or {@code null}.
     */
    private static Path findBundledVlc() {
        // Collect candidate "app" directories from multiple sources, since
        // getCodeSource().getLocation() can return null inside a jpackage app-image.
        List<Path> candidates = new ArrayList<>();

        // 1. Code source location (works in dev / fat-JAR runs)
        try {
            java.net.URL loc = MediaPane.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Path.of(loc.toURI());
                candidates.add(Files.isRegularFile(p) ? p.getParent() : p);
            }
        } catch (Exception ignored) {}

        // 2. java.home → jpackage runtime is at <appRoot>/runtime, so app dir is sibling
        try {
            Path javaHome = Path.of(System.getProperty("java.home", ""));
            // jpackage: <appRoot>/runtime/  →  sibling "app/" is where the JAR lives
            Path appDir = javaHome.getParent().resolve("app");
            candidates.add(appDir);
            // Also try the parent itself (non-jpackage layouts)
            candidates.add(javaHome.getParent());
        } catch (Exception ignored) {}

        // 3. Executable location via ProcessHandle (jpackage .exe is one level above app/)
        try {
            ProcessHandle.current().info().command().ifPresent(exe -> {
                Path exeDir = Path.of(exe).getParent();
                if (exeDir != null) {
                    candidates.add(exeDir.resolve("app"));
                    candidates.add(exeDir);
                }
            });
        } catch (Exception ignored) {}

        for (Path appDir : candidates) {
            try {
                Path vlcDir = appDir.resolve("vlc");
                if (Files.exists(vlcDir.resolve("libvlc.dll"))) return vlcDir;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Registers {@code vlcDir} with JNA's runtime search path AND sets the
     * system properties, so the DLL can be found both by the property-based
     * discovery and by JNA's already-initialised NativeLibrary cache.
     */
    private static void applyVlcNativePath(Path vlcDir) {
        String dir = vlcDir.toAbsolutePath().toString();

        // System property — read by VLCJ's NativeDiscovery and JNA at startup
        String existing = System.getProperty("jna.library.path", "");
        System.setProperty("jna.library.path",
                existing.isEmpty() ? dir : dir + ";" + existing);

        // JNA runtime API via reflection — works even after JNA has already been
        // initialised and read the system property (module boundary: com.sun.jna
        // is not declared in module-info, so we call it reflectively).
        try {
            Class<?> nlClass = Class.forName("com.sun.jna.NativeLibrary");
            java.lang.reflect.Method addPath =
                    nlClass.getMethod("addSearchPath", String.class, String.class);
            addPath.invoke(null, "libvlc",     dir);
            addPath.invoke(null, "libvlccore", dir);
        } catch (Throwable ignored) {}

        // VLC plugin path (used by libvlccore when scanning for codec modules)
        System.setProperty("VLC_PLUGIN_PATH", vlcDir.resolve("plugins").toString());
        // Also set as env-var substitute via libvlc option (passed later in factory args)
    }

    /** Called from {@code wireWindowClose()} to release the factory on app exit. */
    public static synchronized void releaseVlcFactory() {
        if (vlcFactory != null) { vlcFactory.release(); vlcFactory = null; }
    }

    /**
     * Creates the {@link CallbackMediaPlayer} for this pane and wires up the
     * BGRA video surface (supports full alpha channel) and the event adapter.
     */
    private void createVlcPlayer() {
        vlcPlayer = vlcFactory.mediaPlayers().newEmbeddedMediaPlayer();

        // BufferFormatCallback: tells VLC the pixel format and allocates the image.
        BufferFormatCallback bufFmt = new BufferFormatCallback() {
            @Override
            public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                // In alpha mode the FFmpeg thread owns videoImage — do not overwrite it.
                if (!alphaMode) {
                    WritableImage img = new WritableImage(sourceWidth, sourceHeight);
                    videoImage = img;               // volatile write — render callback reads this
                    Platform.runLater(() -> videoView.setImage(img));
                }
                // BGRA: 4 bytes/px, order B G R A — VLC outputs straight (non-pre-multiplied) alpha,
                // so the render callback uses getByteBgraInstance() (not the Pre variant).
                // VLC fills A=0xFF for opaque video; real alpha for VP9α / ProRes 4444.
                return new BufferFormat("BGRA", sourceWidth, sourceHeight,
                        new int[]{sourceWidth * 4}, new int[]{sourceHeight});
            }
            @Override
            public void newFormatSize(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
                // Called when VLC reports a dynamic resolution change.
                // Reallocate the WritableImage at the new output dimensions.
                if (!alphaMode) {
                    WritableImage img = new WritableImage(outputWidth, outputHeight);
                    videoImage = img;
                    Platform.runLater(() -> videoView.setImage(img));
                }
            }
            @Override
            public void allocatedBuffers(ByteBuffer[] buffers) {
                // Nothing to do — our WritableImage is allocated in getBufferFormat().
            }
        };

        // RenderCallback: receives decoded BGRA frames and paints into WritableImage.
        // Called on VLC's decode thread — NOT the FX thread.
        // In alpha mode the FFmpeg thread owns videoImage; skip to avoid double-writes
        // that would cause the video to appear to play at 2× speed on first load.
        // VLCJ 4.12+: RenderCallback has lock/display/unlock — lock & unlock are no-ops
        // here because WritableImage's PixelWriter is thread-safe for our usage.
        CallbackVideoSurface surface = vlcFactory.videoSurfaces().newVideoSurface(bufFmt,
            new uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback() {
                @Override
                public void lock(MediaPlayer mp) { /* no-op */ }
                @Override
                public void display(MediaPlayer mp, ByteBuffer[] nativeBuffers,
                                    BufferFormat bufferFormat, int displayWidth, int displayHeight) {
                    if (alphaMode) return;
                    WritableImage img = videoImage;
                    if (img == null) return;
                    img.getPixelWriter().setPixels(
                            0, 0, bufferFormat.getWidth(), bufferFormat.getHeight(),
                            PixelFormat.getByteBgraInstance(),
                            nativeBuffers[0], bufferFormat.getPitches()[0]);
                    // Pause-on-first-frame: triggered here (after pixels are written) rather than
                    // on the 'playing' event, so the frame is already visible when VLC pauses.
                    if (pauseOnFirstFrame) {
                        pauseOnFirstFrame = false;
                        Platform.runLater(() -> { if (vlcPlayer != null) vlcPlayer.controls().pause(); });
                    }
                    if (videoLoading) onFirstFrameArrived();
                }
                @Override
                public void unlock(MediaPlayer mp) { /* no-op */ }
            },
            true);
        vlcPlayer.videoSurface().set(surface);

        // Event adapter: keep UI controls synchronised with VLC state.
        vlcPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void timeChanged(MediaPlayer mp, long newTime) {
                Platform.runLater(() -> {
                    if (!seekInProgress) {
                        long total = vlcPlayer.status().length();
                        if (total > 0)
                            seekSlider.setValue((double) newTime / total);
                        timeCurrent.setText(formatTime(Duration.millis(newTime)));
                        // CC is updated by vlcCCTimer at 60 fps — no call needed here.
                    }
                });
            }
            @Override
            public void lengthChanged(MediaPlayer mp, long newLength) {
                Platform.runLater(() -> {
                    timeDuration.setText(formatTime(Duration.millis(newLength)));
                    if (currentVideoPath != null)
                        onStatusUpdate.accept(formatVideoStatus(currentVideoPath,
                                Duration.millis(newLength)));
                });
            }
            @Override
            public void playing(MediaPlayer mp) {
                // Apply stored playback rate every time playback (re)starts so it
                // survives pause/resume and video-to-video transitions.
                if (playbackRate != 1.0f) mp.controls().setRate(playbackRate);
                // Apply deferred seek (resume position) after audio is flowing,
                // not synchronously inside the playing callback — doing it here
                // on VLC's event thread can disrupt audio pipeline init.
                long seek = pendingSeekMs;
                if (seek > 0) {
                    pendingSeekMs = -1;
                    Platform.runLater(() -> { if (vlcPlayer != null) vlcPlayer.controls().setTime(seek); });
                } else {
                    pendingSeekMs = -1;
                }
                // Start the FFmpeg decode thread now that VLC audio is confirmed running.
                // This synchronises A/V start time so neither gets ahead of the other.
                if (pendingAlphaStart) {
                    pendingAlphaStart = false;
                    startAlphaFfmpegAt(0);
                    // Non-autoplay: pause VLC immediately (FFmpeg will show first frame then wait)
                    if (apPaused) {
                        mp.controls().pause();
                    }
                }
                Platform.runLater(() -> {
                    playPauseBtn.setText("⏸");
                    // Start 60 fps CC timer so word highlights update every frame.
                    if (vlcCCTimer == null) {
                        vlcCCTimer = new javafx.animation.AnimationTimer() {
                            @Override public void handle(long now) {
                                if (vlcPlayer != null)
                                    updateCC(Duration.millis(vlcPlayer.status().time()));
                            }
                        };
                    }
                    vlcCCTimer.start();
                });
            }
            @Override
            public void paused(MediaPlayer mp) {
                long time = mp.status().time();
                Platform.runLater(() -> {
                    playPauseBtn.setText("▶");
                    if (vlcCCTimer != null) vlcCCTimer.stop();
                    updateCC(Duration.millis(time));
                });
            }
            @Override
            public void stopped(MediaPlayer mp) {
                Platform.runLater(() -> {
                    playPauseBtn.setText("▶");
                    if (vlcCCTimer != null) vlcCCTimer.stop();
                });
            }
            @Override
            public void finished(MediaPlayer mp) {
                Platform.runLater(() -> {
                    playPauseBtn.setText("▶");
                    if (vlcCCTimer != null) vlcCCTimer.stop();
                    if (loopBtn.isSelected()) {
                        vlcPlayer.controls().play();
                    } else {
                        onEndOfMedia.run();
                    }
                });
            }
            @Override
            public void error(MediaPlayer mp) {
                videoLoading = false;
                Platform.runLater(() -> {
                    onFirstFrame = null;   // cancel pending fade-in; restore opacity
                    setOpacity(1.0);
                    if (spinnerDelayTimer != null) spinnerDelayTimer.stop();
                    loadingSpinner.setVisible(false);
                    showError("Cannot play video.\nFormat not supported or file is corrupt.");
                });
            }
        });
    }

    // ── Seek-bar preview helpers ──────────────────────────────────────────────

    private void updateSeekPreview(MouseEvent e) {
        if (currentVideoPath == null || vlcPlayer == null) return;
        long totalMs = vlcPlayer.status().length();
        if (totalMs <= 0) return;

        // Map mouse X → [0,1] fraction on the slider track (track has ~8 px insets each side)
        double inset    = 8.0;
        double fraction = Math.max(0, Math.min(1, (e.getX() - inset) / (seekSlider.getWidth() - 2 * inset)));
        long   targetMs = (long)(totalMs * fraction);

        seekPrevTime.setText(formatTime(Duration.millis(targetMs)));

        // Position popup above the slider, centred on the cursor, clamped inside videoPane
        final double POPUP_W = 168, POPUP_H = 118;
        javafx.geometry.Point2D inScene = seekSlider.localToScene(e.getX(), 0);
        javafx.geometry.Point2D inPane  = videoPane.sceneToLocal(inScene);
        double left = Math.max(4, Math.min(videoPane.getWidth()  - POPUP_W - 4, inPane.getX() - POPUP_W / 2));
        double top  = Math.max(4, inPane.getY() - POPUP_H - 6);
        StackPane.setMargin(seekPrevPopup, new javafx.geometry.Insets(top, 0, 0, left));
        seekPrevPopup.setVisible(true);

        // Serve from cache if available (1-second bucket)
        int   bucket = (int)(targetMs / 1000);
        Image cached = seekPrevCache.get(bucket);
        if (cached != null) { seekPrevView.setImage(cached); return; }

        // Submit extraction task; generation counter lets the task self-cancel if superseded
        long  gen  = seekPrevGen.incrementAndGet();
        Path  path = currentVideoPath;
        seekPrevExec.execute(() -> {
            if (seekPrevGen.get() != gen) return;
            Image img = extractSeekFrame(path, targetMs);
            if (img != null && seekPrevGen.get() == gen) {
                seekPrevCache.put(bucket, img);
                Platform.runLater(() -> { if (seekPrevPopup.isVisible()) seekPrevView.setImage(img); });
            }
        });
    }

    /** Extracts a single frame at {@code timeMs} via FFmpeg and returns it as a JavaFX Image. */
    private static Image extractSeekFrame(Path videoPath, long timeMs) {
        try {
            Path tmp = Files.createTempFile("seek_", ".jpg");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        BundledTools.ffmpeg(), "-y", "-loglevel", "quiet",
                        "-ss", String.format("%.3f", timeMs / 1000.0),
                        "-i", videoPath.toString(),
                        "-vframes", "1",
                        "-vf", "scale=320:-1",
                        tmp.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().readAllBytes();   // drain stdout/stderr
                boolean ok = p.waitFor(5, TimeUnit.SECONDS);
                if (!ok) p.destroyForcibly();
                if (ok && p.exitValue() == 0 && Files.exists(tmp) && Files.size(tmp) > 0) {
                    byte[] bytes = Files.readAllBytes(tmp);
                    return new Image(new java.io.ByteArrayInputStream(bytes), 160, 90, true, true);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            System.err.println("[seek-preview] frame extraction failed: " + e.getMessage());
        }
        return null;
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    /** Returns the current playback position in milliseconds (works for both VLC and alpha mode). */
    private long getCurrentPositionMs() {
        if (alphaMode && apDurationMs > 0)
            return (long)(seekSlider.getValue() * apDurationMs);
        if (vlcPlayer != null)
            return vlcPlayer.status().time();
        return 0;
    }

    /**
     * Shows a bookmark popup styled like the queue manager.
     * Positioned above the control bar and clamped to stay within the window.
     */
    private void showBookmarkPopup() {
        if (currentVideoPath == null) return;
        String pathKey = currentVideoPath.toString();

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);

        // ── Shell ─────────────────────────────────────────────────────────────
        VBox box = new VBox(0);
        box.setStyle(
                "-fx-background-color: #252525; " +
                "-fx-border-color: #4a4a4a; " +
                "-fx-border-width: 1; " +
                "-fx-border-radius: 6; " +
                "-fx-background-radius: 6; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.7), 12, 0, 0, 4);");
        box.setMinWidth(280);
        box.setMaxWidth(340);

        // ── Header row ────────────────────────────────────────────────────────
        Label header = new Label("🔖  Bookmarks");
        header.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px; -fx-padding: 8 10 6 10;");
        header.setMaxWidth(Double.MAX_VALUE);

        Button addBtn = new Button("＋  Add here");
        addBtn.setStyle(
                "-fx-background-color: #3a3f42; -fx-text-fill: #cccccc; " +
                "-fx-border-color: #555; -fx-border-radius: 4; -fx-background-radius: 4; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; -fx-cursor: hand;");
        addBtn.setOnMouseEntered(e -> addBtn.setStyle(
                "-fx-background-color: #4d78cc; -fx-text-fill: white; " +
                "-fx-border-color: #4d78cc; -fx-border-radius: 4; -fx-background-radius: 4; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; -fx-cursor: hand;"));
        addBtn.setOnMouseExited(e -> addBtn.setStyle(
                "-fx-background-color: #3a3f42; -fx-text-fill: #cccccc; " +
                "-fx-border-color: #555; -fx-border-radius: 4; -fx-background-radius: 4; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; -fx-cursor: hand;"));
        addBtn.setOnAction(e -> {
            popup.hide();
            long posMs = getCurrentPositionMs();
            List<AppConfig.VideoBookmark> existing = onGetBookmarks.apply(pathKey);
            String defaultName = "Bookmark " + (existing.size() + 1);
            TextInputDialog dlg = new TextInputDialog(defaultName);
            dlg.setTitle("Add Bookmark");
            dlg.setHeaderText("Position: " + formatTime(Duration.millis(posMs)));
            dlg.setContentText("Name:");
            dlg.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            dlg.showAndWait().ifPresent(name -> {
                if (!name.isBlank())
                    onAddBookmark.save(pathKey, name.trim(), posMs);
            });
        });

        HBox headerRow = new HBox(6, header, new Region(), addBtn);
        HBox.setHgrow(header, Priority.ALWAYS);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(4, 8, 4, 4));
        headerRow.setStyle("-fx-border-color: transparent transparent #3a3a3a transparent; -fx-border-width: 0 0 1 0;");
        box.getChildren().add(headerRow);

        // ── Bookmark rows ─────────────────────────────────────────────────────
        List<AppConfig.VideoBookmark> bookmarks = onGetBookmarks.apply(pathKey);
        if (bookmarks.isEmpty()) {
            Label empty = new Label("No bookmarks yet");
            empty.setStyle("-fx-text-fill: #555; -fx-font-size: 11px; -fx-padding: 10 10 10 10;");
            box.getChildren().add(empty);
        } else {
            String rowBase = "-fx-background-color: rgba(255,255,255,0.04); " +
                             "-fx-background-radius: 4; -fx-padding: 0 4 0 4;";
            String rowHover = "-fx-background-color: rgba(255,255,255,0.12); " +
                              "-fx-background-radius: 4; -fx-padding: 0 4 0 4;";

            VBox rowsBox = new VBox(2);
            rowsBox.setPadding(new Insets(6, 6, 6, 6));

            for (AppConfig.VideoBookmark bm : bookmarks) {
                Label tsLbl = new Label(formatTime(Duration.millis(bm.ms)));
                tsLbl.setStyle("-fx-text-fill: #4d78cc; -fx-font-size: 11px; " +
                               "-fx-font-weight: bold; -fx-min-width: 40px;");

                Label nameLbl = new Label(bm.name);
                nameLbl.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");
                nameLbl.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(nameLbl, Priority.ALWAYS);

                Button rmBtn = new Button("✕");
                rmBtn.setStyle(
                        "-fx-background-color: transparent; -fx-text-fill: #666; " +
                        "-fx-border-color: transparent; -fx-font-size: 10px; " +
                        "-fx-padding: 2 6 2 6; -fx-cursor: hand;");
                rmBtn.setOnMouseEntered(e -> rmBtn.setStyle(
                        "-fx-background-color: rgba(255,80,80,0.2); -fx-text-fill: #ff6666; " +
                        "-fx-border-color: transparent; -fx-font-size: 10px; " +
                        "-fx-background-radius: 3; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
                rmBtn.setOnMouseExited(e -> rmBtn.setStyle(
                        "-fx-background-color: transparent; -fx-text-fill: #666; " +
                        "-fx-border-color: transparent; -fx-font-size: 10px; " +
                        "-fx-padding: 2 6 2 6; -fx-cursor: hand;"));
                final String bmName = bm.name;
                rmBtn.setOnAction(e -> { popup.hide(); onDeleteBookmark.accept(pathKey, bmName); });

                HBox row = new HBox(8, tsLbl, nameLbl, rmBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPrefHeight(32);
                row.setStyle(rowBase);
                row.setCursor(Cursor.HAND);
                row.setOnMouseEntered(e -> row.setStyle(rowHover));
                row.setOnMouseExited(e -> row.setStyle(rowBase));
                row.setOnMouseClicked(e -> {
                    if (e.getTarget() == rmBtn) return;
                    popup.hide();
                    seekToMs(bm.ms);
                });

                rowsBox.getChildren().add(row);
            }
            box.getChildren().add(rowsBox);
        }

        popup.getContent().add(box);

        // ── Position: above button, clamped to window ─────────────────────────
        javafx.geometry.Bounds b = bookmarkBtn.localToScreen(bookmarkBtn.getBoundsInLocal());
        if (b == null) return;
        javafx.stage.Window win = bookmarkBtn.getScene().getWindow();

        // Show off-screen first to get real dimensions, then reposition
        popup.show(win, -9999, -9999);
        Platform.runLater(() -> {
            double pw = popup.getWidth();
            double ph = popup.getHeight();
            double targetX = b.getMinX();
            double targetY = b.getMinY() - ph - 6;
            // Clamp within window
            double clampedX = Math.max(win.getX() + 4,
                              Math.min(targetX, win.getX() + win.getWidth() - pw - 4));
            double clampedY = Math.max(win.getY() + 4,
                              Math.min(targetY, win.getY() + win.getHeight() - ph - 4));
            popup.setX(clampedX);
            popup.setY(clampedY);
        });
    }

    // ── Alpha-video: FFmpeg frame-pipe + VLC audio-only ───────────────────────

    /**
     * Probe the file with ffprobe, allocate the WritableImage, start VLC for audio-only,
     * then hand off to {@link #startAlphaFfmpegAt} for the actual frame pipe.
     */
    private void startAlphaVideo(Path path) {
        alphaMode    = true;
        apStopped    = false;
        apPaused     = !autoPlay;
        apW          = 0; apH = 0;
        apFps        = 30.0;
        apDurationMs = 0;

        // Probe using libavformat (no subprocess)
        try {
            AVFormatContext fmtCtx = avformat_alloc_context();
            if (avformat_open_input(fmtCtx, path.toString(), null, null) >= 0) {
                avformat_find_stream_info(fmtCtx, (PointerPointer) null);
                for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                    AVStream st = fmtCtx.streams(i);
                    if (st.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO && apW == 0) {
                        apW = st.codecpar().width();
                        apH = st.codecpar().height();
                        // r_frame_rate is the codec's native frame rate;
                        // avg_frame_rate can be 2× for field-encoded .mov files.
                        AVRational fr = st.r_frame_rate();
                        if (fr.den() == 0 || fr.num() == 0) fr = st.avg_frame_rate();
                        if (fr.den() != 0 && fr.num() != 0) {
                            apFps = (double) fr.num() / fr.den();
                            if (apFps > 120) apFps = 30.0; // guard against bogus values
                        }
                        if (st.duration() > 0) {
                            AVRational tb = st.time_base();
                            if (tb.den() != 0)
                                apDurationMs = (long)(st.duration() * (double)tb.num() / tb.den() * 1000);
                        }
                    }
                }
                if (apDurationMs <= 0 && fmtCtx.duration() > 0)
                    apDurationMs = fmtCtx.duration() / 1000; // AV_TIME_BASE = 1_000_000 µs
                avformat_close_input(fmtCtx);
            }
        } catch (Exception e) {
            System.err.println("[alpha] ffprobe probe failed for " + path.getFileName() + ": " + e.getMessage());
        }

        if (apW <= 0 || apH <= 0) {
            // ffprobe unavailable or failed: fall back to normal VLC playback (no alpha)
            alphaMode = false;
            pauseOnFirstFrame = !autoPlay;
            vlcPlayer.media().play(path.toString());
            Platform.runLater(() -> onStatusUpdate.accept(
                    path.getFileName() + "  (alpha probe failed — playing via VLC)"));
            return;
        }

        apRawW = apW;
        apRawH = apH;

        // Allocate the frame image and wire it into the video view
        WritableImage img = new WritableImage(apW, apH);
        videoImage = img;
        Platform.runLater(() -> {
            videoView.setImage(img);
            if (apDurationMs > 0) timeDuration.setText(formatTime(Duration.millis(apDurationMs)));
            playPauseBtn.setText(apPaused ? "▶" : "⏸");
        });

        // VLC handles audio only (video track suppressed so VLC doesn't fight FFmpeg)
        // FFmpeg thread is started from the VLC 'playing' event (see playing() below) so that
        // audio and video both begin from the same wall-clock moment.  Starting FFmpeg here
        // caused A/V desync on first load because VLC needs time to initialise its audio pipeline.
        pendingAlphaStart = true;
        vlcPlayer.media().play(path.toString(), ":no-video");
    }

    /**
     * (Re)start the JavaCPP FFmpeg decode from {@code seekMs} milliseconds.
     * Stops any existing reader thread before starting the new one.
     */
    private void startAlphaFfmpegAt(long seekMs) {
        apStopped = true;
        if (apThread != null) {
            apThread.interrupt();
            try { apThread.join(3000); } catch (InterruptedException ignored) {}
            apThread = null;
        }
        apStopped = false;

        final Path vidPath = currentVideoPath;
        final long startMs = Math.max(0, seekMs);
        final int  w = apW, h = apH;
        final int  nW = apRawW > 0 ? apRawW : w;
        final int  nH = apRawH > 0 ? apRawH : h;
        final int  bgraSize = w * h * 4;

        apThread = new Thread(() -> {
            AVFormatContext fmtCtx = avformat_alloc_context();
            if (avformat_open_input(fmtCtx, vidPath.toString(), null, null) < 0) return;
            if (avformat_find_stream_info(fmtCtx, (PointerPointer) null) < 0) {
                avformat_close_input(fmtCtx); return;
            }

            int vidStream = -1;
            for (int i = 0; i < fmtCtx.nb_streams(); i++) {
                if (fmtCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    vidStream = i; break;
                }
            }
            if (vidStream < 0) { avformat_close_input(fmtCtx); return; }

            boolean isWebm = vidPath.getFileName().toString().toLowerCase().endsWith(".webm");
            AVCodec codec = null;
            if (isWebm) {
                int cid = fmtCtx.streams(vidStream).codecpar().codec_id();
                if (cid == AV_CODEC_ID_VP9)
                    codec = avcodec_find_decoder_by_name("libvpx-vp9");
                else if (cid == AV_CODEC_ID_VP8)
                    codec = avcodec_find_decoder_by_name("libvpx");
            }
            if (codec == null || codec.isNull())
                codec = avcodec_find_decoder(fmtCtx.streams(vidStream).codecpar().codec_id());

            AVCodecContext codecCtx = avcodec_alloc_context3(codec);
            avcodec_parameters_to_context(codecCtx, fmtCtx.streams(vidStream).codecpar());
            codecCtx.thread_count(4);

            if (avcodec_open2(codecCtx, codec, (AVDictionary) null) < 0) {
                avcodec_free_context(codecCtx);
                avformat_close_input(fmtCtx);
                return;
            }

            if (startMs > 0) {
                long seekUs = startMs * 1000L;
                avformat_seek_file(fmtCtx, -1, Long.MIN_VALUE, seekUs, seekUs, 0);
                avcodec_flush_buffers(codecCtx);
            }

            // swsCtx is used only for non-alpha video (e.g. yuv420p opaque).
            // Alpha YUV formats (yuva420p, yuva444p10le, …) use avFrameToBgra() directly
            // because swscale silently drops the alpha plane for many of those formats.
            SwsContext[] swsCtxHolder = { null };
            AVFrame dstFrame = av_frame_alloc();
            dstFrame.format(AV_PIX_FMT_BGRA);
            dstFrame.width(w); dstFrame.height(h);
            av_frame_get_buffer(dstFrame, 1);

            AVPacket pkt    = av_packet_alloc();
            AVFrame  frame  = av_frame_alloc();
            byte[]   bgra   = new byte[bgraSize];
            PixelFormat<ByteBuffer> fmt = PixelFormat.getByteBgraInstance();

            // PTS-based timing: use actual frame presentation timestamps instead of
            // a fixed frame interval derived from apFps.  This is immune to any fps
            // miscalculation (field-rate vs frame-rate, variable-rate content, etc.)
            // and naturally handles the "first play 2× speed" issue caused by incorrect
            // avg_frame_rate values on some .mov containers.
            AVRational streamTb  = fmtCtx.streams(vidStream).time_base();
            double     tbToMs    = (double) streamTb.num() / streamTb.den() * 1000.0;
            // Fallback fixed-interval for when PTS is unavailable
            long frameNs   = (long)(1_000_000_000.0 / apFps);
            long nextNs    = System.nanoTime();   // used only for PTS-unavailable fallback
            long firstPts  = Long.MIN_VALUE;      // PTS of the first decoded frame
            long playStartNs = -1;                // wall-clock ns when first frame was decoded
            long startWall = System.currentTimeMillis();
            long lastUiMs  = 0;
            boolean firstFrame = true;

            try {
                outer:
                while (!apStopped) {
                    // Always decode the first frame so a preview is visible even when paused.
                    // Subsequent frames respect the pause state normally.
                    if (!firstFrame) {
                        long pauseEntryNs = -1;
                        while (!apStopped && apPaused) {
                            if (pauseEntryNs < 0) pauseEntryNs = System.nanoTime();
                            Thread.sleep(20);
                        }
                        // Shift the PTS clock forward by the pause duration so that
                        // the next frame's target time is relative to *now*, not to
                        // when the first frame was decoded.  Without this, frames after
                        // a pause (including the initial pause-on-first-frame) all have
                        // negative sleep times and the video plays at decoder-max speed.
                        if (pauseEntryNs >= 0 && playStartNs > 0)
                            playStartNs += System.nanoTime() - pauseEntryNs;
                    }
                    if (apStopped) break;

                    // Read next video packet
                    while (true) {
                        int r = av_read_frame(fmtCtx, pkt);
                        if (r < 0) break outer;
                        if (pkt.stream_index() == vidStream) break;
                        av_packet_unref(pkt);
                    }

                    // Capture packet PTS before unreffing — packet timestamps are always
                    // in the stream's timebase, unlike frame.pts() which may be rescaled
                    // to the codec's internal timebase after avcodec_receive_frame().
                    long pktPts = pkt.pts();
                    if (pktPts == AV_NOPTS_VALUE) pktPts = pkt.dts();

                    avcodec_send_packet(codecCtx, pkt);
                    av_packet_unref(pkt);

                    // Drain decoded frames from this packet
                    while (!apStopped) {
                        int r = avcodec_receive_frame(codecCtx, frame);
                        if (r < 0) break; // EAGAIN or EOF from decoder

                        int frameFmt = frame.format();
                        if (frameFmt == AV_PIX_FMT_YUVA420P) {
                            // VP9 alpha (WebM): swscale correctly preserves alpha for yuva420p → bgra
                            if (swsCtxHolder[0] == null)
                                swsCtxHolder[0] = sws_getContext(
                                        frame.width(), frame.height(), AV_PIX_FMT_YUVA420P,
                                        w, h, AV_PIX_FMT_BGRA,
                                        SWS_BILINEAR, null, null, (DoublePointer) null);
                            if (swsCtxHolder[0] == null) { av_frame_unref(frame); continue; }
                            sws_scale(swsCtxHolder[0], frame.data(), frame.linesize(),
                                      0, frame.height(), dstFrame.data(), dstFrame.linesize());
                            dstFrame.data(0).get(bgra, 0, bgraSize);
                        } else if (av_pix_fmt_count_planes(frameFmt) == 4) {
                            // yuva444p10le etc. (ProRes 4444): swscale drops alpha; convert manually
                            avFrameToBgra(frame, bgra, w, h);
                        } else {
                            // Opaque video: lazy-create swsCtx and use swscale
                            if (swsCtxHolder[0] == null && frameFmt != AV_PIX_FMT_NONE) {
                                swsCtxHolder[0] = sws_getContext(
                                        frame.width(), frame.height(), frameFmt,
                                        w, h, AV_PIX_FMT_BGRA,
                                        SWS_BILINEAR, null, null, (DoublePointer) null);
                            }
                            if (swsCtxHolder[0] == null) { av_frame_unref(frame); continue; }
                            sws_scale(swsCtxHolder[0], frame.data(), frame.linesize(),
                                      0, frame.height(), dstFrame.data(), dstFrame.linesize());
                            dstFrame.data(0).get(bgra, 0, bgraSize);
                        }
                        av_frame_unref(frame);

                        WritableImage wimg = videoImage;
                        if (wimg != null && (int)wimg.getWidth() == w && (int)wimg.getHeight() == h)
                            wimg.getPixelWriter().setPixels(0, 0, w, h, fmt, ByteBuffer.wrap(bgra), w * 4);

                        // ── Timing ───────────────────────────────────────────
                        if (firstFrame) {
                            firstFrame   = false;
                            firstPts     = pktPts;
                            playStartNs  = System.nanoTime();
                            nextNs       = playStartNs;   // sync fallback anchor
                            startWall    = System.currentTimeMillis();
                            if (videoLoading) onFirstFrameArrived();
                        }

                        long frameOffsetMs = 0;
                        if (pktPts != AV_NOPTS_VALUE && firstPts != Long.MIN_VALUE && tbToMs > 0) {
                            // PTS-based: sleep until packet's scheduled wall-clock time.
                            // pktPts is always in the stream's timebase (guaranteed by container).
                            frameOffsetMs = (long)((pktPts - firstPts) * tbToMs);
                            long targetNs = playStartNs + frameOffsetMs * 1_000_000L;
                            long sleepNs  = targetNs - System.nanoTime();
                            if (sleepNs > 1_000_000L && sleepNs < 10_000_000_000L)
                                Thread.sleep(sleepNs / 1_000_000, (int)(sleepNs % 1_000_000));
                        } else {
                            // Fallback: fixed interval based on apFps
                            nextNs += frameNs;
                            long sleepNs = nextNs - System.nanoTime();
                            if (sleepNs > 0)
                                Thread.sleep(sleepNs / 1_000_000, (int)(sleepNs % 1_000_000));
                            frameOffsetMs = System.currentTimeMillis() - startWall;
                        }

                        // Seek bar / time label ~4× / second
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastUiMs >= 250) {
                            lastUiMs = nowMs;
                            long curMs = startMs + frameOffsetMs;
                            Platform.runLater(() -> {
                                if (!seekInProgress) {
                                    timeCurrent.setText(formatTime(Duration.millis(curMs)));
                                    if (apDurationMs > 0)
                                        seekSlider.setValue((double) curMs / apDurationMs);
                                }
                            });
                        }
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (swsCtxHolder[0] != null) sws_freeContext(swsCtxHolder[0]);
                av_frame_free(dstFrame);
                av_frame_free(frame);
                av_packet_free(pkt);
                avcodec_free_context(codecCtx);
                avformat_close_input(fmtCtx);
            }

            if (!apStopped) {
                Platform.runLater(() -> {
                    if (loopBtn.isSelected()) {
                        startAlphaFfmpegAt(0);
                        if (vlcPlayer != null) { vlcPlayer.controls().setTime(0); vlcPlayer.controls().play(); }
                    } else {
                        apPaused = true;
                        playPauseBtn.setText("▶");
                        if (onEndOfMedia != null) onEndOfMedia.run();
                    }
                });
            }
        }, "alpha-javacpp-video");
        apThread.setDaemon(true);
        apThread.start();
    }

    /**
     * Convert a decoded AVFrame (any planar YUV+alpha format) to packed BGRA.
     * Handles yuva420p (VP9 alpha), yuva444p (8-bit), yuva444p10le (ProRes 4444 10-bit), etc.
     * Uses the pixel-format descriptor to determine chroma subsampling and bit depth automatically.
     * BT.601 limited-range YCbCr→RGB; alpha copied unchanged (scaled to 8-bit for >8-bit depths).
     */
    private static void avFrameToBgra(AVFrame frame, byte[] bgra, int w, int h) {
        int fmt = frame.format();
        // Derive subsampling and depth from the pixel-format descriptor.
        // Falls back to yuva444p10le params (ProRes 4444 10-bit) if descriptor is unavailable.
        int chromaShiftW, chromaShiftH, bytesPerSamp, depthShift;
        org.bytedeco.ffmpeg.avutil.AVPixFmtDescriptor desc = av_pix_fmt_desc_get(fmt);
        if (desc != null && !desc.isNull()) {
            chromaShiftW = desc.log2_chroma_w();  // 1 = half-width (420), 0 = full (444)
            chromaShiftH = desc.log2_chroma_h();
            int depth    = desc.comp(0).depth();  // bits per sample: 8, 10, 12, 16
            bytesPerSamp = depth > 8 ? 2 : 1;
            depthShift   = depth > 8 ? depth - 8 : 0;
        } else {
            // Fallback: assume yuva444p10le (ProRes 4444 10-bit, the most common ProRes alpha format)
            chromaShiftW = 0; chromaShiftH = 0; bytesPerSamp = 2; depthShift = 2;
        }

        int yStride = frame.linesize(0);
        int uStride = frame.linesize(1);
        int vStride = frame.linesize(2);
        int aStride = frame.linesize(3);

        int uvH = h >> chromaShiftH;
        // Bulk-copy native plane buffers to Java arrays (one JNI call each, not per pixel)
        byte[] yBuf = new byte[h   * yStride];
        byte[] uBuf = new byte[uvH * uStride];
        byte[] vBuf = new byte[uvH * vStride];
        byte[] aBuf = new byte[h   * aStride];
        frame.data(0).get(yBuf);
        frame.data(1).get(uBuf);
        frame.data(2).get(vBuf);
        frame.data(3).get(aBuf);

        for (int row = 0; row < h; row++) {
            int uvRow = row >> chromaShiftH;
            for (int col = 0; col < w; col++) {
                int uvCol = col >> chromaShiftW;
                int Y, U, V, A;
                if (bytesPerSamp == 1) {
                    Y = yBuf[row  * yStride + col]           & 0xFF;
                    U = uBuf[uvRow * uStride + uvCol]        & 0xFF;
                    V = vBuf[uvRow * vStride + uvCol]        & 0xFF;
                    A = aBuf[row  * aStride + col]           & 0xFF;
                } else {
                    // 2-byte LE sample (10/12/16-bit) — shift to 8-bit range
                    int yOff = row  * yStride + col  * 2;
                    int uOff = uvRow * uStride + uvCol * 2;
                    int vOff = uvRow * vStride + uvCol * 2;
                    int aOff = row  * aStride + col  * 2;
                    Y = ((yBuf[yOff] & 0xFF) | ((yBuf[yOff+1] & 0xFF) << 8)) >> depthShift;
                    U = ((uBuf[uOff] & 0xFF) | ((uBuf[uOff+1] & 0xFF) << 8)) >> depthShift;
                    V = ((vBuf[vOff] & 0xFF) | ((vBuf[vOff+1] & 0xFF) << 8)) >> depthShift;
                    A = ((aBuf[aOff] & 0xFF) | ((aBuf[aOff+1] & 0xFF) << 8)) >> depthShift;
                }
                Y -= 16; U -= 128; V -= 128;
                int r = avClamp(298 * Y + 409 * V + 128);
                int g = avClamp(298 * Y - 100 * U - 208 * V + 128);
                int b = avClamp(298 * Y + 516 * U + 128);
                int di = (row * w + col) << 2;
                bgra[di]     = (byte) b;
                bgra[di + 1] = (byte) g;
                bgra[di + 2] = (byte) r;
                bgra[di + 3] = (byte) A;
            }
        }
    }
    private static int avClamp(int v) { v >>= 8; return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** Stop the JavaCPP FFmpeg decode thread and clear alpha mode state. */
    private void stopAlphaVideo() {
        apStopped = true;
        if (apThread != null) { apThread.interrupt(); apThread = null; }
        alphaMode = false;
    }

    private void stopGifAnimation() {
        if (gifTimeline != null) { gifTimeline.stop(); gifTimeline = null; }
    }

    /** Called from render threads when the first frame has been written to the surface.
     *  Stops the spinner delay, hides the spinner, then fires (and clears) onFirstFrame. */
    private void onFirstFrameArrived() {
        videoLoading = false;
        Platform.runLater(() -> {
            if (spinnerDelayTimer != null) spinnerDelayTimer.stop();
            loadingSpinner.setVisible(false);
            Runnable cb = onFirstFrame;
            onFirstFrame = null;
            if (cb != null) cb.run();
        });
    }

    private void disposeVideo() {
        stopGifAnimation();
        if (alphaMode) stopAlphaVideo();
        if (controlFadeTimer != null) { controlFadeTimer.stop(); controlFadeTimer = null; }
        // Clean up JavaFX MediaPlayer if active
        if (usingFxMedia) {
            if (fxMediaPlayer != null) {
                fxMediaPlayer.stop();
                fxMediaPlayer.dispose();
                fxMediaPlayer = null;
            }
            fxMediaView.setMediaPlayer(null);
            fxMediaView.setVisible(false);
            videoView.setVisible(true);
            usingFxMedia = false;
        }
        // Save resume position before stopping
        if (vlcPlayer != null && currentVideoPath != null) {
            long pos = vlcPlayer.status().time();
            long len = vlcPlayer.status().length();
            boolean nearEnd = len > 0 && pos >= len - 30_000;
            onSavePosition.accept(currentVideoPath, (nearEnd || pos < 10_000) ? 0L : pos);
        }
        if (vlcPlayer != null) vlcPlayer.controls().stop();
        if (seekPrevPopup != null) seekPrevPopup.setVisible(false);
        seekPrevGen.incrementAndGet();   // discard any in-flight preview task
        seekPrevCache.clear();
        seekPrevView.setImage(null);
        seekSlider.setValue(0);
        timeCurrent.setText("0:00");
        timeDuration.setText("0:00");
        playPauseBtn.setText("▶");
        ccVisible = false;
        ccPane.setVisible(false);
    }

    private void showError(String msg) {
        clearMedia();
        // Use a temporary overlay so imgScroll / videoPane are NOT evicted from the
        // scene graph — if we used getChildren().setAll() they would be gone and every
        // subsequent showVideo() call would produce a black screen.
        Label lbl = new Label(msg);
        lbl.setStyle("-fx-text-fill: #ff6666; -fx-font-size: 14px; -fx-wrap-text: true; " +
                "-fx-text-alignment: center;");
        lbl.setMaxWidth(460);
        lbl.setWrapText(true);
        StackPane overlay = new StackPane(lbl);
        overlay.setStyle("-fx-background-color: #1a1a1a;");
        overlay.setMouseTransparent(true);
        getChildren().add(overlay);
        // Auto-remove after 6 s so the pane is ready for the next media load.
        Timeline remove = new Timeline(new KeyFrame(Duration.seconds(6),
                e -> getChildren().remove(overlay)));
        remove.play();
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private static String formatTime(Duration d) {
        if (d == null || d.isUnknown() || d.isIndefinite()) return "--:--";
        int total = (int) d.toSeconds();
        int sec = total % 60, min = (total / 60) % 60, hr = total / 3600;
        return hr > 0 ? String.format("%d:%02d:%02d", hr, min, sec)
                      : String.format("%d:%02d", min, sec);
    }

    private static String formatImageStatus(Path path, Image img) {
        try {
            return String.format("%s  |  %.0f × %.0f  |  %s",
                    path.getFileName(), img.getWidth(), img.getHeight(),
                    humanSize(Files.size(path)));
        } catch (IOException e) { return path.getFileName().toString(); }
    }

    private static String formatVideoStatus(Path path, Duration dur) {
        try {
            return String.format("%s  |  %s  |  %s",
                    path.getFileName(), formatTime(dur), humanSize(Files.size(path)));
        } catch (IOException e) { return path.getFileName().toString(); }
    }

    private static String humanSize(long b) {
        if (b < 1024)      return b + " B";
        if (b < 1 << 20)   return String.format("%.1f KB", b / 1024.0);
        if (b < 1 << 30)   return String.format("%.1f MB", b / (double)(1 << 20));
        return                    String.format("%.2f GB", b / (double)(1 << 30));
    }

    private static Duration clampDuration(Duration v, Duration lo, Duration hi) {
        return v.lessThan(lo) ? lo : v.greaterThan(hi) ? hi : v;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}

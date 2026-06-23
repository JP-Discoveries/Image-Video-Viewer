package com.imageviewer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A virtualized grid-based thumbnail browser dialog.
 *
 * Uses a ListView of rows (each row = COLS cells) so only visible rows are
 * rendered regardless of total file count.  A thumbCache avoids re-loading
 * images when rows are recycled.  A filter field narrows results by filename.
 * Clicking a thumbnail calls {@code onNavigate} and closes the dialog;
 * Ctrl+click toggles the slideshow queue; Alt+click loads into compare pane.
 */
public class ThumbnailBrowserDialog {

    private static final double CELL_W = 320;
    private static final double CELL_H = 220;
    private static final int    COLS   = 4;             // cells per row

    private final Stage               stage;
    private final ListView<int[]>     listView;         // each int[] = row of file indices
    private final TextField           searchField;
    private Label                     countLabel;       // shows filtered file count
    /** 0=Name  1=Date  2=Size  3=Type  4=Ext  5=Random */
    private int     sortMode   = 0;
    /** true = ascending (A→Z / oldest / smallest); false = descending */
    private boolean sortAsc    = true;
    /** References to the 6 sort buttons so updateSortButtons() can relabel them. */
    private final Button[] sortBtns = new Button[6];
    /** 0 = All, 1 = Images only, 2 = Videos only */
    private int filterMode = 0;
    private final List<MediaFile>     files;
    private       int                 currentIdx;
    private final IntConsumer         onNavigate;
    private final IntConsumer         onQueueToggle;
    private final Consumer<int[]>     onQueueRange;
    private final IntConsumer         onAltSelect;
    /** Right-click context menu callbacks. */
    private IntConsumer               onReveal      = i -> {};
    private IntConsumer               onRename      = i -> {};
    private Consumer<List<Integer>>   onBatchDelete = l -> {};
    private Consumer<List<Integer>>   onBatchRename = l -> {};
    private IntConsumer               onStar        = i -> {};
    private IntConsumer               onCopyTo      = i -> {};
    private IntConsumer               onMoveTo      = i -> {};
    /** When true, only starred files appear in the browser. */
    private boolean starFilterActive = false;
    /** Last index normally clicked — anchor for Shift+click range selection. */
    private int shiftAnchorIdx = -1;
    /** Current queue positions (index → 1-based position); kept in sync so
     *  cells rebuilt on scroll get the correct badge without waiting for a refresh. */
    private Map<Integer, Integer>     queuePositions = new HashMap<>();
    /** Toolbar button enabled when 2+ files are queued. */
    private Button                    batchRenameBtn;
    /** Clears all queued files; enabled whenever queue is non-empty. */
    private Button                    cancelQueueBtn;
    /** Notifies MainWindow to clear the rename queue. */
    private Runnable                  onClearQueue;
    /** Fast pool — loads existing sidecars and images only (no FFmpeg). */
    private final ExecutorService     loadPool;
    /** Slow pool — runs ffprobe + ffmpeg to generate missing video sidecars. */
    private final ExecutorService     generatePool;
    /** Cache of loaded images by file index — avoids re-loading on cell recycle. */
    private final Map<Integer, Image> thumbCache    = new ConcurrentHashMap<>();
    /** Tracks indices already submitted to avoid duplicate load tasks. */
    private final Set<Integer>        submitted     = ConcurrentHashMap.newKeySet();
    /** Most-recently-created queue badge label per file index (updated on cell rebuild). */
    private final Map<Integer, Label>     badgeLabels    = new HashMap<>();
    /** Most-recently-created thumbnail ImageView per file index — lets async load callbacks
     *  target the current visible cell even after the cell was recycled and rebuilt. */
    private final Map<Integer, ImageView> liveImageViews = new ConcurrentHashMap<>();

    public ThumbnailBrowserDialog(Window owner, Scene ownerScene,
                                  List<MediaFile> files, int currentIdx,
                                  int initialSortMode, boolean initialSortAsc,
                                  IntConsumer onNavigate,
                                  IntConsumer onQueueToggle,
                                  Consumer<int[]> onQueueRange,
                                  IntConsumer onAltSelect) {
        this.files         = files;
        this.currentIdx    = currentIdx;
        this.sortMode      = initialSortMode;
        this.sortAsc       = initialSortAsc;
        this.onNavigate    = onNavigate;
        this.onQueueToggle = onQueueToggle;
        this.onQueueRange  = onQueueRange;
        this.onAltSelect   = onAltSelect;
        this.loadPool = new ThreadPoolExecutor(8, 8, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "browser-load"); t.setDaemon(true); return t; });
        this.generatePool = new ThreadPoolExecutor(3, 3, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "browser-gen"); t.setDaemon(true); return t; });

        stage = new Stage(StageStyle.UNDECORATED);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setOnHidden(e -> { if (onClose != null) onClose.run(); });
        if (owner instanceof Stage ownerStage)
            stage.getIcons().addAll(ownerStage.getIcons());

        // ── Row 1: search + count ─────────────────────────────────────────────
        searchField = new TextField();
        searchField.setPromptText("Filter by name…");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, o, n) ->
                rebuildGrid(n.trim().toLowerCase()));

        countLabel = new Label(files.size() + " file" + (files.size() == 1 ? "" : "s"));
        countLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");

        HBox searchRow = new HBox(8, searchField, countLabel);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.setPadding(new Insets(8, 8, 4, 8));

        // ── Row 2: sort buttons + divider + filter toggles ────────────────────
        // Sort — clicking the active button flips direction; clicking a new one
        // sets it with its natural default direction.
        sortBtns[0] = makeSortBtn("Name",   0, true);
        sortBtns[1] = makeSortBtn("Date",   1, false);   // default: newest first
        sortBtns[2] = makeSortBtn("Size",   2, false);   // default: largest first
        sortBtns[3] = makeSortBtn("Type",   3, true);
        sortBtns[4] = makeSortBtn("Ext",    4, true);
        sortBtns[5] = makeSortBtn("Random", 5, true);
        updateSortButtons();   // label the initial active button (Name ↑)

        HBox sortGroup = new HBox(5,
                sortBtns[0], sortBtns[1], sortBtns[2],
                sortBtns[3], sortBtns[4], sortBtns[5]);
        sortGroup.setAlignment(Pos.CENTER_LEFT);

        // Thin vertical divider
        Region divider = new Region();
        divider.setStyle("-fx-background-color: #444;");
        divider.setPrefSize(1, 20);
        HBox.setMargin(divider, new Insets(0, 10, 0, 10));

        // Filter toggles
        ToggleButton allBtn = filterBtn("All");
        ToggleButton imgBtn = filterBtn("🖼");
        ToggleButton vidBtn = filterBtn("🎬");
        allBtn.setTooltip(new Tooltip("Show all files"));
        imgBtn.setTooltip(new Tooltip("Images only"));
        vidBtn.setTooltip(new Tooltip("Videos only"));

        ToggleGroup filterGroup = new ToggleGroup();
        allBtn.setToggleGroup(filterGroup);
        imgBtn.setToggleGroup(filterGroup);
        vidBtn.setToggleGroup(filterGroup);
        allBtn.setSelected(true);

        filterGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if      (n == imgBtn) filterMode = 1;
            else if (n == vidBtn) filterMode = 2;
            else                  filterMode = 0;
            rebuildGrid(searchField.getText().trim().toLowerCase());
        });

        allBtn.setOnMousePressed(e -> { if (allBtn.isSelected()) e.consume(); });
        imgBtn.setOnMousePressed(e -> { if (imgBtn.isSelected()) e.consume(); });
        vidBtn.setOnMousePressed(e -> { if (vidBtn.isSelected()) e.consume(); });

        // Star filter — independent toggle (can combine with All/🖼/🎬)
        Region divider2 = new Region();
        divider2.setStyle("-fx-background-color: #444;");
        divider2.setPrefSize(1, 20);
        HBox.setMargin(divider2, new Insets(0, 6, 0, 6));

        ToggleButton starBtn = new ToggleButton("⭐");
        starBtn.getStyleClass().add("toolbar-btn");
        starBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3 8 3 8;");
        starBtn.setFocusTraversable(false);
        starBtn.setTooltip(new Tooltip("Show only starred files"));
        starBtn.setOnMousePressed(e -> { if (starBtn.isSelected()) e.consume(); });
        starBtn.setOnAction(e -> {
            starFilterActive = starBtn.isSelected();
            rebuildGrid(searchField.getText().trim().toLowerCase());
        });

        HBox filterGroup2 = new HBox(0, allBtn, imgBtn, vidBtn, divider2, starBtn);
        filterGroup2.setAlignment(Pos.CENTER_LEFT);

        batchRenameBtn = new Button("✏  Rename queued (0)");
        batchRenameBtn.getStyleClass().add("toolbar-btn");
        batchRenameBtn.setDisable(true);
        batchRenameBtn.setOnAction(e -> {
            if (queuePositions.size() >= 2) {
                List<Integer> idxs = new ArrayList<>(queuePositions.keySet());
                Collections.sort(idxs);
                onBatchRename.accept(idxs);
            }
        });

        cancelQueueBtn = new Button("✕  Clear queue");
        cancelQueueBtn.getStyleClass().add("toolbar-btn");
        cancelQueueBtn.setDisable(true);
        cancelQueueBtn.setTooltip(new Tooltip("Remove all files from the rename queue"));
        cancelQueueBtn.setOnAction(e -> {
            if (onClearQueue != null) onClearQueue.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controlRow = new HBox(8, sortGroup, divider, filterGroup2, spacer,
                cancelQueueBtn, batchRenameBtn);
        controlRow.setAlignment(Pos.CENTER_LEFT);
        controlRow.setPadding(new Insets(0, 8, 6, 8));

        // ── Virtualized grid (ListView of rows) ───────────────────────────────
        listView = new ListView<>();
        listView.setCellFactory(lv -> new GridRowCell());
        listView.getStyleClass().add("thumb-browser-list");
        VBox.setVgrow(listView, Priority.ALWAYS);

        VBox content = new VBox(searchRow, controlRow, listView);
        VBox.setVgrow(listView, Priority.ALWAYS);

        HBox titleBar = buildTitleBar(stage);
        VBox root = new VBox(titleBar, content);
        VBox.setVgrow(content, Priority.ALWAYS);

        double initW = owner != null ? Math.max(600, Math.min(1400, owner.getWidth()  * 0.85)) : 730;
        double initH = owner != null ? Math.max(450, Math.min(1000, owner.getHeight() * 0.85)) : 560;
        Scene scene = new Scene(root, initW, initH);
        if (ownerScene != null) scene.getStylesheets().addAll(ownerScene.getStylesheets());
        stage.setScene(scene);
        stage.setResizable(true);

        rebuildGrid("");

        stage.setOnShown(e -> {
            // Centre over the owner window (undecorated stages don't do this automatically)
            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth()  - stage.getWidth())  / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            }
            // Scroll to the currently-selected file using its actual sorted row position.
            // Deferred one frame so the ListView finishes its layout pass first —
            // otherwise the virtual cell height isn't settled and scrollTo lands one row short.
            int row = findRowOf(currentIdx);
            if (row >= 0) {
                final int r = row;
                Platform.runLater(() -> listView.scrollTo(r));
            }
        });
    }

    public void showAndWait() {
        stage.showAndWait();
        loadPool.shutdownNow();
        generatePool.shutdownNow();
    }

    /** Returns the sort mode active when the dialog was closed (0=Name … 5=Random). */
    public int     getSortMode() { return sortMode; }
    /** Returns the sort direction active when the dialog was closed. */
    public boolean isSortAsc()   { return sortAsc;  }

    public void setOnReveal(IntConsumer cb)               { onReveal      = cb; }
    public void setOnRename(IntConsumer cb)               { onRename      = cb; }
    public void setOnBatchDelete(Consumer<List<Integer>> cb) { onBatchDelete = cb; }
    public void setOnBatchRename(Consumer<List<Integer>> cb) { onBatchRename = cb; }
    public void setOnStar(IntConsumer cb)                 { onStar        = cb; }
    public void setOnCopyTo(IntConsumer cb)               { onCopyTo      = cb; }
    public void setOnMoveTo(IntConsumer cb)               { onMoveTo      = cb; }
    public void setOnClearQueue(Runnable cb)              { onClearQueue  = cb; }

    /**
     * Rebuilds the grid to refresh star badges and re-apply any active star filter.
     * MediaFile objects are shared with MainWindow, so star state is already propagated.
     */
    public void refreshStars(List<MediaFile> ignored) {
        Platform.runLater(() -> rebuildGrid(searchField.getText().trim().toLowerCase()));
    }

    /**
     * Called by the host after navigation so the browser highlights the new
     * current file without closing.  Rebuilds the grid (to refresh borders) and
     * scrolls the row into view.
     */
    private Runnable onClose = null;
    public void setOnClose(Runnable cb) { this.onClose = cb; }

    public void setCurrentIdx(int idx) {
        this.currentIdx = idx;
        listView.refresh();
    }

    /** Updates the current item and scrolls it into view only when the index is actually changing
     *  (i.e. navigation came from outside the browser — sidebar, keyboard, etc.).
     *  Clicking a cell inside the browser already shows the cell, so no scroll is needed. */
    public void setCurrentIdxAndScroll(int idx) {
        boolean needsScroll = this.currentIdx != idx;
        this.currentIdx = idx;
        int row = findRowOf(idx);
        if (row >= 0) {
            final int r = row;
            if (needsScroll) {
                Platform.runLater(() -> { listView.refresh(); listView.scrollTo(r); });
            } else {
                Platform.runLater(listView::refresh);
            }
        } else {
            listView.refresh();
        }
    }

    /** Updates queue position badges on all currently-visible cells, and caches
     *  the positions so cells rebuilt on scroll show the correct badge immediately. */
    public void refreshQueueBadges(Map<Integer, Integer> positions) {
        this.queuePositions = new HashMap<>(positions);
        badgeLabels.forEach((idx, label) -> {
            Integer pos = positions.get(idx);
            if (pos != null) {
                label.setText(String.valueOf(pos));
                label.setVisible(true);
            } else {
                label.setVisible(false);
            }
        });
        int n = positions.size();
        if (batchRenameBtn != null) {
            batchRenameBtn.setDisable(n < 2);
            batchRenameBtn.setText("✏  Rename queued (" + n + ")");
        }
        if (cancelQueueBtn != null) {
            cancelQueueBtn.setDisable(n == 0);
        }
    }

    // ── Grid construction ─────────────────────────────────────────────────────

    private void rebuildGrid(String filter) {
        // 1. Collect indices that pass type and name filters
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MediaFile f = files.get(i);
            boolean typeOk = switch (filterMode) {
                case 1  -> f.isImage();
                case 2  -> f.isVideo();
                default -> true;
            };
            boolean nameOk   = filter.isEmpty() || f.getFilename().toLowerCase().contains(filter);
            boolean starOk   = !starFilterActive || f.isStarred();
            if (typeOk && nameOk && starOk) indices.add(i);
        }

        // 2. Sort  (sortMode: 0=Name 1=Date 2=Size 3=Type 4=Ext 5=Random)
        if (sortMode == 5) {
            java.util.Collections.shuffle(indices);
        } else {
            Comparator<Integer> cmp = switch (sortMode) {
                case 1  -> {
                    // Date: sortAsc=true → oldest first (ascending epoch)
                    Comparator<Integer> c = Comparator.<Integer, Long>comparing(
                            i -> files.get(i).getLastModified());
                    yield sortAsc ? c : c.reversed();
                }
                case 2  -> {
                    // Size: sortAsc=true → smallest first
                    Comparator<Integer> c = Comparator.<Integer, Long>comparing(
                            i -> files.get(i).getFileSize());
                    yield sortAsc ? c : c.reversed();
                }
                case 3  -> {
                    Comparator<Integer> c =
                            Comparator.comparingInt(i -> typeOrder(files.get(i).getType()));
                    Comparator<Integer> withName =
                            c.thenComparing(i -> files.get(i).getFilename().toLowerCase());
                    yield sortAsc ? withName : withName.reversed();
                }
                case 4  -> {
                    Comparator<Integer> c =
                            Comparator.<Integer, String>comparing(i -> files.get(i).getExt())
                                      .thenComparing(i -> files.get(i).getFilename().toLowerCase());
                    yield sortAsc ? c : c.reversed();
                }
                default -> {
                    // Name: sortAsc=true → A→Z
                    Comparator<Integer> c = Comparator.comparing(
                            i -> files.get(i).getFilename().toLowerCase());
                    yield sortAsc ? c : c.reversed();
                }
            };
            indices.sort(cmp);
        }

        // 3. Update count label
        countLabel.setText(indices.size() + " file" + (indices.size() == 1 ? "" : "s"));

        // 4. Partition into rows of COLS
        List<int[]> rows = new ArrayList<>();
        List<Integer> buf = new ArrayList<>();
        for (int idx : indices) {
            buf.add(idx);
            if (buf.size() == COLS) {
                rows.add(buf.stream().mapToInt(x -> x).toArray());
                buf.clear();
            }
        }
        if (!buf.isEmpty())
            rows.add(buf.stream().mapToInt(x -> x).toArray());
        listView.getItems().setAll(rows);
    }

    /** Returns the row index in the listView that contains {@code originalIdx}, or -1 if
     *  the file is currently filtered out. Accounts for any sort/filter order. */
    private int findRowOf(int originalIdx) {
        List<int[]> rows = listView.getItems();
        for (int r = 0; r < rows.size(); r++)
            for (int idx : rows.get(r))
                if (idx == originalIdx) return r;
        return -1;
    }

    // ── Virtualized row cell ──────────────────────────────────────────────────

    private class GridRowCell extends ListCell<int[]> {
        private final HBox rowBox = new HBox(6);

        GridRowCell() {
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPadding(new Insets(3, 4, 3, 4));
            setFocusTraversable(false);
            rowBox.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(int[] indices, boolean empty) {
            super.updateItem(indices, empty);
            // Always transparent — selection highlight handled per-cell
            setStyle("-fx-background-color: transparent;");
            rowBox.getChildren().clear();
            if (empty || indices == null) { setGraphic(null); return; }
            for (int idx : indices)
                rowBox.getChildren().add(buildCell(idx));
            setGraphic(rowBox);
        }

        @Override
        public void updateSelected(boolean selected) {
            // Do nothing — suppress ListView row selection visuals entirely
        }
    }

    // ── Cell builder ─────────────────────────────────────────────────────────

    private StackPane buildCell(int idx) {
        MediaFile file = files.get(idx);

        Rectangle bg = new Rectangle(CELL_W, CELL_H, Color.web("#2a2a2a"));

        ImageView thumbView = new ImageView();
        thumbView.setFitWidth(CELL_W);
        thumbView.setFitHeight(CELL_H);
        thumbView.setPreserveRatio(true);
        thumbView.setSmooth(true);

        Label playBadge = new Label("▶");
        playBadge.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.90); -fx-font-size: 32px; " +
                "-fx-background-color: rgba(0,0,0,0.42); " +
                "-fx-background-radius: 50; -fx-padding: 6 12 6 16;");
        playBadge.setMouseTransparent(true);
        playBadge.setVisible(false);

        // Slideshow queue badge — orange numbered pill, top-right corner.
        // Initialised from the cached queuePositions so it survives virtual-scroll rebuilds.
        Label queueBadge = new Label();
        queueBadge.setStyle(
                "-fx-background-color: #f5a623; -fx-text-fill: white; " +
                "-fx-font-size: 11px; -fx-font-weight: bold; " +
                "-fx-padding: 2 7 2 7; -fx-background-radius: 8;");
        queueBadge.setMouseTransparent(true);
        Integer storedPos = queuePositions.get(idx);
        if (storedPos != null) {
            queueBadge.setText(String.valueOf(storedPos));
            queueBadge.setVisible(true);
        } else {
            queueBadge.setVisible(false);
        }
        StackPane.setAlignment(queueBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(queueBadge, new Insets(4, 4, 0, 0));
        badgeLabels.put(idx, queueBadge);
        // Register as the current live ImageView for this index so async callbacks
        // always update the cell that is actually visible, even after cell recycling.
        liveImageViews.put(idx, thumbView);

        // Audio indicator dot — bottom-right corner (green = audio, grey = silent)
        Label audioBadge = new Label();
        audioBadge.setMouseTransparent(true);
        StackPane.setAlignment(audioBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(audioBadge, new Insets(0, 5, 5, 0));
        applyAudioBadge(audioBadge, file.isVideo() ? file.getHasAudio() : null);

        // Star badge — gold ★ at bottom-left when file is starred
        Label starBadge = new Label("★");
        starBadge.setStyle("-fx-text-fill: #ffd700; -fx-font-size: 15px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 2, 0, 0, 1);");
        starBadge.setMouseTransparent(true);
        starBadge.setVisible(file.isStarred());
        StackPane.setAlignment(starBadge, Pos.BOTTOM_LEFT);
        StackPane.setMargin(starBadge, new Insets(0, 0, 5, 6));

        StackPane cell = new StackPane(bg, thumbView, playBadge, queueBadge, audioBadge, starBadge);
        cell.setPrefSize(CELL_W, CELL_H);
        cell.setMaxSize(CELL_W, CELL_H);
        cell.getStyleClass().add("browser-cell");
        applyNormalStyle(cell, idx == currentIdx);

        // Cache hit → set image immediately, no background task needed
        Image cached = thumbCache.get(idx);
        if (cached != null) {
            thumbView.setImage(cached);
        } else if (submitted.add(idx)) {
            // First time seeing this index — submit load task
            loadPool.submit(() -> {
                Image img = loadExistingThumb(file);
                // Detect audio for videos if not already known (e.g. sidebar hasn't loaded it yet)
                if (file.isVideo() && file.getHasAudio() == null) {
                    file.setHasAudio(probeHasAudio(file.getPath()));
                }
                if (img != null) {
                    thumbCache.put(idx, img);
                    Platform.runLater(() -> {
                        ImageView iv = liveImageViews.get(idx);
                        if (iv != null) iv.setImage(img);
                        applyAudioBadge(audioBadge, file.isVideo() ? file.getHasAudio() : null);
                    });
                } else if (file.isVideo()) {
                    Platform.runLater(() -> {
                        playBadge.setVisible(true);
                        applyAudioBadge(audioBadge, file.getHasAudio());
                    });
                    generatePool.submit(() -> {
                        Image generated = generateVideoThumbnail(file);  // also sets hasAudio
                        if (generated != null) {
                            thumbCache.put(idx, generated);
                            Platform.runLater(() -> {
                                ImageView iv = liveImageViews.get(idx);
                                if (iv != null) iv.setImage(generated);
                                playBadge.setVisible(false);
                                applyAudioBadge(audioBadge, file.getHasAudio());
                            });
                        }
                    });
                }
            });
        } else {
            // Load already submitted but not yet cached — show play badge for videos
            if (file.isVideo()) playBadge.setVisible(true);
        }

        cell.setOnMouseEntered(e -> applyHoverStyle(cell));
        cell.setOnMouseExited(e  -> applyNormalStyle(cell, idx == currentIdx));
        cell.setOnContextMenuRequested(e ->
                buildContextMenu(idx, file).show(cell, e.getScreenX(), e.getScreenY()));
        // Use event filter + consume so ListView never sees the click (prevents
        // row-selection flicker and the need to click twice to focus first).
        // Right-click is passed through unconsumed so CONTEXT_MENU_REQUESTED fires.
        cell.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) return;   // let context menu handle it
            if (e.isControlDown() && !e.isShiftDown()) {
                onQueueToggle.accept(idx);
                shiftAnchorIdx = idx;
            } else if (e.isShiftDown() && !e.isAltDown()) {
                // Range-add from anchor to this cell
                int anchor = shiftAnchorIdx >= 0 ? shiftAnchorIdx : idx;
                int lo = Math.min(anchor, idx);
                int hi = Math.max(anchor, idx);
                int[] range = new int[hi - lo + 1];
                for (int i = lo; i <= hi; i++) range[i - lo] = i;
                onQueueRange.accept(range);
            } else if (e.isAltDown()) {
                onAltSelect.accept(idx);
            } else if (e.getClickCount() >= 2) {
                // Double-click — navigate and close the browser.
                // Update currentIdx first so setCurrentIdxAndScroll sees no change and won't scroll.
                currentIdx = idx;
                onNavigate.accept(idx);
                stage.close();
            } else {
                // Single click — navigate but keep browser open.
                // Update currentIdx first so setCurrentIdxAndScroll sees no change and won't scroll.
                shiftAnchorIdx = idx;
                currentIdx = idx;
                onNavigate.accept(idx);
            }
            e.consume();
        });

        return cell;
    }

    private static void applyNormalStyle(StackPane cell, boolean current) {
        String border = current
                ? "-fx-border-color: #4d78cc; -fx-border-width: 2;"
                : "-fx-border-color: transparent; -fx-border-width: 2;";
        cell.setStyle("-fx-cursor: hand; -fx-background-radius: 4; -fx-border-radius: 4; " + border);
    }

    private static void applyHoverStyle(StackPane cell) {
        cell.setStyle(
                "-fx-cursor: hand; -fx-background-radius: 4; -fx-border-radius: 4; " +
                "-fx-border-color: #7aa0ee; -fx-border-width: 2;");
    }

    // ── Thumbnail loading ─────────────────────────────────────────────────────

    /** Loads only from existing sources (sidecar or direct image). Never runs FFmpeg. */
    private Image loadExistingThumb(MediaFile file) {
        // Load at 2× display size for sharp downscaling — avoids decoding huge originals.
        double maxW = CELL_W * 2;
        double maxH = CELL_H * 2;
        Path parent = file.getPath().getParent();
        if (parent != null) {
            String name = file.getFilename();
            int    dot  = name.lastIndexOf('.');
            String stem = dot >= 0 ? name.substring(0, dot) : name;
            Path   tDir = parent.resolve("thumbnails");
            for (String ext : List.of("jpg", "jpeg", "png", "webp")) {
                Path tp = tDir.resolve(stem + "." + ext);
                if (Files.exists(tp)) {
                    try { return new Image(tp.toUri().toString(), maxW, maxH, true, true, false); }
                    catch (Exception ignored) {}
                }
            }
        }
        if (file.isImage()) {
            try {
                Image img = new Image(file.getPath().toUri().toString(), maxW, maxH, true, true, false);
                if (!img.isError() && img.getWidth() > 0) return img;
            } catch (Exception ignored) {}
            // ImageIO fallback — handles CMYK JPEG, TIFF, etc.
            try {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(file.getPath().toFile());
                if (bi != null) {
                    double scale = Math.min(maxW / bi.getWidth(), maxH / bi.getHeight());
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
                }
            } catch (Exception ignored) {}
            // ffmpeg fallback — handles WebP, ICO, and other exotic formats
            return loadThumbViaFfmpeg(file.getPath(), (int) maxW, (int) maxH);
        }
        return null;
    }

    private static Image loadThumbViaFfmpeg(Path path, int maxW, int maxH) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                BundledTools.ffmpeg(), "-y", "-i", path.toAbsolutePath().toString(),
                "-frames:v", "1",
                "-vf", "scale=" + maxW + ":" + maxH + ":force_original_aspect_ratio=decrease",
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
     * Probes duration with ffprobe, seeks to 10%, extracts a frame with ffmpeg,
     * saves as {@code thumbnails/<stem>.jpg}.  Returns null silently on any failure.
     */
    private Image generateVideoThumbnail(MediaFile file) {
        Path parent = file.getPath().getParent();
        if (parent == null) return null;

        String name = file.getFilename();
        int    dot  = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        Path   tDir = parent.resolve("thumbnails");
        Path   out  = tDir.resolve(stem + ".jpg");

        try {
            Files.createDirectories(tDir);

            double seekSecs = 3.0;
            ProcessBuilder probePb = new ProcessBuilder(
                    BundledTools.ffprobe(), "-v", "quiet",
                    "-show_entries", "format=duration:stream=codec_type",
                    "-of", "csv=p=0",
                    file.getPath().toString());
            probePb.redirectErrorStream(true);
            Process probeP = probePb.start();
            String probeOut = new String(probeP.getInputStream().readAllBytes()).trim();
            if (!probeP.waitFor(10, TimeUnit.SECONDS)) probeP.destroyForcibly();
            boolean hasAudioStream = false;
            String  durStr         = "";
            for (String line : probeOut.split("\\r?\\n")) {
                line = line.trim();
                if (line.equals("audio"))       hasAudioStream = true;
                else if (!line.isEmpty() && !line.equals("video")) durStr = line;
            }
            file.setHasAudio(hasAudioStream);
            if (!durStr.isEmpty()) {
                try { seekSecs = Math.max(0, Double.parseDouble(durStr) * 0.10); }
                catch (NumberFormatException ignored) {}
            }

            ProcessBuilder pb = new ProcessBuilder(
                    BundledTools.ffmpeg(), "-y", "-loglevel", "quiet",
                    "-ss", String.format("%.3f", seekSecs),
                    "-i", file.getPath().toString(),
                    "-vframes", "1",
                    "-vf", "scale=640:-1",
                    out.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(30, TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();

            if (ok && p.exitValue() == 0 && Files.exists(out)) {
                return new Image(out.toUri().toString(), CELL_W * 2, CELL_H * 2, true, true, false);
            }
        } catch (Exception e) {
            // ffprobe/ffmpeg not available or failed
        }
        return null;
    }

    /** Quick FFprobe check — true if the file contains at least one audio stream. */
    private static boolean probeHasAudio(Path filePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    BundledTools.ffprobe(), "-v", "quiet",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=codec_type",
                    "-of", "csv=p=0",
                    filePath.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            return out.contains("audio");
        } catch (Exception ignored) { return false; }
    }

    // ── Title bar (matches Manage Queue / Settings style) ─────────────────────

    private HBox buildTitleBar(Stage s) {
        HBox bar = new HBox(6);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 0, 5, 10));
        bar.setStyle("-fx-background-color: #1a1a1a; " +
                "-fx-border-color: transparent transparent #333 transparent;");

        ImageView icon = new ImageView(buildBrowserIcon());
        icon.setFitWidth(16); icon.setFitHeight(16); icon.setPreserveRatio(true);
        bar.getChildren().add(icon);

        Label lbl = new Label("Thumbnail Browser");
        lbl.setStyle("-fx-text-fill: #cccccc; -fx-font-weight: bold; -fx-font-size: 12px;");
        lbl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lbl, Priority.ALWAYS);
        bar.getChildren().add(lbl);

        String dflt  = "-fx-background-color: transparent; -fx-text-fill: #888; " +
                "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 10 2 10;";
        String hover = "-fx-background-color: #c42b1c; -fx-text-fill: white; " +
                "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 10 2 10;";
        Button closeBtn = new Button("✕");
        closeBtn.setStyle(dflt);
        closeBtn.setFocusTraversable(false);
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(hover));
        closeBtn.setOnMouseExited(e  -> closeBtn.setStyle(dflt));
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

    /** 2×2 thumbnail-grid icon — same colour as the queue icon (#9eb8d4). */
    private static Image buildBrowserIcon() {
        Canvas c = new Canvas(16, 16);
        javafx.scene.canvas.GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#9eb8d4"));
        g.fillRoundRect(1, 1, 6, 6, 1, 1);
        g.fillRoundRect(9, 1, 6, 6, 1, 1);
        g.fillRoundRect(1, 9, 6, 6, 1, 1);
        g.fillRoundRect(9, 9, 6, 6, 1, 1);
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    /** Type sort order: IMAGE → GIF → RAW → VIDEO → other. */
    private static int typeOrder(MediaFile.Type t) {
        return switch (t) {
            case IMAGE -> 0;
            case GIF   -> 1;
            case RAW   -> 2;
            case VIDEO -> 3;
            default    -> 4;
        };
    }

    /**
     * Creates a sort button for the given mode.
     * @param label       base label text (e.g. "Name")
     * @param mode        sortMode value this button represents
     * @param defaultAsc  true if first click should sort ascending
     */
    private Button makeSortBtn(String label, int mode, boolean defaultAsc) {
        Button btn = new Button(label);
        btn.setFocusTraversable(false);
        btn.getStyleClass().add("toolbar-btn");
        btn.setStyle("-fx-font-size: 10px; -fx-padding: 3 10 3 10;");
        btn.setOnAction(e -> {
            if (sortMode == mode && mode != 5) {
                sortAsc = !sortAsc;          // same button → flip direction
            } else {
                sortMode = mode;
                sortAsc  = defaultAsc;       // new button → reset to default direction
            }
            updateSortButtons();
            rebuildGrid(searchField.getText().trim().toLowerCase());
        });
        return btn;
    }

    /** Relabels all sort buttons to reflect the current sortMode + sortAsc state. */
    private void updateSortButtons() {
        String[] base = {"Name", "Date", "Size", "Type", "Ext", "Random"};
        for (int i = 0; i < sortBtns.length; i++) {
            if (sortBtns[i] == null) continue;
            boolean active = (i == sortMode);
            String  text   = base[i];
            if (active && i != 5)            // Random has no direction arrow
                text += sortAsc ? " ↑" : " ↓";
            sortBtns[i].setText(text);
            if (active) {
                sortBtns[i].setStyle(
                        "-fx-font-size: 10px; -fx-padding: 3 10 3 10; " +
                        "-fx-background-color: rgba(77,120,204,0.30); " +
                        "-fx-text-fill: #7aa0ee;");
            } else {
                sortBtns[i].setStyle("-fx-font-size: 10px; -fx-padding: 3 10 3 10;");
            }
        }
    }

    private static ToggleButton filterBtn(String text) {
        ToggleButton btn = new ToggleButton(text);
        btn.setFocusTraversable(false);
        btn.setStyle("-fx-font-size: 10px; -fx-padding: 2 6 2 6;");
        btn.getStyleClass().add("toolbar-btn");
        return btn;
    }

    /** Applies the correct colour to an audio-indicator dot label. */
    private static void applyAudioBadge(Label badge, Boolean hasAudio) {
        if (hasAudio == null) { badge.setVisible(false); return; }
        String color = hasAudio ? "#4caf50" : "#888888";
        badge.setStyle(
                "-fx-min-width: 9px; -fx-min-height: 9px; " +
                "-fx-max-width: 9px; -fx-max-height: 9px; " +
                "-fx-background-radius: 50; -fx-background-color: " + color + ";");
        badge.setVisible(true);
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private ContextMenu buildContextMenu(int idx, MediaFile file) {
        ContextMenu cm = new ContextMenu();

        // Batch operations (shown when 2+ files are queued)
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

        MenuItem revealItem = new MenuItem("📂  Reveal in Explorer");
        revealItem.setOnAction(e -> {
            onReveal.accept(idx);
            revealInExplorer(file.getPath());
        });

        MenuItem renameItem = new MenuItem("✏  Rename…");
        renameItem.setOnAction(e -> onRename.accept(idx));

        MenuItem starItem = new MenuItem(file.isStarred() ? "☆  Unstar" : "⭐  Star");
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

    private static void revealInExplorer(Path path) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
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

}

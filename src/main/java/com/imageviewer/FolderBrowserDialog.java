package com.imageviewer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Grid-style folder-browser dialog — supports multi-folder selection.
 *
 * Layout
 * ──────
 *  ┌────────────────────────────────────────────────────────────────────┐
 *  │  [◀]  C:\Users\Public                                        [▲]  │ ← address bar
 *  ├──────────────────┬─────────────────────────────────────────────────┤
 *  │  Drives          │  🔍 Search folders…                  [↕ A–Z]   │
 *  │   ● C:\          ├─────────────────────────────────────────────────┤
 *  │   ○ D:\          │  📁 .cache  📁 Users  📁 Windows  📁 …         │
 *  │                  │                                                  │
 *  │  Favorites       │                                                  │
 *  │   Pictures       │                                                  │
 *  │  Recent          │                                                  │
 *  │   Videos         │                                                  │
 *  │ [⭐ Add Favorite] │                                                  │
 *  ├──────────────────┴─────────────────────────────────────────────────┤
 *  │  Double-click to open  •  Click / Ctrl+click / Shift+click         │
 *  └────────────────────────────────────────────────────────────────────┘
 *                                                      [OK]   [Cancel]
 *
 * Returns the list of selected {@link Path}s (null if cancelled).
 * If OK is pressed with nothing selected, returns a list containing the current folder.
 * Exposes {@link #getFavorites()} so the caller can persist changes.
 */
public class FolderBrowserDialog {

    // ── Grid item dimensions ─────────────────────────────────────────────────
    private static final int ITEM_W = 102;
    private static final int ITEM_H =  84;

    // ── Highlight styles ─────────────────────────────────────────────────────
    private static final String S_NORMAL =
            "-fx-background-color: transparent; -fx-background-radius: 5; -fx-cursor: hand;";
    private static final String S_HOVER  =
            "-fx-background-color: rgba(100,130,210,0.22); -fx-background-radius: 5; -fx-cursor: hand;";
    private static final String S_SEL    =
            "-fx-background-color: rgba(77,120,204,0.50); -fx-background-radius: 5; -fx-cursor: hand;";

    // ── Navigation state ─────────────────────────────────────────────────────
    private Path              currentFolder;
    private final Deque<Path> history = new ArrayDeque<>();

    // ── Data ─────────────────────────────────────────────────────────────────
    private final List<String> favorites;
    private final List<String> recents;
    private List<Path>         currentSubfolders = new ArrayList<>();
    private boolean            sortAscending     = true;

    // ── Multi-select state ───────────────────────────────────────────────────
    /** Currently selected cells (for multi-select). cell → path */
    private final Map<VBox, Path> selectedCells = new LinkedHashMap<>();
    /** The most recently clicked cell (anchor for Shift+click) */
    private VBox   anchorCell = null;
    private Path   anchorPath = null;
    /** All cells in current grid, in display order */
    private final List<VBox>  gridCells = new ArrayList<>();
    private final List<Path>  gridPaths = new ArrayList<>();

    // ── UI ────────────────────────────────────────────────────────────────────
    private final TextField          addressField;
    private final TextField          searchField;
    private final Button             backBtn;
    private final Button             upBtn;
    private final Button             sortBtn;
    private final Button             addFavBtn;
    private final FlowPane           folderGrid;
    private final ListView<DriveEntry> driveList;
    private final ListView<QuickItem>  quickList;
    private final Label              statusLabel;
    /** Lazy folder tree — alternative to the grid view. */
    private TreeView<Path>           treeView;
    private boolean                  treeMode = false;
    private ScrollPane               gridScroll;
    private ScrollPane               treeScroll;

    // ── Window / result ───────────────────────────────────────────────────────
    private final Stage       dialogStage;
    private       List<Path>  result = null;

    // ── Background loader ─────────────────────────────────────────────────────
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "folder-loader");
        t.setDaemon(true);
        return t;
    });

    // ── Drive model ───────────────────────────────────────────────────────────
    private record DriveEntry(String label, File root) {}

    /** A row in the Quick Access sidebar — either a header or a path entry. */
    private record QuickItem(String path, String header) {
        static QuickItem header(String title) { return new QuickItem(null, title); }
        static QuickItem entry(String path)   { return new QuickItem(path, null); }
        boolean isHeader() { return header != null; }
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    public FolderBrowserDialog(Stage owner, AppConfig config) {
        this.favorites = new ArrayList<>(config.favoriteFolders);
        this.recents   = new ArrayList<>(config.recentFolders);

        dialogStage = new Stage(StageStyle.UNDECORATED);
        dialogStage.initOwner(owner);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.getIcons().addAll(owner.getIcons());

        // ── Address bar ──────────────────────────────────────────────────────
        addressField = new TextField();
        addressField.setPromptText("Type a path and press Enter…");
        HBox.setHgrow(addressField, Priority.ALWAYS);
        addressField.setOnAction(e -> navigateToText(addressField.getText()));

        backBtn = new Button("◀");
        backBtn.getStyleClass().add("toolbar-btn");
        backBtn.setTooltip(new Tooltip("Go back"));
        backBtn.setOnAction(e -> navigateBack());
        backBtn.setDisable(true);

        upBtn = new Button("▲");
        upBtn.getStyleClass().add("toolbar-btn");
        upBtn.setTooltip(new Tooltip("Go up one level  (Backspace)"));
        upBtn.setOnAction(e -> navigateUp());

        HBox addressBar = new HBox(5, backBtn, addressField, upBtn);
        addressBar.setAlignment(Pos.CENTER_LEFT);
        addressBar.setPadding(new Insets(7, 8, 7, 8));
        addressBar.setStyle("-fx-border-color: transparent transparent #444 transparent;");

        // ── Search + sort row ─────────────────────────────────────────────────
        searchField = new TextField();
        searchField.setPromptText("🔍  Search folders…");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, o, n) -> applyFilter());

        sortBtn = new Button("↕ A–Z");
        sortBtn.getStyleClass().add("toolbar-btn");
        sortBtn.setOnAction(e -> toggleSort());

        Button treeToggleBtn = new Button("⊟ Tree");
        treeToggleBtn.getStyleClass().add("toolbar-btn");
        treeToggleBtn.setTooltip(new Tooltip("Toggle folder tree view"));

        HBox searchBar = new HBox(6, searchField, sortBtn, treeToggleBtn);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(5, 8, 4, 8));
        searchBar.setStyle("-fx-border-color: transparent transparent #3a3a3a transparent;");

        // ── Folder grid ───────────────────────────────────────────────────────
        folderGrid = new FlowPane();
        folderGrid.setHgap(4);
        folderGrid.setVgap(4);
        folderGrid.setPadding(new Insets(8));

        gridScroll = new ScrollPane(folderGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        // ── Folder tree (lazy, built on first toggle) ─────────────────────────
        treeView = buildFolderTree();
        treeScroll = new ScrollPane(treeView);
        treeScroll.setFitToWidth(true);
        treeScroll.setFitToHeight(true);
        treeScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        treeScroll.setVisible(false);
        treeScroll.setManaged(false);   // exclude from layout until tree mode is activated
        VBox.setVgrow(treeScroll, Priority.ALWAYS);

        treeToggleBtn.setOnAction(e -> {
            treeMode = !treeMode;
            gridScroll.setVisible(!treeMode);
            gridScroll.setManaged(!treeMode);
            treeScroll.setVisible(treeMode);
            treeScroll.setManaged(treeMode);
            searchField.setVisible(!treeMode);
            searchField.setManaged(!treeMode);
            sortBtn.setVisible(!treeMode);
            sortBtn.setManaged(!treeMode);
            treeToggleBtn.setText(treeMode ? "⊟ Grid" : "⊟ Tree");
            if (treeMode) refreshTreeRoot();
        });

        VBox rightPanel = new VBox(0, searchBar, gridScroll, treeScroll);
        VBox.setVgrow(rightPanel, Priority.ALWAYS);

        // ── Drive list ────────────────────────────────────────────────────────
        driveList = new ListView<>();
        driveList.setCellFactory(lv -> new DriveCell());
        driveList.setPrefHeight(140);
        driveList.setMaxHeight(190);
        loadDrives();
        driveList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) {
                driveList.getSelectionModel().clearSelection();
                navigateTo(n.root().toPath());
            }
        });

        // ── Quick access list ─────────────────────────────────────────────────
        quickList = new ListView<>();
        quickList.setCellFactory(lv -> new QuickCell());
        VBox.setVgrow(quickList, Priority.ALWAYS);
        buildQuickList();
        quickList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && !n.isHeader()) {
                quickList.getSelectionModel().clearSelection();
                Path p = Path.of(n.path());
                if (Files.isDirectory(p)) navigateTo(p);
            }
        });

        // ── Add-to-favorites button ───────────────────────────────────────────
        addFavBtn = new Button("⭐  Add to Favorites");
        addFavBtn.getStyleClass().add("toolbar-btn");
        addFavBtn.setMaxWidth(Double.MAX_VALUE);
        addFavBtn.setPadding(new Insets(7, 10, 7, 10));
        addFavBtn.setOnAction(e -> toggleFavorite());

        // ── Left panel ────────────────────────────────────────────────────────
        VBox leftPanel = new VBox(0,
                sideHeader("Drives"),    driveList,
                quickList,
                addFavBtn);
        leftPanel.getStyleClass().add("thumbnail-panel");
        leftPanel.setPrefWidth(175);
        leftPanel.setMinWidth(140);

        // ── Split ─────────────────────────────────────────────────────────────
        SplitPane split = new SplitPane(leftPanel, rightPanel);
        split.setDividerPositions(0.215);
        SplitPane.setResizableWithParent(leftPanel, false);
        VBox.setVgrow(split, Priority.ALWAYS);

        // ── Status bar ────────────────────────────────────────────────────────
        statusLabel = new Label(
                "Double-click to open  •  Click to select  •  Ctrl+click = add/remove  •  Shift+click = range  •  Backspace = up");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-padding: 4 8 4 8; -fx-text-fill: #777;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        // ── Custom title bar (replaces OS white bar; stage is set UNDECORATED below) ──
        ImageView iconView = new ImageView(buildFolderTitleIcon());
        iconView.setFitWidth(16);
        iconView.setFitHeight(16);
        iconView.setPreserveRatio(true);
        Label titleLbl = new Label("Select Folder");
        titleLbl.setStyle("-fx-text-fill: #cccccc; -fx-font-weight: bold; -fx-font-size: 12px;");
        titleLbl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLbl, Priority.ALWAYS);

        Button closeTitleBtn = new Button("✕");
        String closeDflt = "-fx-background-color: transparent; -fx-text-fill: #888; "
                + "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 10 2 10;";
        String closeHover = "-fx-background-color: #c42b1c; -fx-text-fill: white; "
                + "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 10 2 10;";
        closeTitleBtn.setStyle(closeDflt);
        closeTitleBtn.setOnMouseEntered(e -> closeTitleBtn.setStyle(closeHover));
        closeTitleBtn.setOnMouseExited(e  -> closeTitleBtn.setStyle(closeDflt));
        closeTitleBtn.setOnAction(e -> dialogStage.close());

        HBox titleBar = new HBox(6, iconView, titleLbl, closeTitleBtn);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(5, 0, 5, 10));
        titleBar.setStyle("-fx-background-color: #1a1a1a; "
                + "-fx-border-color: transparent transparent #333 transparent;");

        // Drag-to-move from the title bar
        double[] dragRef = {0, 0};
        titleBar.setOnMousePressed(e -> { dragRef[0] = e.getScreenX(); dragRef[1] = e.getScreenY(); });
        titleBar.setOnMouseDragged(e -> {
            dialogStage.setX(dialogStage.getX() + e.getScreenX() - dragRef[0]);
            dialogStage.setY(dialogStage.getY() + e.getScreenY() - dragRef[1]);
            dragRef[0] = e.getScreenX();
            dragRef[1] = e.getScreenY();
        });

        // ── OK / Cancel buttons ───────────────────────────────────────────────
        Button okBtn = new Button("OK");
        okBtn.getStyleClass().add("toolbar-btn");
        okBtn.setDefaultButton(true);
        okBtn.setOnAction(e -> {
            result = !selectedCells.isEmpty()
                    ? new ArrayList<>(selectedCells.values())
                    : currentFolder != null ? List.of(currentFolder) : null;
            dialogStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("toolbar-btn");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dialogStage.close());

        HBox buttonBar = new HBox(8, okBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(8, 12, 8, 12));
        buttonBar.setStyle("-fx-border-color: #333 transparent transparent transparent;");

        // ── Scene / stage ─────────────────────────────────────────────────────
        VBox root = new VBox(0, titleBar, addressBar, split, statusLabel, buttonBar);
        VBox.setVgrow(split, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1e1e1e;");

        Scene scene = new Scene(root, 870, 560);
        if (owner.getScene() != null)
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.BACK_SPACE && !addressField.isFocused()) {
                navigateUp(); e.consume();
            } else if (e.getCode() == KeyCode.ENTER && anchorPath != null
                    && !addressField.isFocused()) {
                navigateTo(anchorPath); e.consume();
            }
        });

        // Route scroll wheel anywhere in the dialog to the active scroll pane.
        // Without this, scrolling only works when the mouse is directly over the
        // scrollbar thumb — hovering over folder cells or empty space does nothing.
        scene.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            javafx.scene.Node target = (javafx.scene.Node) e.getTarget();
            // If the event is already headed for a native scroll container, let it go.
            if (isWithin(target, quickList) || isWithin(target, driveList)) return;
            // Route to whichever panel is currently active.
            scrollPaneBy(treeMode ? treeScroll : gridScroll, e.getDeltaY());
            e.consume();
        });

        dialogStage.setScene(scene);
        dialogStage.setMinHeight(450);
        dialogStage.setMinWidth(500);
        dialogStage.setOnHidden(ev -> loader.shutdownNow());

        // ── Initial navigation ────────────────────────────────────────────────
        Path start = config.lastFolder != null
                ? Path.of(config.lastFolder) : Path.of(System.getProperty("user.home"));
        if (!Files.isDirectory(start)) start = Path.of(System.getProperty("user.home"));
        navigateTo(start);
    }

    public Optional<List<Path>> showAndWait() {
        dialogStage.setOnShown(e -> {
            Window owner = dialogStage.getOwner();
            if (owner != null) {
                dialogStage.setX(owner.getX() + (owner.getWidth()  - dialogStage.getWidth())  / 2);
                dialogStage.setY(owner.getY() + (owner.getHeight() - dialogStage.getHeight()) / 2);
            }
        });
        dialogStage.showAndWait();
        return Optional.ofNullable(result);
    }

    public List<String> getFavorites() { return favorites; }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateTo(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return;
        if (currentFolder != null && !currentFolder.equals(folder))
            history.push(currentFolder);
        currentFolder = folder;
        clearAllSel();
        backBtn.setDisable(history.isEmpty());
        addressField.setText(folder.toString());
        updateFavBtn();
        searchField.clear();
        loadFolderAsync();
        driveList.refresh();
    }

    private void navigateBack() {
        if (!history.isEmpty()) {
            currentFolder = history.pop();
            clearAllSel();
            backBtn.setDisable(history.isEmpty());
            addressField.setText(currentFolder.toString());
            updateFavBtn();
            searchField.clear();
            loadFolderAsync();
            driveList.refresh();
        }
    }

    private void navigateUp() {
        if (currentFolder != null && currentFolder.getParent() != null)
            navigateTo(currentFolder.getParent());
    }

    private void navigateToText(String text) {
        if (text == null || text.isBlank()) return;
        try {
            Path p = Path.of(text.trim());
            if (Files.isDirectory(p)) navigateTo(p);
            else statusLabel.setText("⚠  Not a valid folder: " + text.trim());
        } catch (Exception ignored) {
            statusLabel.setText("⚠  Invalid path");
        }
    }

    // ── Async folder loading ──────────────────────────────────────────────────

    private void loadFolderAsync() {
        final Path folder = currentFolder;
        folderGrid.getChildren().clear();
        Label loading = new Label("Loading…");
        loading.setStyle("-fx-text-fill: #555; -fx-font-size: 12px; -fx-padding: 20;");
        folderGrid.getChildren().add(loading);

        loader.submit(() -> {
            List<Path> dirs = new ArrayList<>();
            try (var stream = Files.list(folder)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> {
                          String n = p.getFileName() != null ? p.getFileName().toString() : "";
                          return !n.equals("$Recycle.Bin") && !n.equals("System Volume Information");
                      })
                      .sorted(Comparator.comparing(
                              p -> p.getFileName() != null ? p.getFileName().toString() : "",
                              String.CASE_INSENSITIVE_ORDER))
                      .forEach(dirs::add);
            } catch (IOException ignored) {}

            Platform.runLater(() -> {
                if (!folder.equals(currentFolder)) return; // stale
                currentSubfolders = dirs;
                applyFilter();
                int n = dirs.size();
                statusLabel.setText(String.format(
                        "📁  %d folder%s  •  Click to select  •  Ctrl+click = add/remove  •  Shift+click = range  •  Double-click to open  •  Backspace = up",
                        n, n == 1 ? "" : "s"));
            });
        });
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().toLowerCase().trim();
        List<Path> filtered = currentSubfolders.stream()
                .filter(p -> {
                    String n = p.getFileName() != null ? p.getFileName().toString() : "";
                    return q.isEmpty() || n.toLowerCase().contains(q);
                })
                .collect(Collectors.toList());
        if (!sortAscending) Collections.reverse(filtered);
        buildGrid(filtered);
    }

    private void toggleSort() {
        sortAscending = !sortAscending;
        sortBtn.setText(sortAscending ? "↕ A–Z" : "↕ Z–A");
        applyFilter();
    }

    private void buildGrid(List<Path> folders) {
        folderGrid.getChildren().clear();
        gridCells.clear();
        gridPaths.clear();
        if (folders.isEmpty()) {
            Label empty = new Label("No folders here");
            empty.setStyle("-fx-text-fill: #555; -fx-font-size: 12px; -fx-padding: 20;");
            folderGrid.getChildren().add(empty);
            return;
        }
        for (Path p : folders) {
            VBox cell = makeFolderCell(p);
            gridCells.add(cell);
            gridPaths.add(p);
            folderGrid.getChildren().add(cell);
        }
        // Re-apply selection highlights for cells that are still in selectedCells
        Set<Path> selPaths = new HashSet<>(selectedCells.values());
        for (int i = 0; i < gridCells.size(); i++) {
            if (selPaths.contains(gridPaths.get(i))) {
                gridCells.get(i).setStyle(S_SEL);
            }
        }
    }

    // ── Folder grid cell ──────────────────────────────────────────────────────

    private VBox makeFolderCell(Path folder) {
        String name = folder.getFileName() != null ? folder.getFileName().toString() : folder.toString();

        Pane icon = makeFolderIcon();

        Label lbl = new Label(name);
        lbl.setMaxWidth(ITEM_W - 8);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-overrun: ELLIPSIS;");
        lbl.setWrapText(false);
        lbl.setMouseTransparent(true);
        if (name.length() > 14) lbl.setTooltip(new Tooltip(name));

        VBox cell = new VBox(5, icon, lbl);
        cell.setAlignment(Pos.CENTER);
        cell.setPrefWidth(ITEM_W);
        cell.setMinWidth(ITEM_W);
        cell.setPrefHeight(ITEM_H);
        cell.setPadding(new Insets(6, 4, 6, 4));
        cell.setStyle(S_NORMAL);

        cell.setOnMouseEntered(e -> { if (!selectedCells.containsKey(cell)) cell.setStyle(S_HOVER); });
        cell.setOnMouseExited(e  -> { if (!selectedCells.containsKey(cell)) cell.setStyle(S_NORMAL); });
        cell.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                if (e.getClickCount() == 2) {
                    navigateTo(folder);
                } else if (e.isControlDown()) {
                    // Ctrl+click: toggle this cell in/out of selection
                    toggleCellSel(cell, folder);
                    anchorCell = cell;
                    anchorPath = folder;
                    updateAddressForSel();
                } else if (e.isShiftDown() && anchorCell != null) {
                    // Shift+click: range select from anchor to this cell
                    rangeSelect(cell);
                    updateAddressForSel();
                } else {
                    // Plain click: select only this cell
                    clearAllSel();
                    addCellSel(cell, folder);
                    anchorCell = cell;
                    anchorPath = folder;
                    addressField.setText(folder.toString());
                    updateFavBtn();
                }
                e.consume();
            }
        });
        return cell;
    }

    /** Amber folder icon (16×16) drawn for the dialog title bar. */
    private static WritableImage buildFolderTitleIcon() {
        Canvas c = new Canvas(16, 16);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#f5c842"));
        g.fillRoundRect(0, 3, 7, 4, 2, 2);           // tab
        g.fillRoundRect(0, 6, 15, 9, 2, 2);           // body
        g.setFill(Color.web("#ffe090"));
        g.fillRoundRect(1, 7, 13, 2, 1, 1);           // highlight stripe
        WritableImage img = new WritableImage(16, 16);
        c.snapshot(null, img);
        return img;
    }

    /** Blue folder shape: rounded rectangle body + smaller rounded tab at top-left. */
    private static Pane makeFolderIcon() {
        final int W = 48, H = 38;
        Rectangle body = new Rectangle(0, H * 0.22, W, H * 0.78);
        body.setArcWidth(4); body.setArcHeight(4);
        body.setFill(Color.web("#4d78cc"));
        Rectangle tab = new Rectangle(0, 0, W * 0.44, H * 0.32);
        tab.setArcWidth(3); tab.setArcHeight(3);
        tab.setFill(Color.web("#6a90e4"));
        Pane p = new Pane(body, tab);
        p.setPrefSize(W, H); p.setMinSize(W, H); p.setMaxSize(W, H);
        p.setMouseTransparent(true);
        return p;
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

    private void addCellSel(VBox cell, Path path) {
        selectedCells.put(cell, path);
        cell.setStyle(S_SEL);
    }

    private void toggleCellSel(VBox cell, Path path) {
        if (selectedCells.containsKey(cell)) {
            selectedCells.remove(cell);
            cell.setStyle(S_NORMAL);
        } else {
            addCellSel(cell, path);
        }
    }

    private void rangeSelect(VBox toCell) {
        int fromIdx = gridCells.indexOf(anchorCell);
        int toIdx   = gridCells.indexOf(toCell);
        if (fromIdx < 0 || toIdx < 0) return;
        int lo = Math.min(fromIdx, toIdx);
        int hi = Math.max(fromIdx, toIdx);
        VBox savedAnchor = anchorCell;
        Path savedAnchorPath = anchorPath;
        clearAllSel();
        for (int i = lo; i <= hi; i++) {
            addCellSel(gridCells.get(i), gridPaths.get(i));
        }
        // Restore anchor so it stays the pivot for future shift+clicks
        anchorCell = savedAnchor;
        anchorPath = savedAnchorPath;
    }

    private void clearAllSel() {
        for (VBox c : selectedCells.keySet()) c.setStyle(S_NORMAL);
        selectedCells.clear();
        anchorCell = null;
        anchorPath = null;
    }

    private void updateAddressForSel() {
        if (selectedCells.isEmpty()) {
            addressField.setText(currentFolder != null ? currentFolder.toString() : "");
        } else if (selectedCells.size() == 1) {
            addressField.setText(selectedCells.values().iterator().next().toString());
        } else {
            addressField.setText(selectedCells.size() + " folders selected");
        }
        updateFavBtn();
    }

    // ── Drives ────────────────────────────────────────────────────────────────

    private void loadDrives() {
        // Collect first, then setAll() atomically — avoids a transient empty-list state
        // that triggers IndexOutOfBoundsException in JavaFX 21's ListViewBehavior when
        // a click arrives between clear() and the subsequent add() calls.
        List<DriveEntry> drives = new java.util.ArrayList<>();
        for (File root : File.listRoots())
            drives.add(new DriveEntry(root.toString(), root));
        driveList.getItems().setAll(drives);
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    private void toggleFavorite() {
        Path target = selectedCells.size() == 1
                ? selectedCells.values().iterator().next()
                : currentFolder;
        if (target == null) return;
        String s = target.toString();
        if (favorites.contains(s)) favorites.remove(s);
        else favorites.add(0, s);
        rebuildQuickList();
        updateFavBtn();
    }

    private void updateFavBtn() {
        Path t = selectedCells.size() == 1
                ? selectedCells.values().iterator().next()
                : currentFolder;
        addFavBtn.setText(t != null && favorites.contains(t.toString())
                ? "★  Remove from Favorites" : "⭐  Add to Favorites");
    }

    private void buildQuickList() {
        List<QuickItem> items = new ArrayList<>();
        if (!favorites.isEmpty()) {
            items.add(QuickItem.header("Favorites"));
            for (String f : favorites) items.add(QuickItem.entry(f));
        }
        if (!recents.isEmpty()) {
            items.add(QuickItem.header("Recent"));
            for (String r : recents) items.add(QuickItem.entry(r));
        }
        // Clear selection first — avoids IndexOutOfBoundsException in JavaFX 21's
        // ListViewBehavior when setAll() fires a change event against a 0-size list.
        quickList.getSelectionModel().clearSelection();
        quickList.getItems().setAll(items);
    }

    private void rebuildQuickList() {
        // Preserve all recents from current list (all non-header entries that aren't favorites).
        // Clear selection first to avoid IndexOutOfBoundsException in JavaFX 21 ListViewBehavior.
        quickList.getSelectionModel().clearSelection();
        List<String> currentRecents = quickList.getItems().stream()
                .filter(qi -> !qi.isHeader())
                .map(QuickItem::path)
                .filter(p -> !favorites.contains(p))
                .collect(Collectors.toList());
        List<QuickItem> items = new ArrayList<>();
        if (!favorites.isEmpty()) {
            items.add(QuickItem.header("Favorites"));
            for (String f : favorites) items.add(QuickItem.entry(f));
        }
        if (!currentRecents.isEmpty()) {
            items.add(QuickItem.header("Recent"));
            for (String r : currentRecents) items.add(QuickItem.entry(r));
        }
        quickList.getItems().setAll(items);
    }

    // ── Scroll helpers ────────────────────────────────────────────────────────

    /** Scroll a ScrollPane by {@code deltaY} pixels (positive = scroll up). */
    private static void scrollPaneBy(ScrollPane sp, double deltaY) {
        if (sp == null || !sp.isVisible()) return;
        javafx.scene.Node content = sp.getContent();
        if (content == null) return;
        double contentH  = content.getBoundsInLocal().getHeight();
        double viewportH = sp.getViewportBounds().getHeight();
        double range     = contentH - viewportH;
        if (range <= 0) return;
        double step = deltaY / range;
        sp.setVvalue(Math.max(0.0, Math.min(1.0, sp.getVvalue() - step)));
    }

    /** Returns true if {@code node} is {@code ancestor} or a descendant of it. */
    private static boolean isWithin(javafx.scene.Node node, javafx.scene.Node ancestor) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent())
            if (n == ancestor) return true;
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Label sideHeader(String text) {
        Label l = new Label(text);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 3 10; -fx-font-size: 11px; -fx-text-fill: #888;");
        return l;
    }

    // ── Folder tree helpers ───────────────────────────────────────────────────

    /** Build the TreeView with a virtual root and lazy child loading. */
    private TreeView<Path> buildFolderTree() {
        TreeItem<Path> root = new TreeItem<>(Path.of(""));
        root.setExpanded(true);
        TreeView<Path> tv = new TreeView<>(root);
        tv.setShowRoot(false);
        tv.setStyle("-fx-background-color: transparent;");

        // Custom cell: show folder name + a small folder icon
        tv.setCellFactory(v -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                String name = item.getFileName() != null ? item.getFileName().toString() : item.toString();
                setText(name);
                // Reuse the small folder icon from makeFolderIcon() but scaled down
                javafx.scene.shape.Rectangle icon = new javafx.scene.shape.Rectangle(12, 9);
                icon.setArcWidth(2); icon.setArcHeight(2);
                icon.setFill(javafx.scene.paint.Color.web("#4d78cc"));
                setGraphic(icon);
                setCursor(Cursor.HAND);
            }
        });

        // Single click → navigate grid to that folder
        tv.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && n.getValue() != null && !n.getValue().toString().isEmpty()) {
                Path p = n.getValue();
                if (Files.isDirectory(p)) {
                    currentFolder = p;
                    addressField.setText(p.toString());
                    updateFavBtn();
                    clearAllSel();
                    // Also update the grid in background (for when switching back to grid mode)
                    loadFolderAsync();
                }
            }
        });

        // Double click → confirm selection
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                TreeItem<Path> sel = tv.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() != null) {
                    result = List.of(sel.getValue());
                    dialogStage.close();
                }
            }
        });

        return tv;
    }

    /** Populate (or refresh) the tree root from the current drives. */
    private void refreshTreeRoot() {
        TreeItem<Path> root = (TreeItem<Path>) treeView.getRoot();
        root.getChildren().clear();
        for (File drive : File.listRoots()) {
            Path drivePath = drive.toPath();
            TreeItem<Path> driveItem = lazyTreeItem(drivePath);
            root.getChildren().add(driveItem);
        }
    }

    /** Create a lazy-loading TreeItem — children are populated on first expand. */
    private TreeItem<Path> lazyTreeItem(Path folder) {
        TreeItem<Path> item = new TreeItem<>(folder);
        // Placeholder child makes the expand arrow appear
        item.getChildren().add(new TreeItem<>(null));

        item.expandedProperty().addListener((obs, wasExp, isExp) -> {
            if (!isExp) return;
            // Replace placeholder with real children
            if (item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null) {
                item.getChildren().clear();
                loader.submit(() -> {
                    List<Path> subdirs = new ArrayList<>();
                    try (var stream = Files.list(folder)) {
                        stream.filter(Files::isDirectory)
                              .filter(p -> {
                                  String n = p.getFileName() != null ? p.getFileName().toString() : "";
                                  return !n.startsWith("$") && !n.equals("System Volume Information");
                              })
                              .sorted(Comparator.comparing(
                                      p -> p.getFileName() != null ? p.getFileName().toString() : "",
                                      String.CASE_INSENSITIVE_ORDER))
                              .forEach(subdirs::add);
                    } catch (IOException ignored) {}
                    Platform.runLater(() -> {
                        for (Path sub : subdirs) item.getChildren().add(lazyTreeItem(sub));
                        if (subdirs.isEmpty()) {
                            // Remove expand arrow for leaf nodes
                            item.getChildren().clear();
                        }
                    });
                });
            }
        });
        return item;
    }

    // ── Cell renderers ────────────────────────────────────────────────────────

    private class DriveCell extends ListCell<DriveEntry> {
        @Override protected void updateItem(DriveEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); setText(null); return; }

            long total = item.root().getTotalSpace();
            Color fill;
            if (!item.root().exists() || total == 0)          fill = Color.web("#666");
            else if (total < 2L * 1024 * 1024 * 1024)        fill = Color.web("#f5a623"); // < 2 GB = removable
            else                                               fill = Color.web("#4d78cc");

            boolean active = currentFolder != null && currentFolder.getRoot() != null
                    && currentFolder.getRoot().toString().equalsIgnoreCase(item.label());
            Circle dot = new Circle(active ? 5.5 : 4.5);
            dot.setFill(active ? fill : Color.TRANSPARENT);
            dot.setStroke(fill);
            dot.setStrokeWidth(1.5);

            Label lbl = new Label(item.label());
            lbl.setStyle("-fx-font-size: 12px;");

            HBox box = new HBox(7, dot, lbl);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(1, 0, 1, 4));
            setGraphic(box);
            setCursor(Cursor.HAND);
        }
    }

    private static class QuickCell extends ListCell<QuickItem> {
        @Override protected void updateItem(QuickItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); setGraphic(null); setStyle(""); return; }
            if (item.isHeader()) {
                setText(null);
                Label lbl = new Label(item.header());
                lbl.setStyle("-fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #888; -fx-padding: 5 0 2 8;");
                setGraphic(lbl);
                setStyle("-fx-background-color: transparent;");
                setCursor(Cursor.DEFAULT);
                setDisable(true);
            } else {
                setDisable(false);
                setGraphic(null);
                Path p = Path.of(item.path());
                int n = p.getNameCount();
                setText(n > 0 ? p.getName(n - 1).toString() : item.path());
                setTooltip(new Tooltip(item.path()));
                setStyle("");
                setCursor(Cursor.HAND);
            }
        }
    }
}

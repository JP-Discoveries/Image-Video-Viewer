package com.imageviewer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * JavaFX Image &amp; Video Viewer — entry point.
 *
 * Features
 * ────────
 *  • Browse multiple folders; virtual thumbnail panel (async background loading)
 *  • Image display: zoom (scroll-wheel), pan (drag), fit/fill/actual-size, rotate
 *  • Video playback: JavaFX MediaPlayer (MP4/H.264/AAC), seek bar, volume
 *  • Themes: Dark, Light, Blue
 *  • Slideshow mode (configurable interval)
 *  • Full-screen (F11 / F key)
 *  • Config persisted to ~/.image_viewer/config_java.json
 *  • Keyboard navigation (← → arrow keys, Space, +/−, R, F)
 *  • Drag-and-drop folders / files onto the window
 */
public class App extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.getIcons().add(buildAppIcon());
        AppConfig  config  = AppConfig.load();
        ThemeManager theme = new ThemeManager();
        new MainWindow(primaryStage, config, theme, getParameters().getRaw()).show();
    }

    /**
     * Draws a simple camera icon on a 64×64 canvas and returns it as a
     * {@link WritableImage} suitable for use as the stage icon.
     */
    private static WritableImage buildAppIcon() {
        Canvas canvas = new Canvas(64, 64);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Rounded blue background
        gc.setFill(Color.web("#2d5be3"));
        gc.fillRoundRect(0, 0, 64, 64, 14, 14);

        // Camera body (white rounded rect)
        gc.setFill(Color.web("#ffffff"));
        gc.fillRoundRect(8, 22, 48, 32, 7, 7);

        // Viewfinder bump (top-centre of body)
        gc.fillRoundRect(22, 14, 16, 10, 5, 5);

        // Lens — outer dark ring
        gc.setFill(Color.web("#2d5be3"));
        gc.fillOval(20, 26, 24, 24);

        // Lens — inner highlight
        gc.setFill(Color.web("#aac4ff"));
        gc.fillOval(24, 30, 16, 16);

        // Lens — centre dot
        gc.setFill(Color.web("#ffffff"));
        gc.fillOval(29, 35, 6, 6);

        // Flash / indicator dot (top-right of body)
        gc.setFill(Color.web("#f5c842"));
        gc.fillOval(46, 27, 6, 6);

        WritableImage icon = new WritableImage(64, 64);
        canvas.snapshot(null, icon);
        return icon;
    }

    /** Ensure the JVM exits cleanly when the last window is closed. */
    @Override
    public void stop() {
        Platform.exit();
        System.exit(0);
    }
}

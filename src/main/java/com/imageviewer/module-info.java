module com.imageviewer {
    requires javafx.controls;
    requires transitive javafx.graphics;  // also exports javafx.print package
    requires java.desktop;   // ImageIO, AWT colour helpers
    requires java.xml;       // org.w3c.dom for GIF metadata parsing
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires uk.co.caprica.vlcj;             // VLCJ 4.x video playback (module uk.co.caprica.vlcj)
    requires javafx.media;                   // JavaFX media: fallback player when VLC not installed
    // EXIF / metadata reader (auto-module name from JAR manifest)
    requires metadata.extractor;
    // JavaCPP FFmpeg: direct library calls for alpha video decode
    requires org.bytedeco.javacpp;
    requires org.bytedeco.ffmpeg;
    requires org.bytedeco.ffmpeg.windows.x86_64;

    // Jackson needs reflective access to our config class
    opens com.imageviewer to com.fasterxml.jackson.databind;
    exports com.imageviewer;
}

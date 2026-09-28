package com.rsmt.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;

/**
 * JavaFX entry point for rSMTv2.
 * Launched by {@link com.rsmt.Main} when no CLI args are provided.
 */
public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        URL fxmlUrl = getClass().getResource("main.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("Cannot find main.fxml — check classpath");
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Scene scene = new Scene(loader.load(), 1400, 930);
        SimulationController controller = loader.getController();

        // Load dark theme CSS
        URL cssUrl = getClass().getResource("styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("rSMTv2 — Reverse SMT Processor Simulator");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(800);

        // Application icon — shown in taskbar, title bar, and Dock
        URL iconUrl = getClass().getResource("icon.png");
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        // Clean shutdown: cancel any running sim thread then exit the JVM.
        stage.setOnCloseRequest(e -> {
            if (controller != null) controller.shutdown();
            Platform.exit();
        });

        stage.show();
    }

    /** Called by Main.main() when no CLI args are present. */
    public static void launchGui(String[] args) {
        Application.launch(MainApp.class, args);
    }
}

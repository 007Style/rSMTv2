package com.rsmt.gui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
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
        Scene scene = new Scene(loader.load(), 1400, 880);

        // Load dark theme CSS
        URL cssUrl = getClass().getResource("styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("rSMTv2 — Reverse SMT Processor Simulator");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(750);
        stage.show();
    }

    /** Called by Main.main() when no CLI args are present. */
    public static void launchGui(String[] args) {
        Application.launch(MainApp.class, args);
    }
}

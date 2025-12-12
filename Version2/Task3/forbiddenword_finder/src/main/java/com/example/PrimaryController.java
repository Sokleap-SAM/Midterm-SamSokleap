package com.example;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class PrimaryController {

    @FXML
    private TextField directoryPathField;
    @FXML
    private TextField fileField;

    // Table View elements
    @FXML
    private TableView<ReportEntry> reportTableView;
    @FXML
    private TableColumn<ReportEntry, String> fileNameColumn;
    @FXML
    private TableColumn<ReportEntry, Integer> countColumn;
    @FXML
    private TableColumn<ReportEntry, String> fileDirectoryColumn;
    @FXML
    private TableColumn<ReportEntry, String> searchDirectoryColumn;

    // Control buttons
    @FXML
    private ProgressBar forbiddenProgressBar;
    @FXML
    private Button forbiddenStartButton;
    @FXML
    private Button forbiddenPauseButton;
    @FXML
    private Button forbiddenResumeButton;
    @FXML
    private Button forbiddenStopButton;
    @FXML
    private Button forbiddenResetButton;

    // Configuration
    private Path selectedDirectory;
    private final Path outputDirectory = Paths.get("forbidden_output");
    private Path forbiddenWordsFile;
    private ForbiddenFinderTask currentTask;

    @FXML
    public void initialize() {
        // Set up the table columns to bind to ReportEntry properties
        fileNameColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        countColumn.setCellValueFactory(new PropertyValueFactory<>("forbiddenWordCount"));
        fileDirectoryColumn.setCellValueFactory(new PropertyValueFactory<>("fileDirectory"));
        searchDirectoryColumn.setCellValueFactory(new PropertyValueFactory<>("searchDirectory"));

        setControlsForStopState(); // Initialize control buttons
    }

    // --- UI Handlers ---

    @FXML
    private void handleOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Directory to Search");
        Stage stage = (Stage) directoryPathField.getScene().getWindow();
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            selectedDirectory = selected.toPath();
            directoryPathField.setText(selectedDirectory.toString());
        }
    }

    @FXML
    private void handleOpenForbiddenFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Forbidden Words File (One word per line)");
        Stage stage = (Stage) fileField.getScene().getWindow();
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            forbiddenWordsFile = selected.toPath();
            fileField.setText(forbiddenWordsFile.toString());
        }
    }

    @FXML
    private void handleForbiddenStart() {
        if (selectedDirectory == null) {
            return;
        }

        Set<String> forbiddenWords = new HashSet<>();
        boolean usingEmptyWords = false;

        try {
            // Clear previous results
            reportTableView.getItems().clear();

            if (forbiddenWordsFile != null && Files.exists(forbiddenWordsFile)) {
                forbiddenWords = new HashSet<>(Files.readAllLines(forbiddenWordsFile));
                if (forbiddenWords.isEmpty()) {
                    usingEmptyWords = true;
                }
            } else {
                forbiddenWords = new HashSet<>();
                usingEmptyWords = true;
            }

            // Create and configure the task
            currentTask = new ForbiddenFinderTask(selectedDirectory, forbiddenWords, outputDirectory);

            // Bind UI elements to the task's properties
            forbiddenProgressBar.progressProperty().bind(currentTask.progressProperty());

            // Bind the task's message property to the TextArea

            // Set callbacks for completion and failure
            currentTask.setOnSucceeded(e -> {
                // Bind the task's result to the TableView
                reportTableView.setItems(currentTask.getValue());
                setControlsForStopState();
            });

            currentTask.setOnFailed(e -> {
                currentTask.getException().printStackTrace();
                setControlsForStopState();
            });

            currentTask.setOnCancelled(e -> {
                setControlsForStopState();
            });

            new Thread(currentTask).start();
            setControlsForStartState();

        } catch (IOException e) {
        }
    }

    @FXML
    private void handleForbiddenPause() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.pauseExecution();
            setControlsForPauseState();
        }
    }

    @FXML
    private void handleForbiddenResume() {
        if (currentTask != null) {
            currentTask.resumeExecution();
            setControlsForStartState();
        }
    }

    @FXML
    private void handleForbiddenStop() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.cancel();
            setControlsForStopState();
        }
    }

    @FXML
    private void handleForbiddenReset() {
        if (currentTask != null) {
            currentTask.cancel();
        }
        forbiddenProgressBar.progressProperty().unbind();
        forbiddenProgressBar.setProgress(0.0);
        reportTableView.getItems().clear(); // Clear the table
        setControlsForStopState();
        directoryPathField.setText("");
        fileField.setText("");
        selectedDirectory = null;
        forbiddenWordsFile = null;
    }

    private void setControlsForStartState() {
        forbiddenStartButton.setDisable(true);
        forbiddenPauseButton.setDisable(false);
        forbiddenResumeButton.setDisable(true);
        forbiddenStopButton.setDisable(false);
        forbiddenResetButton.setDisable(true);
    }

    private void setControlsForPauseState() {
        forbiddenStartButton.setDisable(true);
        forbiddenPauseButton.setDisable(true);
        forbiddenResumeButton.setDisable(false);
        forbiddenStopButton.setDisable(false);
        forbiddenResetButton.setDisable(true);
    }

    private void setControlsForStopState() {
        forbiddenStartButton.setDisable(false);
        forbiddenPauseButton.setDisable(true);
        forbiddenResumeButton.setDisable(true);
        forbiddenStopButton.setDisable(true);
        forbiddenResetButton.setDisable(false);
    }
}
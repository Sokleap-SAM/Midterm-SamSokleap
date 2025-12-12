package com.example;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty; // New import for file size
import javafx.beans.property.SimpleStringProperty;

public class ReportEntry {
    
    private final SimpleStringProperty fileName;
    private final SimpleIntegerProperty forbiddenWordCount;
    private final SimpleStringProperty fileDirectory;
    private final SimpleStringProperty searchDirectory;
    private final SimpleLongProperty fileSize; // New property for file size

    public ReportEntry(
            String fileName, 
            int forbiddenWordCount, 
            String fileDirectory, 
            String searchDirectory,
            long fileSize) { // Updated constructor signature
        this.fileName = new SimpleStringProperty(fileName);
        this.forbiddenWordCount = new SimpleIntegerProperty(forbiddenWordCount);
        this.fileDirectory = new SimpleStringProperty(fileDirectory);
        this.searchDirectory = new SimpleStringProperty(searchDirectory);
        this.fileSize = new SimpleLongProperty(fileSize); // Initialize new property
    }

    // --- Getters for TableView Column Binding ---
    public String getFileName() { return fileName.get(); }
    public int getForbiddenWordCount() { return forbiddenWordCount.get(); }
    public String getFileDirectory() { return fileDirectory.get(); }
    public String getSearchDirectory() { return searchDirectory.get(); }
    public long getFileSize() { return fileSize.get(); } // New Getter
    
    // --- Optional: Property Getters (good practice) ---
    public SimpleStringProperty fileNameProperty() { return fileName; }
    public SimpleIntegerProperty forbiddenWordCountProperty() { return forbiddenWordCount; }
    public SimpleStringProperty fileDirectoryProperty() { return fileDirectory; }
    public SimpleStringProperty searchDirectoryProperty() { return searchDirectory; }
    public SimpleLongProperty fileSizeProperty() { return fileSize; } // New Property Getter
    
    /**
     * Helper method to format file size into human-readable bytes (KB, MB, GB).
     * This is useful for presentation in the TableView.
     */
    public String getFormattedFileSize() {
        long bytes = getFileSize();
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
package com.example;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;

public class ReportEntry {
    
    private final SimpleStringProperty fileName;
    private final SimpleIntegerProperty forbiddenWordCount;
    private final SimpleStringProperty fileDirectory;
    private final SimpleStringProperty searchDirectory;

    public ReportEntry(String fileName, int forbiddenWordCount, String fileDirectory, String searchDirectory) {
        this.fileName = new SimpleStringProperty(fileName);
        this.forbiddenWordCount = new SimpleIntegerProperty(forbiddenWordCount);
        this.fileDirectory = new SimpleStringProperty(fileDirectory);
        this.searchDirectory = new SimpleStringProperty(searchDirectory);
    }

    // Getters for TableView Column Binding
    public String getFileName() { return fileName.get(); }
    public int getForbiddenWordCount() { return forbiddenWordCount.get(); }
    public String getFileDirectory() { return fileDirectory.get(); }
    public String getSearchDirectory() { return searchDirectory.get(); }
    
    // Optional: Property Getters (good practice)
    public SimpleStringProperty fileNameProperty() { return fileName; }
    public SimpleIntegerProperty forbiddenWordCountProperty() { return forbiddenWordCount; }
    public SimpleStringProperty fileDirectoryProperty() { return fileDirectory; }
    public SimpleStringProperty searchDirectoryProperty() { return searchDirectory; }
}
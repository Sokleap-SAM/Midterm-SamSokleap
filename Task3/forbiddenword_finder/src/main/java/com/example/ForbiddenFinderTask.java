package com.example;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Task now returns a list of ReportEntry objects for the TableView
public class ForbiddenFinderTask extends Task<ObservableList<ReportEntry>> {

    private final Path startDirectory;
    private final Set<String> forbiddenWords;
    private final Path outputDirectory;
    private final AtomicLong filesProcessed = new AtomicLong(0);

    // Store ReportEntry objects
    private final ObservableList<ReportEntry> reportEntries = FXCollections.observableArrayList();
    private final ConcurrentHashMap<String, AtomicLong> wordCounts = new ConcurrentHashMap<>();

    // Synchronization control for Pause/Resume
    private final Object pauseLock = new Object();
    private volatile boolean isPaused = false;
    private final String searchDirectoryString; // Store the root path once for the report

    public ForbiddenFinderTask(
            Path startDirectory,
            Set<String> forbiddenWords,
            Path outputDirectory) {
        this.startDirectory = startDirectory;
        this.forbiddenWords = forbiddenWords;
        this.outputDirectory = outputDirectory;
        this.searchDirectoryString = startDirectory.toAbsolutePath().toString();
    }

    // --- Control Methods ---

    public void pauseExecution() {
        isPaused = true;
        this.updateMessage("Paused...");
    }

    public void resumeExecution() {
        isPaused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        this.updateMessage("Searching...");
    }

    // --- Core Task Logic ---

    @Override
    protected ObservableList<ReportEntry> call() throws Exception {
        // 1. Prepare and Validate Directory (Your Step 1: Validate folder exist)
        if (!Files.exists(startDirectory) || !Files.isDirectory(startDirectory)) {
            this.updateMessage("Error: Directory not found or is not a valid directory.");
            // Throwing an exception here ensures setOnFailed is called
            throw new NoSuchFileException("Directory not found: " + startDirectory);
        }

        Files.createDirectories(outputDirectory);
        reportEntries.clear(); // Ensure list is clean for a new run

        // 2. Calculate total files (Your Step 1: list down all files)
        long totalFiles = calculateTotalFiles(startDirectory);
        if (totalFiles == 0) {
            this.updateMessage("Directory is empty or contains no files to process.");
            return FXCollections.emptyObservableList();
        }

        this.updateProgress(0, totalFiles);
        this.updateMessage("Starting search in: " + startDirectory.getFileName());

        // 3. Start search (Your Step 2: run thread to search)
        processDirectory(startDirectory, totalFiles);

        // 4. Final Steps
        if (!isCancelled()) {
            generateReport(); // Report generation still writes to file
            this.updateMessage(String.format("Search complete. %d files found with forbidden words. Report saved.",
                    reportEntries.size()));
        } else {
            this.updateMessage("Search was cancelled.");
        }

        // Return the collected data for the TableView
        return reportEntries;
    }

    private void processDirectory(Path currentDir, long totalFiles) throws IOException, InterruptedException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (isCancelled()) {
                    return;
                }
                System.out.println(currentDir);
                System.out.println(entry);
                checkPauseState();

                if (Files.isDirectory(entry)) {
                    // Skip hidden directories (like .git)
                    if (!Files.isHidden(entry)) {
                        processDirectory(entry, totalFiles);
                    }
                } else if (Files.isRegularFile(entry)) {
                    processFile(entry);

                    filesProcessed.incrementAndGet();
                    this.updateProgress(filesProcessed.get(), totalFiles);
                    this.updateMessage(String.format("Processing: %s (File %d of %d)",
                            entry.getFileName(), filesProcessed.get(), totalFiles));
                }
            }
        }
    }

    private void processFile(Path filePath) {
        long replacementsCount = 0;
        StringBuilder replacementContent = new StringBuilder();
        boolean foundForbiddenWord = false; // Controls whether the file is put in the report

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String currentLine = line;
                long lineReplacements = 0;

                for (String word : forbiddenWords) {
                    System.out.println(word);
                    // Define the pattern for whole word, case-insensitive match
                    Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
                    java.util.regex.Matcher matcher = pattern.matcher(currentLine);

                    long count = 0;
                    // Step 2: ACCURATE COUNTING using matcher.find()
                    while (matcher.find()) {
                        count++;
                    }

                    if (count > 0) {
                        foundForbiddenWord = true;

                        // Step 3: Perform replacement on the current line
                        matcher.reset(currentLine); // Reset matcher to perform replacement from the start
                        String replacedLine = matcher.replaceAll("*******");

                        // Update the line and accumulated counts
                        currentLine = replacedLine;
                        lineReplacements += count;

                        // Update the overall word statistics (ConcurrentHashMap)
                        wordCounts.computeIfAbsent(word, k -> new AtomicLong(0)).addAndGet(count);
                    }
                }

                // Accumulate total replacements for the ReportEntry
                replacementsCount += lineReplacements;
                replacementContent.append(currentLine).append(System.lineSeparator());
            }

            // Your instruction: if found -> put in report
            if (foundForbiddenWord) {
                // 1. Copy the original file
                Path copiedFile = outputDirectory.resolve(filePath.getFileName().toString());
                Files.copy(filePath, copiedFile, StandardCopyOption.REPLACE_EXISTING);

                // 2. Write the replacement file
                String replacementFileName = filePath.getFileName().toString() + ".replaced";
                Path replacedFile = outputDirectory.resolve(replacementFileName);
                Files.write(replacedFile, replacementContent.toString().getBytes());

                // 3. Add to report entries (ReportEntry object)
                ReportEntry entry = new ReportEntry(
                        filePath.getFileName().toString(),
                        (int) replacementsCount,
                        filePath.getParent().toAbsolutePath().toString(),
                        searchDirectoryString);
                // ReportEntry is added to the ObservableList
                reportEntries.add(entry);
            }
            // Your instruction: if no found -> ignore that file
            // If foundForbiddenWord is false, the function simply finishes without adding
            // to reportEntries.

        } catch (IOException e) {
            System.err.println("Error processing file " + filePath + ": " + e.getMessage());
        }
    }

    private long calculateTotalFiles(Path start) throws IOException {
        // Your Step 1: list down all file store in list (and count them)
        try (Stream<Path> stream = Files.walk(start)) {
            // Filter to only count regular, non-hidden files
            return stream.filter(p -> Files.isRegularFile(p) && !p.toFile().isHidden()).count();
        }
    }

    // --- Pause/Resume Synchronization ---
    private void checkPauseState() throws InterruptedException {
        synchronized (pauseLock) {
            while (isPaused) {
                pauseLock.wait(); // Release lock and wait until notified
            }
        }
    }

    // --- Report Generation (Writes to file) ---
    private void generateReport() throws IOException {
        Path reportPath = outputDirectory.resolve("ForbiddenFinder_Report_" + System.currentTimeMillis() + ".txt");

        try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
            writer.write("--- Forbidden Finder Report ---");
            writer.newLine();

            // Found Files and Replacements
            writer.write("\n\nFound Files Containing Forbidden Words:");
            writer.newLine();
            if (reportEntries.isEmpty()) {
                writer.write("None found.");
            } else {
                for (ReportEntry entry : reportEntries) {
                    writer.write(String.format("File: %s | Replacements: %d | Path: %s",
                            entry.getFileName(), entry.getForbiddenWordCount(), entry.getFileDirectory()));
                    writer.newLine();
                }
            }
            // 10 Most Popular Words
            writer.write("\n\n--- 10 Most Popular Forbidden Words ---");
            writer.newLine();

            wordCounts.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue().get(), e1.getValue().get()))
                    .limit(10)
                    .forEach(entry -> {
                        try {
                            writer.write(String.format("%s: %d replacements", entry.getKey(), entry.getValue().get()));
                            writer.newLine();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }
    }
}
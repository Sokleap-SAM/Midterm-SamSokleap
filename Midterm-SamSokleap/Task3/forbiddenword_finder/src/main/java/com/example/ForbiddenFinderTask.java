package com.example;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// The main Task now manages a pool of worker threads.
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

    // New: Thread pool and Semaphore for controlled multi-threading
    private final ExecutorService fileProcessorExecutor;
    private final Semaphore fileProcessSemaphore;
    // Set a reasonable concurrency limit, e.g., twice the available processors
    private final int CONCURRENCY_LIMIT = Runtime.getRuntime().availableProcessors() * 2;

    public ForbiddenFinderTask(
            Path startDirectory,
            Set<String> forbiddenWords,
            Path outputDirectory) {
        this.startDirectory = startDirectory;
        this.forbiddenWords = forbiddenWords;
        this.outputDirectory = outputDirectory;
        this.searchDirectoryString = startDirectory.toAbsolutePath().toString();

        // Initialize the fixed thread pool
        this.fileProcessorExecutor = Executors.newFixedThreadPool(CONCURRENCY_LIMIT);

        // Initialize the Semaphore to limit concurrent access to file resources
        this.fileProcessSemaphore = new Semaphore(CONCURRENCY_LIMIT);
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
        // 1. Prepare and Validate Directory
        if (!Files.exists(startDirectory) || !Files.isDirectory(startDirectory)) {
            this.updateMessage("Error: Directory not found or is not a valid directory.");
            fileProcessorExecutor.shutdownNow();
            throw new NoSuchFileException("Directory not found: " + startDirectory);
        }

        Files.createDirectories(outputDirectory);
        reportEntries.clear();
        wordCounts.clear();

        // 2. Calculate total files and collect all file paths
        List<Path> allFiles = collectAllFiles(startDirectory);
        long totalFiles = allFiles.size();

        if (totalFiles == 0) {
            this.updateMessage("Directory is empty or contains no files to process.");
            fileProcessorExecutor.shutdown();
            return FXCollections.emptyObservableList();
        }

        this.updateProgress(0, totalFiles);
        this.updateMessage("Starting multi-threaded search in: " + startDirectory.getFileName());

        // 3. Start multi-threaded search

        CompletionService<Optional<ReportEntry>> completionService = new ExecutorCompletionService<>(
                fileProcessorExecutor);
        int filesSubmitted = 0;

        for (Path file : allFiles) {
            if (isCancelled())
                break;

            // Acquire a permit from the Semaphore before submitting the task
            fileProcessSemaphore.acquire();

            // Submit a new Callable for each file
            completionService.submit(new FileProcessorCallable(file));
            filesSubmitted++;
        }

        // 4. Collect results and update UI (This part runs on the JavaFX Task thread)
        for (int i = 0; i < filesSubmitted; i++) {
            if (isCancelled())
                break;

            checkPauseState(); // Check pause status

            Future<Optional<ReportEntry>> future = null; // Declare future outside try block
            try {
                // Wait for the next file processing result (Future)
                future = completionService.take();
                Optional<ReportEntry> result = future.get(); // Get the result

                result.ifPresent(entry -> {
                    // Update the ObservableList on the FX Thread
                    reportEntries.add(entry);
                });

                // Release the semaphore permit, allowing another task to start submission
                fileProcessSemaphore.release();

                filesProcessed.incrementAndGet();
                updateProgress(filesProcessed.get(), totalFiles);
                updateMessage(String.format("Processed: %s (File %d of %d)",
                        result.map(ReportEntry::getFileName).orElse("N/A"),
                        filesProcessed.get(), totalFiles));

            } catch (ExecutionException e) {
                System.err.println("Error processing file (Execution): " + e.getCause().getMessage());
                // Crucial: If future.get() fails, the worker thread released its permit,
                // so we don't need to release it here, but we must handle the case
                // where the thread that acquired the permit didn't release it (which shouldn't
                // happen
                // if the Callable executes successfully or returns empty).
                // For safety and simplicity, we assume the semaphore is released upon
                // successful
                // processing or intentional release is needed here if execution fails.
                // However, since we acquired the permit before *submission* (outside the try
                // block),
                // we must ensure it is released here if we reach this catch block
                // AND the release hasn't happened.

                // Safety: If the task failed after acquiring the permit but before submission
                // (which should be rare with our current logic), the permit is still held.
                // Since the acquisition happens immediately before submission, we can rely
                // on the success path for release. If a fundamental error occurred in the loop,
                // we should still release.

                // Let's ensure the release is robust:
                if (future != null && future.isDone()) {
                    // This means the Callable finished (successfully or failed). The Callable
                    // does *not* release the permit; the main task thread does.
                    fileProcessSemaphore.release();
                } else if (future != null && !future.isDone()) {
                    // If future.get() threw ExecutionException, the task finished.
                    // We must release the permit acquired before submission.
                    fileProcessSemaphore.release();
                }

            } catch (InterruptedException e) {
                // Handle cancellation or interruption during take() or get()
                fileProcessorExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                return reportEntries;
            }
        }

        // Final cleanup
        fileProcessorExecutor.shutdownNow();

        // 5. Final Steps
        if (!isCancelled()) {
            generateReport();
            this.updateMessage(String.format("Search complete. %d files found with forbidden words. Report saved.",
                    reportEntries.size()));
        } else {
            this.updateMessage("Search was cancelled.");
        }

        return reportEntries;
    }

    /**
     * Helper method to collect all files recursively before starting the thread
     * pool.
     */
    private List<Path> collectAllFiles(Path start) throws IOException {
    try (Stream<Path> stream = Files.walk(start)) {
        return stream.filter(p -> Files.isRegularFile(p) && !p.toFile().isHidden())
                // Use the Java 8/11 compatible collection method:
                .collect(Collectors.toList()); 
    }
}

    // --- File Processor (Callable for the thread pool) ---

    /**
     * This class handles the processing of a single file on a worker thread.
     */
    private class FileProcessorCallable implements Callable<Optional<ReportEntry>> {
        private final Path filePath;

        public FileProcessorCallable(Path filePath) {
            this.filePath = filePath;
        }

        @Override
        public Optional<ReportEntry> call() throws Exception {
            // Worker threads must respect global state (Pause/Cancel)
            if (isCancelled())
                return Optional.empty();

            // Check global pause state
            synchronized (pauseLock) {
                while (isPaused) {
                    pauseLock.wait();
                }
            }

            long replacementsCount = 0;
            StringBuilder replacementContent = new StringBuilder();
            boolean foundForbiddenWord = false;

            // Calculate file size for the report
            long fileSize = Files.size(filePath);

            try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled())
                        return Optional.empty();

                    String currentLine = line;
                    long lineReplacements = 0;

                    for (String word : forbiddenWords) {
                        // 1. Define and count
                        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b",
                                Pattern.CASE_INSENSITIVE);
                        java.util.regex.Matcher matcher = pattern.matcher(currentLine);

                        long count = 0;
                        while (matcher.find()) {
                            count++;
                        }

                        if (count > 0) {
                            foundForbiddenWord = true;

                            // 2. Perform replacement
                            matcher.reset(currentLine);
                            String replacedLine = matcher.replaceAll("*******");

                            currentLine = replacedLine;
                            lineReplacements += count;

                            // 3. Update global word stats (Thread-safe ConcurrentHashMap)
                            wordCounts.computeIfAbsent(word, k -> new AtomicLong(0)).addAndGet(count);
                        }
                    }

                    replacementsCount += lineReplacements;
                    replacementContent.append(currentLine).append(System.lineSeparator());
                }

                if (foundForbiddenWord) {
                    // File processing and reporting (Your Step 2: if found -> put in report)

                    // 1. Copy the original file
                    Path copiedFile = outputDirectory.resolve(filePath.getFileName().toString());
                    Files.copy(filePath, copiedFile, StandardCopyOption.REPLACE_EXISTING);

                    // 2. Write the replacement file
                    String replacementFileName = filePath.getFileName().toString() + ".replaced";
                    Path replacedFile = outputDirectory.resolve(replacementFileName);
                    Files.write(replacedFile, replacementContent.toString().getBytes());

                    // 3. Return the ReportEntry
                    ReportEntry entry = new ReportEntry(
                            filePath.getFileName().toString(),
                            (int) replacementsCount,
                            filePath.getParent().toAbsolutePath().toString(),
                            searchDirectoryString,
                            fileSize);
                    return Optional.of(entry);
                }

                // Your Step 2: if no found -> ignore that file
                return Optional.empty();

            } catch (IOException e) {
                System.err.println("Error processing file " + filePath + ": " + e.getMessage());
                return Optional.empty();
            }
        }
    }

    // --- Pause/Resume Synchronization ---
    private void checkPauseState() throws InterruptedException {
        synchronized (pauseLock) {
            while (isPaused) {
                pauseLock.wait();
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
                    // UPDATED: Include File Size in the generated report
                    writer.write(String.format("File: %s | Replacements: %d | Size: %s | Path: %s",
                            entry.getFileName(),
                            entry.getForbiddenWordCount(),
                            entry.getFormattedFileSize(), // Use the formatted size for readability
                            entry.getFileDirectory()));
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
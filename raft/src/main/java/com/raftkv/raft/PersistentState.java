package com.raftkv.raft;

import com.raftkv.core.LogEntry;
import com.raftkv.core.NodeId;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Manages the durable Raft state: currentTerm, votedFor, and the log.
 *
 * <p>File format (line-oriented text):
 * <pre>
 *   term=<N>
 *   votedFor=<nodeId>    (or empty string for null)
 *   <term>,<index>,<base64-encoded-command>
 *   ...
 * </pre>
 *
 * <p>On startup the file is read if it exists. Whenever term or votedFor changes the
 * header (first two lines) is rewritten atomically. Log entries are appended at the end.
 *
 * <p>Not thread-safe — callers must ensure single-threaded access (the RaftNode event loop
 * satisfies this).
 */
public final class PersistentState {

    private final Path filePath;

    private long currentTerm = 0;
    private NodeId votedFor = null;
    private final List<LogEntry> log = new ArrayList<>();

    public PersistentState(Path filePath) throws IOException {
        this.filePath = filePath;
        if (Files.exists(filePath)) {
            load();
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public long getCurrentTerm() {
        return currentTerm;
    }

    public NodeId getVotedFor() {
        return votedFor;
    }

    /** Returns an unmodifiable view of the log. */
    public List<LogEntry> getLog() {
        return List.copyOf(log);
    }

    public int logSize() {
        return log.size();
    }

    /** Returns the last log index (0 if empty). */
    public long lastLogIndex() {
        return log.isEmpty() ? 0 : log.get(log.size() - 1).getIndex();
    }

    /** Returns the last log term (0 if empty). */
    public long lastLogTerm() {
        return log.isEmpty() ? 0 : log.get(log.size() - 1).getTerm();
    }

    // -------------------------------------------------------------------------
    // Mutators — each call persists immediately
    // -------------------------------------------------------------------------

    /** Updates currentTerm and votedFor atomically and persists. */
    public void setTermAndVote(long term, NodeId votedFor) throws IOException {
        this.currentTerm = term;
        this.votedFor = votedFor;
        persistHeader();
    }

    /** Convenience: update only the term (clears votedFor). */
    public void setTerm(long term) throws IOException {
        setTermAndVote(term, null);
    }

    /** Appends a new log entry and persists it. */
    public void appendEntry(LogEntry entry) throws IOException {
        log.add(entry);
        appendEntryToFile(entry);
    }

    /**
     * Truncates the log so that only entries with index < fromIndex remain.
     * Persists the result atomically.
     */
    public void truncateFrom(long fromIndex) throws IOException {
        // fromIndex is 1-based log index; remove all entries whose index >= fromIndex
        log.removeIf(e -> e.getIndex() >= fromIndex);
        persistHeader();
    }

    /**
     * Returns the log entry at the given 1-based log index, or null if not present.
     */
    public LogEntry getEntryAtIndex(long index) {
        // Linear search — acceptable for correctness; log is short in tests
        for (int i = log.size() - 1; i >= 0; i--) {
            LogEntry e = log.get(i);
            if (e.getIndex() == index) return e;
            if (e.getIndex() < index) break;
        }
        return null;
    }

    /**
     * Returns all log entries with index >= fromIndex (in order).
     */
    public List<LogEntry> getEntriesFrom(long fromIndex) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry e : log) {
            if (e.getIndex() >= fromIndex) {
                result.add(e);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    /**
     * Rewrites the entire file from the in-memory state.
     * Used for header updates (term/votedFor change).
     */
    private void persistHeader() throws IOException {
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp.toFile()), StandardCharsets.UTF_8))) {
            w.write("term=" + currentTerm);
            w.newLine();
            w.write("votedFor=" + (votedFor == null ? "" : votedFor.getId()));
            w.newLine();
            for (LogEntry e : log) {
                w.write(encodeEntry(e));
                w.newLine();
            }
            w.flush();
        }
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Appends a single entry line to the end of the file. */
    private void appendEntryToFile(LogEntry entry) throws IOException {
        // If the file doesn't exist yet (first write), do a full persist instead.
        if (!Files.exists(filePath)) {
            persistHeader();
            return;
        }
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(filePath.toFile(), true), StandardCharsets.UTF_8))) {
            w.write(encodeEntry(entry));
            w.newLine();
            w.flush();
        }
    }

    private static String encodeEntry(LogEntry e) {
        String b64 = Base64.getEncoder().encodeToString(e.getCommand());
        return e.getTerm() + "," + e.getIndex() + "," + b64;
    }

    /** Reads the persisted file and populates in-memory state. */
    private void load() throws IOException {
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            throw new IOException("Corrupt persistent state file: " + filePath);
        }

        // Line 0: term=<N>
        String termLine = lines.get(0);
        if (!termLine.startsWith("term=")) {
            throw new IOException("Corrupt: expected term line, got: " + termLine);
        }
        currentTerm = Long.parseLong(termLine.substring("term=".length()).trim());

        // Line 1: votedFor=<nodeId>
        String vfLine = lines.get(1);
        if (!vfLine.startsWith("votedFor=")) {
            throw new IOException("Corrupt: expected votedFor line, got: " + vfLine);
        }
        String vf = vfLine.substring("votedFor=".length()).trim();
        votedFor = vf.isEmpty() ? null : new NodeId(vf);

        // Lines 2+: log entries
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",", 3);
            if (parts.length != 3) {
                throw new IOException("Corrupt log entry at line " + (i + 1) + ": " + line);
            }
            long entryTerm = Long.parseLong(parts[0]);
            long entryIndex = Long.parseLong(parts[1]);
            byte[] cmd = Base64.getDecoder().decode(parts[2]);
            log.add(new LogEntry(entryTerm, entryIndex, cmd));
        }
    }
}

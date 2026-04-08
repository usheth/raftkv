package com.raftkv.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable log entry in the Raft log.
 */
public final class LogEntry {
    private final long term;
    private final long index;
    private final byte[] command;

    public LogEntry(long term, long index, byte[] command) {
        this.term = term;
        this.index = index;
        this.command = command == null ? new byte[0] : command.clone();
    }

    public long getTerm() {
        return term;
    }

    public long getIndex() {
        return index;
    }

    public byte[] getCommand() {
        return command.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogEntry)) return false;
        LogEntry other = (LogEntry) o;
        return term == other.term && index == other.index && Arrays.equals(command, other.command);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(term, index);
        result = 31 * result + Arrays.hashCode(command);
        return result;
    }

    @Override
    public String toString() {
        return "LogEntry{term=" + term + ", index=" + index + "}";
    }
}

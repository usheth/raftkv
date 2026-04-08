package com.raftkv.core;

/**
 * The three possible roles a Raft node can occupy.
 */
public enum RaftRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}

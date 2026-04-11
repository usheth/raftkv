package com.raftkv.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KVStoreTest {

    private KVStore store;

    @BeforeEach
    void setUp() {
        store = new KVStore();
    }

    @Test
    void getReturnsEmptyForMissingKey() {
        assertEquals(Optional.empty(), store.get("missing"));
    }

    @Test
    void putAndGetRoundTrip() {
        store.put("hello", "world");
        assertEquals(Optional.of("world"), store.get("hello"));
    }

    @Test
    void putOverwritesExistingValue() {
        store.put("k", "v1");
        store.put("k", "v2");
        assertEquals(Optional.of("v2"), store.get("k"));
    }

    @Test
    void deleteRemovesKey() {
        store.put("del", "val");
        assertTrue(store.delete("del"));
        assertEquals(Optional.empty(), store.get("del"));
    }

    @Test
    void deleteReturnsFalseForAbsentKey() {
        assertFalse(store.delete("absent"));
    }

    @Test
    void fenceAndUnfenceVnode() {
        assertFalse(store.isFenced(5));
        store.fenceVnode(5);
        assertTrue(store.isFenced(5));
        store.unfenceVnode(5);
        assertFalse(store.isFenced(5));
    }

    @Test
    void fencingDoesNotAffectOtherVnodes() {
        store.fenceVnode(3);
        assertFalse(store.isFenced(4));
        assertFalse(store.isFenced(2));
    }

    @Test
    void multipleVnodesCanBeFencedIndependently() {
        store.fenceVnode(0);
        store.fenceVnode(255);
        assertTrue(store.isFenced(0));
        assertTrue(store.isFenced(255));
        store.unfenceVnode(0);
        assertFalse(store.isFenced(0));
        assertTrue(store.isFenced(255));
    }
}

package com.linggango.sync.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure duplicate-suppression rule: a custom payload is suppressed only when it
 * is byte-identical to the immediately preceding payload on the same channel for the same
 * connection.
 */
class DuplicateSuppressorTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void suppressesImmediateRepeat() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object connection = new Object();

        assertFalse(suppressor.shouldSuppress(connection, "goety:channel", 3, bytes("payload")));
        assertTrue(suppressor.shouldSuppress(connection, "goety:channel", 3, bytes("payload")));
        assertTrue(suppressor.shouldSuppress(connection, "goety:channel", 3, bytes("payload")));
    }

    @Test
    void allowsDifferingPayload() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object connection = new Object();

        assertFalse(suppressor.shouldSuppress(connection, "goety:channel", 3, bytes("aaa")));
        assertFalse(suppressor.shouldSuppress(connection, "goety:channel", 3, bytes("bbb")));
        assertTrue(suppressor.shouldSuppress(connection, "goety:channel", 3, bytes("bbb")));
    }

    @Test
    void doesNotSuppressAlternatingPayloads() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object connection = new Object();

        assertFalse(suppressor.shouldSuppress(connection, "ch", 0, bytes("A")));
        assertFalse(suppressor.shouldSuppress(connection, "ch", 0, bytes("B")));
        assertFalse(suppressor.shouldSuppress(connection, "ch", 0, bytes("A")));
        assertFalse(suppressor.shouldSuppress(connection, "ch", 0, bytes("B")));
    }

    @Test
    void tracksChannelsIndependently() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object connection = new Object();

        assertFalse(suppressor.shouldSuppress(connection, "ch:one", 0, bytes("same")));
        assertFalse(suppressor.shouldSuppress(connection, "ch:two", 0, bytes("same")));
        assertTrue(suppressor.shouldSuppress(connection, "ch:one", 0, bytes("same")));
    }

    @Test
    void isolatesConnections() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object alice = new Object();
        Object bob = new Object();

        assertFalse(suppressor.shouldSuppress(alice, "ch", 0, bytes("same")));
        assertFalse(suppressor.shouldSuppress(bob, "ch", 0, bytes("same")));
        assertTrue(suppressor.shouldSuppress(alice, "ch", 0, bytes("same")));
    }

    @Test
    void indexDistinguishesPayloadsWithIdenticalBytes() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object connection = new Object();

        assertFalse(suppressor.shouldSuppress(connection, "ch", 1, bytes("same")));
        assertFalse(suppressor.shouldSuppress(connection, "ch", 2, bytes("same")));
    }

    @Test
    void forgetClearsState() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor();
        Object connection = new Object();

        assertFalse(suppressor.shouldSuppress(connection, "ch", 0, bytes("same")));
        suppressor.forget(connection);
        assertFalse(suppressor.shouldSuppress(connection, "ch", 0, bytes("same")));
    }
}

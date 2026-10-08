package com.alak.neuralgateway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyPoolTest {

    @Test
    @DisplayName("Strict round-robin key distribution across multiple keys")
    void testStrictRoundRobin() {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("key1", "key2", "key3"), 100);
        assertEquals("key1", pool.getNextKey());
        assertEquals("key2", pool.getNextKey());
        assertEquals("key3", pool.getNextKey());
        assertEquals("key1", pool.getNextKey());
    }

    @Test
    @DisplayName("Round-robin handles integer overflow gracefully without throwing IndexOutOfBoundsException")
    void testIntegerOverflowSafe() throws Exception {
        ApiKeyPool pool = new ApiKeyPool("test-provider", List.of("keyA", "keyB", "keyC"), 100);

        // Access private field currentKeyIndex to simulate integer overflow
        Field field = ApiKeyPool.class.getDeclaredField("currentKeyIndex");
        field.setAccessible(true);
        AtomicInteger counter = (AtomicInteger) field.get(pool);
        counter.set(Integer.MAX_VALUE);

        // Next call should safely return a valid key and not throw negative index exception
        assertDoesNotThrow(() -> {
            String key = pool.getNextKey();
            assertNotNull(key);
            assertTrue(List.of("keyA", "keyB", "keyC").contains(key));
        });

        // Following call should also work safely
        String nextKey = pool.getNextKey();
        assertNotNull(nextKey);
        assertTrue(List.of("keyA", "keyB", "keyC").contains(nextKey));
    }

    @Test
    @DisplayName("Handles single key and empty pool correctly")
    void testSingleAndEmptyKeys() {
        ApiKeyPool singlePool = new ApiKeyPool("single", List.of("only-one"), 100);
        assertEquals("only-one", singlePool.getNextKey());
        assertEquals("only-one", singlePool.getNextKey());

        ApiKeyPool emptyPool = new ApiKeyPool("empty", List.of(), 100);
        assertNull(emptyPool.getNextKey());
    }
}

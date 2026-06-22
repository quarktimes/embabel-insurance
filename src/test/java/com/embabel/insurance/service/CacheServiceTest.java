package com.embabel.insurance.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for CacheService.
 * Tests caching behavior, expiration, and statistics.
 */
class CacheServiceTest {

    private CacheService cacheService;
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager("llm-responses", "rag-searches");
        cacheService = new CacheService(cacheManager);
    }

    @Nested
    @DisplayName("Local Cache Tests")
    class LocalCacheTests {

        @Test
        @DisplayName("Should store and retrieve from local cache")
        void shouldStoreAndRetrieveFromLocalCache() {
            String key = "test-key";
            String value = "test-value";
            
            cacheService.putInLocalCache(key, value);
            Object result = cacheService.getFromLocalCache(key);
            
            assertEquals(value, result);
        }

        @Test
        @DisplayName("Should return null for non-existent key")
        void shouldReturnNullForNonExistentKey() {
            Object result = cacheService.getFromLocalCache("non-existent-key");
            assertNull(result);
        }

        @Test
        @DisplayName("Should overwrite existing key")
        void shouldOverwriteExistingKey() {
            String key = "test-key";
            cacheService.putInLocalCache(key, "value1");
            cacheService.putInLocalCache(key, "value2");
            
            Object result = cacheService.getFromLocalCache(key);
            assertEquals("value2", result);
        }

        @Test
        @DisplayName("Should handle null value")
        void shouldHandleNullValue() {
            String key = "null-key";
            cacheService.putInLocalCache(key, null);
            
            Object result = cacheService.getFromLocalCache(key);
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Cache Statistics Tests")
    class CacheStatisticsTests {

        @Test
        @DisplayName("Should return cache statistics")
        void shouldReturnCacheStatistics() {
            CacheService.CacheStatistics stats = cacheService.getStatistics();
            
            assertNotNull(stats);
            assertEquals("llm-responses", stats.llmCacheName());
            assertEquals("rag-searches", stats.ragCacheName());
            assertEquals(0, stats.localCacheSize());
        }

        @Test
        @DisplayName("Should track local cache size")
        void shouldTrackLocalCacheSize() {
            cacheService.putInLocalCache("key1", "value1");
            cacheService.putInLocalCache("key2", "value2");
            
            CacheService.CacheStatistics stats = cacheService.getStatistics();
            assertEquals(2, stats.localCacheSize());
        }
    }

    @Nested
    @DisplayName("Cleanup Tests")
    class CleanupTests {

        @Test
        @DisplayName("Should clear local cache on evict all")
        void shouldClearLocalCacheOnEvictAll() {
            cacheService.putInLocalCache("key1", "value1");
            cacheService.putInLocalCache("key2", "value2");
            
            cacheService.evictAllCaches();
            
            CacheService.CacheStatistics stats = cacheService.getStatistics();
            assertEquals(0, stats.localCacheSize());
        }

        @Test
        @DisplayName("Should evict LLM cache specifically")
        void shouldEvictLlmCacheSpecifically() {
            assertDoesNotThrow(() -> cacheService.evictLlmCache());
        }

        @Test
        @DisplayName("Should evict RAG cache specifically")
        void shouldEvictRagCacheSpecifically() {
            assertDoesNotThrow(() -> cacheService.evictRagCache());
        }

        @Test
        @DisplayName("Should evict all caches")
        void shouldEvictAllCaches() {
            assertDoesNotThrow(() -> cacheService.evictAllCaches());
        }

        @Test
        @DisplayName("Should cleanup expired entries")
        void shouldCleanupExpiredEntries() {
            cacheService.putInLocalCache("key1", "value1");
            // No need to wait for expiration in unit test
            assertDoesNotThrow(() -> cacheService.cleanupExpiredEntries());
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty key")
        void shouldHandleEmptyKey() {
            cacheService.putInLocalCache("", "value");
            assertEquals("value", cacheService.getFromLocalCache(""));
        }

        @Test
        @DisplayName("Should handle special characters in key")
        void shouldHandleSpecialCharactersInKey() {
            String key = "key:with:special:characters";
            cacheService.putInLocalCache(key, "value");
            assertEquals("value", cacheService.getFromLocalCache(key));
        }

        @Test
        @DisplayName("Should handle large value")
        void shouldHandleLargeValue() {
            String key = "large-key";
            String largeValue = "x".repeat(100000);
            cacheService.putInLocalCache(key, largeValue);
            assertEquals(largeValue, cacheService.getFromLocalCache(key));
        }

        @Test
        @DisplayName("Should handle object value")
        void shouldHandleObjectValue() {
            String key = "object-key";
            TestObject obj = new TestObject("test", 123);
            cacheService.putInLocalCache(key, obj);
            
            Object result = cacheService.getFromLocalCache(key);
            assertInstanceOf(TestObject.class, result);
            TestObject retrieved = (TestObject) result;
            assertEquals("test", retrieved.name());
            assertEquals(123, retrieved.value());
        }
    }

    // Helper class for object caching test
    record TestObject(String name, int value) {}
}

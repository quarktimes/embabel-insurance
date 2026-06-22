package com.embabel.insurance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务，为 LLM 响应和 RAG 搜索结果提供多级缓存支持。
 *
 * <p>架构：
 * <ul>
 *   <li>一级缓存：Spring Cache（{@code llm-responses} 和 {@code rag-searches}）</li>
 *   <li>二级缓存：本地 ConcurrentHashMap（快速、短生命周期，TTL 5 分钟）</li>
 * </ul>
 *
 * <p>支持缓存的存入、查询、逐出和过期清理操作。
 */
@Service
@CacheConfig(cacheNames = {"llm-responses", "rag-searches"}, cacheManager = "cacheManager")
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    private final CacheManager cacheManager;
    
    /** 高频访问数据的本地内存缓存 */
    private final ConcurrentHashMap<String, CachedEntry> localCache = new ConcurrentHashMap<>();
    
    /** 本地缓存 TTL（分钟） */
    private static final int LOCAL_CACHE_TTL_MINUTES = 5;

    public CacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * 获取缓存的 LLM 响应。
     */
    @Cacheable(value = "llm-responses", key = "#prompt.hashCode()", unless = "#result == null")
    public String getCachedLlmResponse(String prompt) {
        logger.debug("Cache miss for LLM response: {}", prompt.substring(0, Math.min(50, prompt.length())));
        return null;
    }

    /**
     * 缓存 LLM 响应。
     */
    @CachePut(value = "llm-responses", key = "#prompt.hashCode()")
    public String cacheLlmResponse(String prompt, String response) {
        logger.debug("Caching LLM response for prompt: {}", prompt.substring(0, Math.min(50, prompt.length())));
        return response;
    }

    /**
     * 获取缓存的 RAG 搜索结果。
     */
    @Cacheable(value = "rag-searches", key = "#query.hashCode()", unless = "#result == null")
    public Object getCachedRagResults(String query) {
        logger.debug("Cache miss for RAG search: {}", query);
        return null;
    }

    /**
     * 缓存 RAG 搜索结果。
     */
    @CachePut(value = "rag-searches", key = "#query.hashCode()")
    public Object cacheRagResults(String query, Object results) {
        logger.debug("Caching RAG search results for query: {}", query);
        return results;
    }

    /**
     * 逐出所有 LLM 响应缓存。
     */
    @CacheEvict(value = "llm-responses", allEntries = true)
    public void evictLlmCache() {
        logger.info("Evicting all LLM response caches");
    }

    /**
     * 逐出所有 RAG 搜索缓存。
     */
    @CacheEvict(value = "rag-searches", allEntries = true)
    public void evictRagCache() {
        logger.info("Evicting all RAG search caches");
    }

    /**
     * 逐出全部缓存（包括 Spring Cache 和本地缓存）。
     */
    @CacheEvict(value = {"llm-responses", "rag-searches"}, allEntries = true)
    public void evictAllCaches() {
        logger.info("Evicting all caches");
        localCache.clear();
    }

    /**
     * 从本地内存缓存中获取（快速、短生命周期）。
     */
    public Object getFromLocalCache(String key) {
        CachedEntry entry = localCache.get(key);
        if (entry != null && !entry.isExpired(LOCAL_CACHE_TTL_MINUTES)) {
            logger.debug("Local cache hit for key: {}", key);
            return entry.getValue();
        }
        localCache.remove(key);
        return null;
    }

    /**
     * 存入本地内存缓存。
     */
    public void putInLocalCache(String key, Object value) {
        localCache.put(key, new CachedEntry(value, System.currentTimeMillis()));
        logger.debug("Added to local cache: {}", key);
    }

    /**
     * 获取缓存统计信息。
     */
    public CacheStatistics getStatistics() {
        Cache llmCache = cacheManager.getCache("llm-responses");
        Cache ragCache = cacheManager.getCache("rag-searches");
        
        return new CacheStatistics(
                llmCache != null ? llmCache.getName() : "N/A",
                ragCache != null ? ragCache.getName() : "N/A",
                localCache.size()
        );
    }

    /**
     * 清理过期的本地缓存条目。
     */
    public void cleanupExpiredEntries() {
        localCache.entrySet().removeIf(entry -> 
            entry.getValue().isExpired(LOCAL_CACHE_TTL_MINUTES));
        logger.debug("Local cache cleanup completed. Size: {}", localCache.size());
    }

    /**
     * 定时清理过期缓存条目（每 5 分钟执行一次）。
     */
    @Scheduled(fixedRate = 300_000)
    public void scheduledCleanup() {
        cleanupExpiredEntries();
    }

    /**
     * 带时间戳的缓存条目包装类。
     */
    private static class CachedEntry {
        private final Object value;
        private final long timestamp;

        public CachedEntry(Object value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public Object getValue() {
            return value;
        }

        public boolean isExpired(int ttlMinutes) {
            return System.currentTimeMillis() - timestamp > TimeUnit.MINUTES.toMillis(ttlMinutes);
        }
    }

    /**
     * 缓存统计信息记录。
     */
    public record CacheStatistics(String llmCacheName, String ragCacheName, int localCacheSize) {}
}
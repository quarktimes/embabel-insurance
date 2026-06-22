package com.embabel.insurance.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 缓存配置，为 LLM 响应和 RAG 搜索结果提供本地缓存支持。
 *
 * <p>使用 Spring ConcurrentMapCacheManager 管理两个缓存区域：
 * <ul>
 *   <li>{@code llm-responses} — LLM 生成结果缓存</li>
 *   <li>{@code rag-searches} — RAG 搜索结果缓存</li>
 * </ul>
 *
 * <p>定时缓存清理由 {@link com.embabel.insurance.service.CacheService#scheduledCleanup()}
 * 每 5 分钟自动执行。
 */
@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfiguration {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(
                "llm-responses",
                "rag-searches"
        );
    }

}
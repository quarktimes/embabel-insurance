package com.embabel.insurance.guardrail;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 速率限制服务，基于 Token Bucket 算法（Bucket4j）。
 *
 * <p>按用户 + API 分组进行限流，超过限制的请求返回 429。
 * 当前规则：
 * <ul>
 *   <li>聊天/助手 API：每分钟 30 次</li>
 *   <li>核保/理赔 API：每分钟 10 次</li>
 *   <li>其他 API：每分钟 60 次</li>
 * </ul>
 */
@Service
public class RateLimitingService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingService.class);

    /** 限流规则：按 API 分组名 → 每分钟允许次数 */
    private static final Map<String, Integer> LIMITS = Map.of(
            "chat", 30,
            "assistant", 30,
            "insurance", 10,
            "default", 60
    );

    /** userId#apiGroup → Bucket */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 检查请求是否在限流内。
     *
     * @param userId  认证用户 ID
     * @param apiPath 请求路径，用于识别 API 分组
     * @return true 如果在限流内，false 如果已超限
     */
    public boolean tryConsume(String userId, String apiPath) {
        String group = resolveGroup(apiPath);
        String key = userId + "#" + group;
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(group));
        boolean allowed = bucket.tryConsume(1);
        if (!allowed) {
            logger.warn("Rate limit exceeded: user={}, group={}", userId, group);
        }
        return allowed;
    }

    /**
     * 根据 API 路径识别所属分组。
     */
    private static String resolveGroup(String path) {
        if (path == null) return "default";
        if (path.contains("/api/chat")) return "chat";
        if (path.contains("/api/assistant")) return "assistant";
        if (path.contains("/api/insurance")) return "insurance";
        return "default";
    }

    /**
     * 创建指定分组的 Token Bucket。
     */
    private static Bucket createBucket(String group) {
        int limit = LIMITS.getOrDefault(group, 60);
        var bandwidth = Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}

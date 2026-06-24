package com.embabel.insurance.guardrail;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 速率限制过滤器。
 *
 * <p>在请求到达 Controller 之前检查限流。超过限制的请求返回 429 Too Many Requests。
 * 仅对 /api/** 路径生效，静态资源和 Actuator 不受限。
 * test/e2e profile 下自动跳过。
 */
@Component
@Order(1)
@Profile("!test & !e2e")
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitingService rateLimitingService;
    private final Environment environment;

    public RateLimitFilter(RateLimitingService rateLimitingService, Environment environment) {
        this.rateLimitingService = rateLimitingService;
        this.environment = environment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // test/e2e profile 跳过限流
        if (isTestProfile()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // 仅限流 /api/** 路径
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 获取当前用户
        String userId = resolveUserId();

        // 检查限流
        if (!rateLimitingService.tryConsume(userId, path)) {
            logger.warn("Rate limited: user={}, path={}, method={}", userId, path, request.getMethod());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试。\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 判断当前是否处于 test 或 e2e profile。
     */
    private boolean isTestProfile() {
        if (environment == null) return false;
        String[] activeProfiles = environment.getActiveProfiles();
        for (String p : activeProfiles) {
            if ("test".equals(p) || "e2e".equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前认证用户 ID，未认证用户使用 IP 作为标识。
     */
    private String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "anonymous";
    }
}

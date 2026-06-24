package com.embabel.insurance.guardrail;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 输入校验过滤器。
 *
 * <p>在请求到达 Controller 之前检查输入长度和基本合法性。
 * 仅对 /api/** 路径生效。
 */
@Component
@Order(2)
@Profile("!test & !e2e")
public class InputValidationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(InputValidationFilter.class);

    /** 最大输入长度（字节） */
    private static final long MAX_CONTENT_LENGTH = 5_000;

    /** 最大输入长度（字符，约等于 1 万个中文字符） */
    private static final int MAX_BODY_CHARS = 5_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 仅校验 /api/** 路径的 POST 请求
        if (!path.startsWith("/api/") || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. Content-Length 头快速检查
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_CONTENT_LENGTH) {
            logger.warn("Input too long (Content-Length): path={}, size={}", path, contentLength);
            reject(response, "输入内容过长，请限制在 10000 字符以内。");
            return;
        }

        // 2. GET 请求无 body，提前放行
        if (contentLength == 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 读取 body 检查实际字符数（处理 chunked 或未设置 Content-Length 的请求）
        String body = new String(request.getInputStream().readAllBytes(), request.getCharacterEncoding() != null
                ? request.getCharacterEncoding() : "UTF-8");

        // 检查字符数（非字节数）
        if (body.length() > MAX_BODY_CHARS) {
            logger.warn("Input too long (body): path={}, chars={}", path, body.length());
            reject(response, "输入内容过长，请限制在 10000 字符以内。");
            return;
        }

        // 包装 body 回 request（用 RequestBodyCachingFilter 或直接使用已读取的 body）
        // 此处 body 已读取，后续 filter/controller 无法再次读取 → 需要包装
        // 使用 CachedBodyHttpServletRequest 避免流被消费后下游无法读取
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, body);

        filterChain.doFilter(wrappedRequest, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}

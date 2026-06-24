package com.embabel.insurance.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全配置。
 *
 * <p>功能：
 * <ul>
 *   <li>HTTP Basic 认证</li>
 *   <li>基于角色的层级权限（ADMIN &gt; UNDERWRITER/CLAIMS &gt; USER）</li>
 *   <li>方法级安全（通过 {@code @PreAuthorize} 注解）</li>
 *   <li>Swagger 文档页面和健康检查端点免认证访问</li>
 * </ul>
 *
 * <p>用户数据存储在 MySQL 的 app_users 表中，
 * 由 {@link com.embabel.insurance.service.JpaUserDetailsService} 加载。
 * {@link com.embabel.insurance.config.DataInitializer} 在启动时自动初始化测试用户。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("!test & !e2e")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // 静态页面 — 允许无认证加载，前端 JS 自行处理登录
                .requestMatchers("/", "/index.html").permitAll()
                // Swagger 文档页
                .requestMatchers(
                        "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**", "/v3/api-docs.yaml"
                ).permitAll()
                // 登录接口（无需认证）
                .requestMatchers("/api/auth/login").permitAll()
                // 健康检查 + Actuator
                .requestMatchers("/api/insurance/health").permitAll()
                .requestMatchers("/actuator/health", "/actuator/metrics", "/actuator/prometheus").permitAll()
                // 业务 API — 需要认证
                .requestMatchers("/api/chat/**").authenticated()
                .requestMatchers("/api/assistant/**").authenticated()
                .requestMatchers("/api/insurance/**").authenticated()
                .anyRequest().authenticated()
            )
            // JWT 过滤器优先于 HTTP Basic
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(httpBasic -> httpBasic
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    })
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
            ADMIN > UNDERWRITER
            ADMIN > CLAIMS
            ADMIN > USER
            UNDERWRITER > USER
            CLAIMS > USER
            """);
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler expressionHandler = new DefaultMethodSecurityExpressionHandler();
        expressionHandler.setRoleHierarchy(roleHierarchy);
        return expressionHandler;
    }
}

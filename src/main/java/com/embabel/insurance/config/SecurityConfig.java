package com.embabel.insurance.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

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
 * <p>测试用户：
 * <ul>
 *   <li>user / password — USER 角色，可使用聊天和查看保单</li>
 *   <li>underwriter / underwriter — UNDERWRITER 角色，可处理核保和审批报价单</li>
 *   <li>claims / claims — CLAIMS 角色，可处理理赔和审核理赔单</li>
 *   <li>admin / admin — ADMIN 角色，拥有全部权限</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("!test & !e2e")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                // 健康检查
                .requestMatchers("/api/insurance/health").permitAll()
                // 业务 API — 需要认证
                .requestMatchers("/api/chat/**").authenticated()
                .requestMatchers("/api/assistant/**").authenticated()
                .requestMatchers("/api/insurance/**").authenticated()
                .anyRequest().authenticated()
            )
            .httpBasic(httpBasic -> httpBasic
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // 普通用户 — 可使用聊天和查看保单
        UserDetails user = User.builder()
            .username("user")
            .password(passwordEncoder().encode("password"))
            .roles("USER")
            .authorities("underwriting:read", "chat:use", "policies:read")
            .build();

        // 核保员 — 可处理核保请求、人工审批 REFERRED 报价单
        UserDetails underwriter = User.builder()
            .username("underwriter")
            .password(passwordEncoder().encode("underwriter"))
            .roles("UNDERWRITER")
            .authorities("underwriting:write", "underwriting:approve", "underwriting:read", "chat:use", "policies:read")
            .build();

        // 理赔员 — 可处理理赔请求、审核 INVESTIGATING 理赔单
        UserDetails claimsHandler = User.builder()
            .username("claims")
            .password(passwordEncoder().encode("claims"))
            .roles("CLAIMS")
            .authorities("claims:write", "claims:read", "claims:review", "chat:use", "policies:read")
            .build();

        // 管理员 — 拥有全部权限
        UserDetails admin = User.builder()
            .username("admin")
            .password(passwordEncoder().encode("admin"))
            .roles("ADMIN")
            .authorities("underwriting:write", "underwriting:approve", "underwriting:read",
                        "claims:write", "claims:read", "claims:review",
                        "policies:write", "policies:read",
                        "chat:use", "chat:admin",
                        "rag:admin")
            .build();

        return new InMemoryUserDetailsManager(user, underwriter, claimsHandler, admin);
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

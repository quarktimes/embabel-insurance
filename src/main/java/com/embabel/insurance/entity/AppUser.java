package com.embabel.insurance.entity;

import jakarta.persistence.*;
import java.util.List;

/**
 * 应用用户实体，存储认证信息和权限。
 *
 * <p>替代 SecurityConfig 中的 InMemoryUserDetailsManager 硬编码用户。
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    /** 用户角色，如 USER、ADMIN */
    @Column(nullable = false, length = 50)
    private String role;

    /** 权限列表，逗号分隔，如 "underwriting:write,policies:read,chat:use" */
    @Column(nullable = false, length = 500)
    private String authorities;

    public AppUser() {}

    public AppUser(String username, String password, String role, List<String> authorities) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.authorities = String.join(",", authorities);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getAuthorities() { return authorities; }
    public void setAuthorities(String authorities) { this.authorities = authorities; }
}

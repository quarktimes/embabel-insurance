package com.embabel.insurance.config;

import com.embabel.insurance.entity.AppUser;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.repository.AppUserRepository;
import com.embabel.insurance.repository.CustomerRepository;
import com.embabel.insurance.repository.VehicleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;

/**
 * 演示数据初始化器，在应用启动时自动插入测试用的客户和车辆数据。
 *
 * <p>预置三种风险等级的测试用户，覆盖核保全部决策路径：
 * <pre>
 * 风险评分明细：
 *   low-risk-user:   年龄 41(+0) + 驾龄 15(+0) + 1次事故(+15) + 2022年车(+0)  + 30万(+0)  = 15  → APPROVED
 *   medium-risk-user: 年龄 27(+15)+ 驾龄 4(+10) + 2次事故(+30) + 2018年车(+8)  + 18万(+0)  = 63  → REFERRED
 *   high-risk-user:   年龄 21(+25)+ 驾龄 1(+20) + 3次事故(+45) + 2013年车(+15) + 60万(+10) = 100 → DECLINED
 * </pre>
 */
@Configuration
public class DataInitializer {

    /**
     * 初始化演示数据，仅在数据库为空时执行。
     */
    @Bean
    public CommandLineRunner initData(CustomerRepository customerRepository, VehicleRepository vehicleRepository) {
        return args -> {
            if (customerRepository.count() == 0) {

                // ── 低风险（评分 15）→ 自动批准 ─────────────────
                Customer lowRisk = new Customer(
                        "low-risk-user",
                        "Alice Wang",
                        LocalDate.of(1985, 3, 15),   // 年龄 41
                        15,                            // 驾龄 15 年
                        1,                             // 1 次事故
                        "alice@example.com",
                        "13800000001"
                );
                customerRepository.save(lowRisk);

                vehicleRepository.save(new Vehicle(
                        "京A12345", "RAV4", "Toyota",
                        2022, 300_000, lowRisk
                ));

                // ── 中风险（评分 63）→ 转人工 ───────────
                Customer mediumRisk = new Customer(
                        "medium-risk-user",
                        "Bob Chen",
                        LocalDate.of(1999, 7, 20),    // 年龄 27
                        4,                             // 驾龄 4 年
                        2,                             // 2 次事故
                        "bob@example.com",
                        "13800000002"
                );
                customerRepository.save(mediumRisk);

                vehicleRepository.save(new Vehicle(
                        "京B67890", "Civic", "Honda",
                        2018, 180_000, mediumRisk
                ));

                // ── 高风险（评分 100）→ 拒绝 ───────────────────
                Customer highRisk = new Customer(
                        "high-risk-user",
                        "Charlie Zhang",
                        LocalDate.of(2005, 1, 10),    // 年龄 21
                        1,                             // 驾龄 1 年
                        3,                             // 3 次事故
                        "charlie@example.com",
                        "13800000003"
                );
                customerRepository.save(highRisk);

                vehicleRepository.save(new Vehicle(
                        "京C13579", "X5", "BMW",
                        2013, 600_000, highRisk
                ));

                // ── 兼容旧版的遗留用户 ────
                Customer legacyUser = new Customer(
                        "user",
                        "John Doe",
                        LocalDate.of(1985, 5, 15),
                        15, 2,
                        "john.doe@example.com",
                        "1234567890"
                );
                customerRepository.save(legacyUser);

                vehicleRepository.save(new Vehicle(
                        "京A88888", "RAV4", "Toyota",
                        2020, 250_000, legacyUser
                ));

                Customer legacyAdmin = new Customer(
                        "admin",
                        "Jane Smith",
                        LocalDate.of(1990, 10, 20),
                        8, 0,
                        "jane.smith@example.com",
                        "0987654321"
                );
                customerRepository.save(legacyAdmin);

                vehicleRepository.save(new Vehicle(
                        "京B99999", "Model 3", "Tesla",
                        2022, 450_000, legacyAdmin
                ));
            }
        };
    }

    /**
     * 初始化测试用户到 app_users 表（替代 SecurityConfig 的硬编码）。
     */
    @Bean
    public CommandLineRunner initUsers(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (appUserRepository.count() == 0) {
                appUserRepository.saveAll(List.of(
                        new AppUser("user", passwordEncoder.encode("password"), "USER",
                                List.of("underwriting:read", "chat:use", "policies:read")),
                        new AppUser("underwriter", passwordEncoder.encode("underwriter"), "UNDERWRITER",
                                List.of("underwriting:write", "underwriting:approve", "underwriting:read", "chat:use", "policies:read")),
                        new AppUser("claims", passwordEncoder.encode("claims"), "CLAIMS",
                                List.of("claims:write", "claims:read", "claims:review", "chat:use", "policies:read")),
                        new AppUser("admin", passwordEncoder.encode("admin"), "ADMIN",
                                List.of("underwriting:write", "underwriting:approve", "underwriting:read",
                                        "claims:write", "claims:read", "claims:review",
                                        "policies:write", "policies:read",
                                        "chat:use", "chat:admin", "rag:admin")),
                        new AppUser("low-risk-user", passwordEncoder.encode("password"), "USER",
                                List.of("underwriting:write", "underwriting:read", "policies:read", "chat:use")),
                        new AppUser("medium-risk-user", passwordEncoder.encode("password"), "USER",
                                List.of("underwriting:write", "underwriting:read", "policies:read", "chat:use")),
                        new AppUser("high-risk-user", passwordEncoder.encode("password"), "USER",
                                List.of("underwriting:write", "underwriting:read", "policies:read", "chat:use"))
                ));
            }
        };
    }
}
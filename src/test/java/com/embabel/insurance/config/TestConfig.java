package com.embabel.insurance.config;

import com.embabel.insurance.repository.CustomerRepository;
import com.embabel.insurance.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.CommandLineRunner;
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

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile({"test", "e2e"})
public class TestConfig {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Bean
    public CommandLineRunner testDataInitializer() {
        return new TestDataInitializer(customerRepository, vehicleRepository);
    }

    @Bean
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/insurance/health").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(httpBasic -> {});

        return http.build();
    }

    @Bean
    public UserDetailsService testUserDetailsService(PasswordEncoder encoder) {
        List<UserDetails> users = Arrays.asList(
                User.withUsername("low-risk-user")
                    .password(encoder.encode("password"))
                    .roles("USER")
                    .authorities("underwriting:write", "underwriting:read", "policies:read", "chat:use")
                    .build(),
                User.withUsername("medium-risk-user")
                    .password(encoder.encode("password"))
                    .roles("USER")
                    .authorities("underwriting:write", "underwriting:read", "policies:read", "chat:use")
                    .build(),
                User.withUsername("high-risk-user")
                    .password(encoder.encode("password"))
                    .roles("USER")
                    .authorities("underwriting:write", "underwriting:read", "policies:read", "chat:use")
                    .build(),
                User.withUsername("user")
                    .password(encoder.encode("password"))
                    .roles("USER")
                    .authorities("underwriting:read", "policies:read", "chat:use")
                    .build(),
                User.withUsername("underwriter")
                    .password(encoder.encode("underwriter"))
                    .roles("UNDERWRITER")
                    .authorities("underwriting:write", "underwriting:approve", "underwriting:read", "policies:read", "chat:use")
                    .build(),
                User.withUsername("claims")
                    .password(encoder.encode("claims"))
                    .roles("CLAIMS")
                    .authorities("claims:write", "claims:read", "claims:review", "policies:read", "chat:use")
                    .build(),
                User.withUsername("admin")
                    .password(encoder.encode("admin"))
                    .roles("ADMIN")
                    .authorities("underwriting:write", "underwriting:approve", "underwriting:read",
                                "claims:write", "claims:read", "claims:review",
                                "policies:write", "policies:read",
                                "chat:use", "chat:admin", "rag:admin")
                    .build()
        );
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public RoleHierarchy testRoleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
            ADMIN > UNDERWRITER
            ADMIN > CLAIMS
            ADMIN > USER
            UNDERWRITER > USER
            CLAIMS > USER
            """);
    }

    @Bean
    public MethodSecurityExpressionHandler testMethodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler expressionHandler = new DefaultMethodSecurityExpressionHandler();
        expressionHandler.setRoleHierarchy(roleHierarchy);
        return expressionHandler;
    }

    public static class TestDataInitializer implements CommandLineRunner {

        @SuppressWarnings("unused")
        private final CustomerRepository customerRepository;
        @SuppressWarnings("unused")
        private final VehicleRepository vehicleRepository;

        public TestDataInitializer(CustomerRepository customerRepository, VehicleRepository vehicleRepository) {
            this.customerRepository = customerRepository;
            this.vehicleRepository = vehicleRepository;
        }

        @Override
        public void run(String... args) {
            // CompleteE2ETest creates its own test data in @BeforeAll.
            // This initializer is a no-op for the e2e profile.
        }
    }
}

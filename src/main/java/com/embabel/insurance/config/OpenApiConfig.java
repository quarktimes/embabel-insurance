package com.embabel.insurance.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI（Swagger）文档配置，生成智能保险平台 API 文档页面。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI insurancePlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Insurance Platform API")
                        .description("""
                                Intelligent Insurance Platform powered by Embabel AI Agent Framework.
                                
                                ## Authentication
                                This API uses **HTTP Basic Auth**. Use your credentials to authenticate.
                                
                                ## Available Users (for testing)
                                | Username     | Password      | Role         | Permissions                          |
                                |-------------|---------------|--------------|--------------------------------------|
                                | user        | password      | USER         | underwriting:read, chat:use, policies:read |
                                | underwriter | underwriter   | UNDERWRITER  | underwriting:write, policies:read    |
                                | claims      | claims        | CLAIMS       | claims:write, policies:read          |
                                | admin       | admin         | ADMIN        | Full access, RAG admin               |
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Insurance Platform Team")
                                .email("support@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development Server")
                ))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("HTTP Basic Authentication")));
    }
}

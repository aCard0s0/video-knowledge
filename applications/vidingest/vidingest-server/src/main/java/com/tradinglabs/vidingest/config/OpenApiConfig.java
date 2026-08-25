package com.tradinglabs.vidingest.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.servlet.context-path:/vidingest}")
    private String contextPath;

    @Bean
    public OpenAPI vidingestOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("VidIngest API")
                        .description("Video ingestion service (REST + MCP).")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("TradingLabs Platform")
                                .email("dev@tradinglabs.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8051" + contextPath)
                                .description("Local Development Server"),
                        new Server()
                                .url("http://vidingest:8051" + contextPath)
                                .description("Docker Server")
                ));
    }
}


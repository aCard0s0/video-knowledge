package com.tradinglabs.vidingest.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the operator console from {@code classpath:/static} and forwards unknown paths to its
 * {@code index.html}.
 *
 * <p>The console is a single-page app: {@code /vidingest/runs/{id}} is a client route, not a file,
 * so without this fallback every deep link and every browser refresh outside the root 404s. The
 * console's filters, tabs and pagination all live in the query string precisely so those URLs can
 * be shared, which makes the fallback part of the contract rather than a nicety.
 *
 * <p>Nothing here exists unless a build actually placed {@code static/index.html} in the jar (the
 * Docker image does; a plain {@code mvn package} does not), and API, actuator and OpenAPI prefixes
 * are excluded so a mistyped endpoint still returns a 404 rather than a page of HTML.
 */
@Configuration
public class SpaStaticResourceConfig implements WebMvcConfigurer {

    private static final String INDEX = "static/index.html";

    /** Prefixes that must keep 404ing as JSON instead of falling through to the console. */
    private static final String[] SERVER_PREFIXES = {"api/", "actuator", "v3/", "swagger-ui"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!new ClassPathResource(INDEX).exists()) {
            return;
        }

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        for (String prefix : SERVER_PREFIXES) {
                            if (resourcePath.startsWith(prefix)) {
                                return null;
                            }
                        }
                        return new ClassPathResource(INDEX);
                    }
                });
    }
}

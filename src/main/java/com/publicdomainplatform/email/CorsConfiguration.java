package com.publicdomainplatform.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.net.URI;
import java.util.Arrays;

@Configuration
class CorsConfiguration implements WebMvcConfigurer {
    private static final String LOCAL_FRONTEND_ORIGIN = "http://localhost:8080";
    private final String[] allowedOrigins;
    private final String[] databaseOrigins;

    CorsConfiguration(@Value("${app.cors.allowed-origins}") String[] allowedOrigins,
                      @Value("${app.database-submissions.allowed-origins:}") String databaseOrigins) {
        this.allowedOrigins = allowedOrigins;
        String[] configuredOrigins = Arrays.stream(databaseOrigins.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).peek(origin -> {
                    if (!LOCAL_FRONTEND_ORIGIN.equals(origin)) requireHttpsOrigin(origin);
                }).toArray(String[]::new);
        this.databaseOrigins = Arrays.copyOf(configuredOrigins, configuredOrigins.length + 1);
        this.databaseOrigins[configuredOrigins.length] = LOCAL_FRONTEND_ORIGIN;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(databaseOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Turnstile-Token")
                .maxAge(3600);
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    private static void requireHttpsOrigin(String origin) {
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawPath() == null || !uri.getRawPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Database submission origins must be exact HTTPS origins");
        }
    }

    boolean allowsDatabaseOrigin(String origin) {
        return origin != null && Arrays.asList(databaseOrigins).contains(origin);
    }

    boolean allowsLocalDatabaseOrigin(String origin) {
        return LOCAL_FRONTEND_ORIGIN.equals(origin);
    }
}

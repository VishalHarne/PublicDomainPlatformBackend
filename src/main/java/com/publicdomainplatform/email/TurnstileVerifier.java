package com.publicdomainplatform.email;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class TurnstileVerifier {
    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);
    private final boolean enabled;
    private final String secret;
    private final RestClient client;

    @Autowired
    TurnstileVerifier(@Value("${app.turnstile.enabled:false}") boolean enabled,
                      @Value("${TURNSTILE_SECRET_KEY:}") String secret) {
        this(enabled, secret, createClient());
    }

    TurnstileVerifier(boolean enabled, String secret, RestClient client) {
        if (enabled && secret.isBlank()) {
            throw new IllegalStateException("TURNSTILE_SECRET_KEY is required when Turnstile is enabled");
        }
        this.enabled = enabled;
        this.secret = secret;
        this.client = client;
    }

    void verify(String token) {
        if (!enabled) {
            return;
        }
        if (token == null || token.isBlank() || token.length() > 2048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Turnstile token is required");
        }
        Verification result;
        try {
            result = client.post().uri("https://challenges.cloudflare.com/turnstile/v0/siteverify")
                    .body(Map.of("secret", secret, "response", token))
                    .retrieve().body(Verification.class);
        } catch (RestClientException ex) {
            log.warn("Turnstile verification service is unavailable");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verification service unavailable");
        }
        if (result == null || !result.success()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Turnstile verification failed");
        }
    }

    private static RestClient createClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    record Verification(boolean success) {
    }
}

package com.publicdomainplatform.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class DatabaseTurnstileVerifier {
    private final TurnstileVerifier verifier;

    DatabaseTurnstileVerifier(@Value("${TURNSTILE_SECRET_KEY:}") String secret) {
        verifier = secret.isBlank() ? null : new TurnstileVerifier(true, secret);
    }

    void verify(String token) {
        if (verifier == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verification service unavailable");
        }
        verifier.verify(token);
    }
}

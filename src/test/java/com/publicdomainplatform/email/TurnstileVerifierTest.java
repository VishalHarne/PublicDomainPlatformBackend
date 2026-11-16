package com.publicdomainplatform.email;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TurnstileVerifierTest {
    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Test
    void acceptsValidTokenAndSendsSecretToCloudflare() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"secret\":\"secret\",\"response\":\"token\"}"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        new TurnstileVerifier(true, "secret", builder.build()).verify("token");
        server.verify();
    }

    @Test
    void rejectsMissingInvalidAndOversizedTokens() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerifier verifier = new TurnstileVerifier(true, "secret", builder.build());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> verifier.verify(null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> verifier.verify("x".repeat(2049))).getStatusCode());
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> verifier.verify("invalid")).getStatusCode());
        server.verify();
    }

    @Test
    void failsClosedWhenVerificationServiceFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());
        TurnstileVerifier verifier = new TurnstileVerifier(true, "secret", builder.build());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> verifier.verify("token")).getStatusCode());
        server.verify();
    }

    @Test
    void requiresSecretWhenEnabledAndAllowsLocalDisablement() {
        RestClient client = RestClient.create();
        assertThrows(IllegalStateException.class, () -> new TurnstileVerifier(true, "", client));
        new TurnstileVerifier(false, "", client).verify(null);
    }
}

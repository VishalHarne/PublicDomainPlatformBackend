package com.publicdomainplatform.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "SMTP_HOST=localhost", "SMTP_USERNAME=test", "SMTP_PASSWORD=test",
        "MAIL_FROM=from@example.com", "MAIL_RECIPIENT=to@example.com",
        "TURNSTILE_SECRET_KEY=test-secret", "CORS_ALLOWED_ORIGINS=http://localhost:8080"
})
@ActiveProfiles("render")
@AutoConfigureMockMvc
class RenderProfileTest {
    @Autowired MockMvc mvc;
    @MockitoBean SubmissionMailService mailService;

    @Test
    void healthWorksRetrievalIsDisabledAndSubmissionsRequireTokens() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/getData").param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-02-01T00:00:00Z"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/submitgrievances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailService);
    }

    @Test
    void allowsTurnstileHeaderFromConfiguredWebsite() throws Exception {
        mvc.perform(options("/submitgrievances")
                        .header("Origin", "http://localhost:8080")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-turnstile-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }
}

package com.publicdomainplatform.email;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatabaseSubmissionController.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=https://old.example",
        "app.database-submissions.allowed-origins=https://site.example"
})
class DatabaseSubmissionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DatabaseTurnstileVerifier turnstile;
    @MockitoBean DatabaseSubmissionValidator validator;
    @MockitoBean DatabaseSubmissionService service;
    @MockitoBean ResumeStorage storage;
    @MockitoBean SubmissionConfirmationService confirmation;

    @Test
    void grievanceReturnsOnlyAfterServiceReturns() throws Exception {
        var grievance = new DatabaseSubmissionValidator.Grievance("नाम", "email", "a@example.com",
                "other", "message", "v1");
        when(validator.grievance(any())).thenReturn(grievance);
        UUID id = UUID.randomUUID();
        when(service.grievance(grievance)).thenReturn(id);
        when(confirmation.send("a@example.com", "grievance", id)).thenReturn("sent");
        mvc.perform(post("/api/grievances").secure(true).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Turnstile-Token", "fresh").content("{\"name\":\"नाम\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.emailStatus").value("sent"));
        verify(turnstile).verify("fresh");
        var order = inOrder(service, confirmation);
        order.verify(service).grievance(grievance);
        order.verify(confirmation).send("a@example.com", "grievance", id);
    }

    @Test
    void rejectsFailedTurnstileWithoutWriting() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Turnstile verification failed"))
                .when(turnstile).verify("bad");
        mvc.perform(post("/api/grievances").secure(true).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Turnstile-Token", "bad").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").exists());
        verifyNoInteractions(validator, service, storage, confirmation);
    }

    @Test
    void acceptsJsonBlobPartWithAndWithoutFile() throws Exception {
        var app = new DatabaseSubmissionValidator.Application("A", "+919876543210", null,
                "job_seeker", "Pune", "v1");
        when(validator.application(any())).thenReturn(app);
        UUID id = UUID.randomUUID();
        when(service.application(eq(app), nullable(ResumeStorage.Document.class))).thenReturn(id);
        when(confirmation.send(null, "application", id)).thenReturn("not_requested");
        for (boolean withFile : new boolean[]{false, true}) {
            MockPart data = new MockPart("data", "blob", "{}".getBytes(StandardCharsets.UTF_8));
            data.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            var request = multipart("/api/applications").part(data);
            if (withFile) request.file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}));
            mvc.perform(request.secure(true).header("X-Turnstile-Token", "fresh"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("submitted"))
                    .andExpect(jsonPath("$.emailStatus").value("not_requested"));
        }
        verify(service, times(2)).application(eq(app), nullable(ResumeStorage.Document.class));
        verify(confirmation, times(2)).send(null, "application", id);
    }

    @Test
    void applicationReturnsIdEvenWhenConfirmationFails() throws Exception {
        var app = new DatabaseSubmissionValidator.Application("A", "+919876543210", "a@example.com",
                "job_seeker", "Pune", "v1");
        UUID id = UUID.randomUUID();
        when(validator.application(any())).thenReturn(app);
        when(service.application(app, null)).thenReturn(id);
        when(confirmation.send("a@example.com", "application", id)).thenReturn("failed");
        MockPart data = new MockPart("data", "blob", "{}".getBytes(StandardCharsets.UTF_8));
        data.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        mvc.perform(multipart("/api/applications").part(data).secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(jsonPath("$.emailStatus").value("failed"));
        var order = inOrder(service, confirmation);
        order.verify(service).application(app, null);
        order.verify(confirmation).send("a@example.com", "application", id);
    }

    @Test
    void phoneGrievanceDoesNotRequestEmail() throws Exception {
        var grievance = new DatabaseSubmissionValidator.Grievance("A", "phone", "+919876543210",
                "other", "message", "v1");
        UUID id = UUID.randomUUID();
        when(validator.grievance(any())).thenReturn(grievance);
        when(service.grievance(grievance)).thenReturn(id);
        when(confirmation.send(null, "grievance", id)).thenReturn("not_requested");
        mvc.perform(post("/api/grievances").secure(true).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailStatus").value("not_requested"));
        verify(confirmation).send(null, "grievance", id);
    }

    @Test
    void doesNotSendConfirmationWhenDatabaseInsertFails() throws Exception {
        var grievance = new DatabaseSubmissionValidator.Grievance("A", "email", "a@example.com",
                "other", "message", "v1");
        when(validator.grievance(any())).thenReturn(grievance);
        when(service.grievance(grievance)).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database submission unavailable"));
        mvc.perform(post("/api/grievances").secure(true).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(confirmation);
    }

    @Test
    void enforcesHttpsAndCorsForNewRoutes() throws Exception {
        mvc.perform(post("/api/grievances").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/grievances").secure(true).header("Origin", "https://site.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-turnstile-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://site.example"));
        mvc.perform(options("/api/grievances").secure(true).header("Origin", "https://old.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsLocalFrontendHttpCorsAndRequestsFromLoopback() throws Exception {
        var grievance = new DatabaseSubmissionValidator.Grievance("Jane", "email", "jane@example.com",
                "other", "message", "v1");
        when(validator.grievance(any())).thenReturn(grievance);
        UUID id = UUID.randomUUID();
        when(service.grievance(grievance)).thenReturn(id);
        when(confirmation.send("jane@example.com", "grievance", id)).thenReturn("sent");

        mvc.perform(options("/api/grievances").header("Origin", "http://localhost:8080")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-turnstile-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("x-turnstile-token")));

        mvc.perform(post("/api/grievances").with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }).header("Origin", "http://localhost:8080")
                        .header("X-Turnstile-Token", "fresh")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Jane\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    void oversizeJsonReturns413WithCors() throws Exception {
        mvc.perform(post("/api/grievances").secure(true).contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://site.example").content("x".repeat(16_385)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").exists())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://site.example"));
    }
}

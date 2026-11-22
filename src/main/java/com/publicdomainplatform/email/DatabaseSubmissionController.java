package com.publicdomainplatform.email;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class DatabaseSubmissionController {
    private static final int MAX_JSON_BYTES = 16_384;
    private final DatabaseTurnstileVerifier turnstile;
    private final DatabaseSubmissionValidator validator;
    private final DatabaseSubmissionService service;
    private final ResumeStorage storage;
    private final ObjectMapper mapper;
    private final SubmissionConfirmationService confirmation;

    DatabaseSubmissionController(DatabaseTurnstileVerifier turnstile, DatabaseSubmissionValidator validator,
                                 DatabaseSubmissionService service, ResumeStorage storage, ObjectMapper mapper,
                                 SubmissionConfirmationService confirmation) {
        this.turnstile = turnstile;
        this.validator = validator;
        this.service = service;
        this.storage = storage;
        this.mapper = mapper;
        this.confirmation = confirmation;
    }

    @PostMapping(value = "/api/grievances", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> grievance(HttpServletRequest request,
                                 @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws IOException {
        turnstile.verify(token);
        var grievance = validator.grievance(readJson(request.getInputStream()));
        var id = service.grievance(grievance);
        String email = "email".equals(grievance.contactType()) ? grievance.contactValue() : null;
        return Map.of("id", id.toString(), "status", "received",
                "emailStatus", confirmation.send(email, "grievance", id));
    }

    @PostMapping(value = "/api/applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> application(@RequestPart("data") Part data,
                                    @RequestPart(value = "file", required = false) MultipartFile file,
                                    @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws IOException {
        turnstile.verify(token);
        var application = validator.application(readJson(data.getInputStream()));
        var document = storage.validate(file);
        var id = service.application(application, document);
        return Map.of("id", id.toString(), "status", "submitted",
                "emailStatus", confirmation.send(application.email(), "application", id));
    }

    private JsonNode readJson(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "JSON data is too large");
        }
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
    }
}

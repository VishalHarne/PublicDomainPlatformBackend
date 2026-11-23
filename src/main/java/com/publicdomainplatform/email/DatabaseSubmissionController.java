package com.publicdomainplatform.email;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
class DatabaseSubmissionController {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSubmissionController.class);
    private static final int MAX_JSON_BYTES = 16_384;
    private final DatabaseTurnstileVerifier turnstile;
    private final DatabaseSubmissionValidator validator;
    private final DatabaseSubmissionService service;
    private final ObjectMapper mapper;
    private final SubmissionConfirmationService confirmation;
    private final SubmissionMailService mailService;

    DatabaseSubmissionController(DatabaseTurnstileVerifier turnstile, DatabaseSubmissionValidator validator,
                                 DatabaseSubmissionService service, ObjectMapper mapper,
                                 SubmissionConfirmationService confirmation, SubmissionMailService mailService) {
        this.turnstile = turnstile;
        this.validator = validator;
        this.service = service;
        this.mapper = mapper;
        this.confirmation = confirmation;
        this.mailService = mailService;
    }

    @PostMapping(value = "/api/grievances", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> grievance(HttpServletRequest request,
                                 @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws IOException {
        turnstile.verify(token);
        var grievance = validator.grievance(readJson(request.getInputStream()));
        var id = service.grievance(grievance);
        return Map.of("id", id.toString(), "status", "received",
                "emailStatus", confirmation.send(grievance.email(), "grievance", id));
    }

    @PostMapping(value = "/api/applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> application(@RequestPart("data") Part data,
                                    @RequestPart(value = "file", required = false) MultipartFile file,
                                    @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws IOException {
        turnstile.verify(token);
        SubmissionController.validateResume(file);
        return saveApplication(readJson(data.getInputStream()), file);
    }

    @PostMapping(value = "/api/applications", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> applicationJson(HttpServletRequest request,
                                        @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws IOException {
        turnstile.verify(token);
        return saveApplication(readJson(request.getInputStream()), null);
    }

    private Map<String, String> saveApplication(JsonNode data, MultipartFile file) {
        var application = validator.application(data);
        var id = service.application(application);
        String resumeEmailStatus = sendResume(application, id, file);
        return Map.of("id", id.toString(), "status", "submitted",
                "emailStatus", confirmation.sendApplication(application, id),
                "resumeEmailStatus", resumeEmailStatus);
    }

    private String sendResume(DatabaseSubmissionValidator.Application application, UUID id, MultipartFile file) {
        if (file == null) return "not_requested";
        try {
            mailService.sendApplication(application, id, file);
            return "sent";
        } catch (MailException | MessagingException | IOException | IllegalArgumentException ex) {
            log.warn("Resume email was not confirmed for application {}: {}", id, ex.getClass().getSimpleName());
            return "failed";
        }
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

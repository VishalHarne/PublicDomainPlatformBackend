package com.publicdomainplatform.email;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.MessagingException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class SubmissionController {
    private final SubmissionMailService mailService;
    private final TurnstileVerifier turnstile;
    private final boolean retrievalEnabled;

    SubmissionController(SubmissionMailService mailService, TurnstileVerifier turnstile,
                         @Value("${app.retrieval.enabled:true}") boolean retrievalEnabled) {
        this.mailService = mailService;
        this.turnstile = turnstile;
        this.retrievalEnabled = retrievalEnabled;
    }

    @PostMapping(value = "/submitgrievances", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> submitGrievance(@RequestBody JsonNode data,
                                        @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws MessagingException, IOException {
        requireObject(data);
        turnstile.verify(token);
        return Map.of("id", mailService.send("grievance", data, null), "status", "sent");
    }

    @PostMapping(value = "/submitResume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> submitResume(@RequestPart("data") JsonNode data,
                                     @RequestPart(value = "file", required = false) MultipartFile file,
                                     @RequestHeader(value = "X-Turnstile-Token", required = false) String token)
            throws MessagingException, IOException {
        requireObject(data);
        validateResume(file);
        turnstile.verify(token);
        return Map.of("id", mailService.send("resume", data, file), "status", "sent");
    }

    static void validateResume(MultipartFile file) {
        if (file != null) {
            String filename = file.getOriginalFilename();
            if (file.isEmpty() || file.getSize() > 10L * 1024 * 1024 || filename == null
                    || filename.contains("/") || filename.contains("\\")
                    || !(filename.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    || filename.toLowerCase(Locale.ROOT).endsWith(".doc")
                    || filename.toLowerCase(Locale.ROOT).endsWith(".docx"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a non-empty PDF, DOC or DOCX up to 10 MB");
            }
        }
    }

    @GetMapping("/getData")
    List<SubmissionMailService.Submission> getData(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to)
            throws MessagingException, IOException {
        if (!retrievalEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'");
        }
        return mailService.find(from, to);
    }

    private void requireObject(JsonNode data) {
        if (data == null || !data.isObject() || data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data must be a non-empty JSON object");
        }
    }
}

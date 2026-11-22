package com.publicdomainplatform.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSubmissionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseSubmissionValidator validator = new DatabaseSubmissionValidator("g-mr-1,g-hi-1", "a-mr-1");

    @Test
    void normalizesIndianPhoneAndPreservesUnicode() throws Exception {
        var application = validator.application(mapper.readTree("""
                {"name":"श्री गणेश","phone":"9876543210","email":" person@example.com ",
                "role":"employer","town":"पुणे","consent":true,"consentTextVersion":"a-mr-1"}
                """));
        assertEquals("+919876543210", application.phone());
        assertEquals("श्री गणेश", application.name());
        assertEquals("person@example.com", application.email());
    }

    @Test
    void requiresValidApplicationEmail() throws Exception {
        String base = """
                {"name":"A","phone":"9876543210","email":"a@example.com",
                "role":"job_seeker","town":"Pune","consent":true,"consentTextVersion":"a-mr-1"}
                """;
        for (String invalid : new String[]{base.replace("\"email\":\"a@example.com\",", ""),
                base.replace("a@example.com", " "), base.replace("a@example.com", "invalid")}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> validator.application(mapper.readTree(invalid))).getStatusCode());
        }
    }

    @Test
    void rejectsInvalidInputsAndUnapprovedConsent() throws Exception {
        String base = """
                {"name":"A","phone":"9876543210","email":"hello@example.com",
                "topic":"other","message":"चांगला रस्ता","consent":true,"consentTextVersion":"g-mr-1"}
                """;
        var grievance = validator.grievance(mapper.readTree(base));
        assertEquals("hello@example.com", grievance.email());
        assertEquals("+919876543210", grievance.phone());
        for (String invalid : new String[] {
                base.replace("g-mr-1", "unknown"), base.replace("true", "false"),
                base.replace("hello@example.com", "bad"), base.replace("other", "other2"),
                base.replace("\"phone\":\"9876543210\",", ""), base.replace("9876543210", "1234"),
                base.replace("चांगला रस्ता", "1234")}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> validator.grievance(mapper.readTree(invalid))).getStatusCode());
        }
    }

    @Test
    void acceptsOptionalGrievanceEmail() throws Exception {
        String base = """
                {"name":"A","phone":"9876543210","email":"hello@example.com",
                "topic":"other","message":"Valid message","consent":true,"consentTextVersion":"g-mr-1"}
                """;
        assertNull(validator.grievance(mapper.readTree(base.replace("\"email\":\"hello@example.com\",", ""))).email());
        assertNull(validator.grievance(mapper.readTree(base.replace("\"hello@example.com\"", "null"))).email());
        assertNull(validator.grievance(mapper.readTree(base.replace("hello@example.com", "  "))).email());
    }

    @Test
    void failsClosedWhenConsentIsNotConfigured() throws Exception {
        var noVersions = new DatabaseSubmissionValidator("", "");
        var data = mapper.readTree("""
                {"name":"A","phone":"+919876543210","email":"a@example.com","role":"job_seeker","town":"Pune",
                "consent":true,"consentTextVersion":"x"}
                """);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> noVersions.application(data)).getStatusCode());
    }
}

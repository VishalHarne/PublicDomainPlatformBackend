package com.publicdomainplatform.email;

import java.util.Arrays;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class DatabaseSubmissionValidator {
    private static final Set<String> TOPICS = Set.of("roads_infrastructure", "water_agriculture",
            "public_services", "employment", "other");
    private static final Set<String> ROLES = Set.of("job_seeker", "employer");
    private final Set<String> grievanceVersions;
    private final Set<String> applicationVersions;
    private final PhoneNumberUtil phones = PhoneNumberUtil.getInstance();

    DatabaseSubmissionValidator(@Value("${app.database-submissions.grievance-consent-versions:}") String grievance,
                                @Value("${app.database-submissions.application-consent-versions:}") String application) {
        grievanceVersions = versions(grievance);
        applicationVersions = versions(application);
    }

    Grievance grievance(JsonNode data) {
        object(data);
        String name = required(data, "name", 200);
        String type = required(data, "contactType", 16);
        String contact = required(data, "contactValue", 254);
        if ("phone".equals(type)) {
            contact = phone(contact);
        } else if ("email".equals(type)) {
            contact = email(contact);
        } else {
            throw invalid("Invalid contactType");
        }
        String topic = required(data, "topic", 40);
        if (!TOPICS.contains(topic)) throw invalid("Invalid topic");
        String message = required(data, "message", 2000);
        if (length(message) < 5) throw invalid("message must contain at least 5 characters");
        return new Grievance(name, type, contact, topic, message, consent(data, grievanceVersions));
    }

    Application application(JsonNode data) {
        object(data);
        String name = required(data, "name", 200);
        String phone = phone(required(data, "phone", 40));
        String email = optional(data, "email", 254);
        if (email != null) email = email(email);
        String role = required(data, "role", 32);
        if (!ROLES.contains(role)) throw invalid("Invalid role");
        return new Application(name, phone, email, role, required(data, "town", 200),
                consent(data, applicationVersions));
    }

    private static String consent(JsonNode data, Set<String> versions) {
        if (!data.path("consent").isBoolean() || !data.path("consent").booleanValue()) {
            throw invalid("consent must be true");
        }
        String version = required(data, "consentTextVersion", 100);
        if (versions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consent configuration unavailable");
        }
        if (!versions.contains(version)) throw invalid("Unapproved consentTextVersion");
        return version;
    }

    private String phone(String value) {
        if (!value.matches("(?:\\+[0-9]{8,15}|[0-9]{10})")) {
            throw invalid("Phone must be E.164 or an unprefixed 10-digit Indian number");
        }
        try {
            PhoneNumber number = phones.parse(value, "IN");
            if (!phones.isValidNumber(number)) throw invalid("Invalid phone");
            return phones.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ex) {
            throw invalid("Invalid phone");
        }
    }

    private static String email(String value) {
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (!value.equals(address.getAddress()) || !value.contains("@") || value.contains("\n")
                    || value.contains("\r")) throw invalid("Invalid email");
            return value;
        } catch (AddressException ex) {
            throw invalid("Invalid email");
        }
    }

    private static String required(JsonNode data, String field, int maximum) {
        String value = optional(data, field, maximum);
        if (value == null) throw invalid(field + " is required");
        return value;
    }

    private static String optional(JsonNode data, String field, int maximum) {
        JsonNode node = data.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalid(field + " must be text");
        String value = node.textValue().strip();
        if (value.isEmpty()) return null;
        if (length(value) > maximum || value.indexOf('\0') >= 0) throw invalid("Invalid " + field);
        return value;
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void object(JsonNode data) {
        if (data == null || !data.isObject()) throw invalid("data must be a JSON object");
    }

    private static Set<String> versions(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record Grievance(String name, String contactType, String contactValue, String topic,
                     String message, String consentVersion) {}
    record Application(String name, String phone, String email, String role, String town,
                       String consentVersion) {}
}

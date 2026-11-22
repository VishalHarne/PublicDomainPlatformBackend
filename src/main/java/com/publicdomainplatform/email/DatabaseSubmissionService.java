package com.publicdomainplatform.email;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class DatabaseSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSubmissionService.class);
    private final String url;
    private final String username;
    private final String password;
    private final ResumeStorage storage;

    DatabaseSubmissionService(@Value("${app.database-submissions.jdbc-url:}") String url,
                              @Value("${app.database-submissions.db-user:}") String username,
                              @Value("${app.database-submissions.db-password:}") String password,
                              ResumeStorage storage) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.storage = storage;
    }

    UUID grievance(DatabaseSubmissionValidator.Grievance g) {
        return transaction(null, connection -> {
            UUID person = "phone".equals(g.contactType()) ? person(connection, g.contactValue(), g.name()) : null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO public.grievance_submissions
                    (person_id, submitted_name, contact_type, contact_value, topic, message,
                     consent_given, consented_at, consent_text_version)
                    VALUES (?, ?, ?::public.grievance_contact_type, ?, ?::public.grievance_topic, ?, true, now(), ?)
                    RETURNING grievance_id
                    """)) {
                statement.setObject(1, person);
                statement.setString(2, g.name());
                statement.setString(3, g.contactType());
                statement.setString(4, g.contactValue());
                statement.setString(5, g.topic());
                statement.setString(6, g.message());
                statement.setString(7, g.consentVersion());
                return id(statement);
            }
        });
    }

    UUID application(DatabaseSubmissionValidator.Application a, ResumeStorage.Document document) {
        String key = document == null ? null : storage.upload(document);
        return transaction(key, connection -> {
            UUID person = person(connection, a.phone(), a.name());
            UUID application;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO public.candidate_applications
                    (person_id, submitted_name, submitted_phone, submitted_email, role, town,
                     consent_given, consented_at, consent_text_version)
                    VALUES (?, ?, ?, ?, ?::public.candidate_role, ?, true, now(), ?)
                    RETURNING application_id
                    """)) {
                statement.setObject(1, person);
                statement.setString(2, a.name());
                statement.setString(3, a.phone());
                statement.setString(4, a.email());
                statement.setString(5, a.role());
                statement.setString(6, a.town());
                statement.setString(7, a.consentVersion());
                application = id(statement);
            }
            if (document != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO public.resume_documents
                        (application_id, storage_provider, bucket_name, object_key, original_filename,
                         media_type, size_bytes, sha256_hex)
                        VALUES (?, 'gcs', ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, application);
                    statement.setString(2, storage.bucket());
                    statement.setString(3, key);
                    statement.setString(4, document.filename());
                    statement.setString(5, document.mediaType());
                    statement.setLong(6, document.bytes().length);
                    statement.setString(7, document.sha256());
                    statement.executeUpdate();
                }
            }
            return application;
        });
    }

    private UUID person(Connection connection, String phone, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO public.people (mobile_e164, full_name) VALUES (?, ?)
                ON CONFLICT (mobile_e164) DO NOTHING RETURNING person_id
                """)) {
            insert.setString(1, phone);
            insert.setString(2, name);
            try (ResultSet result = insert.executeQuery()) {
                if (result.next()) return result.getObject(1, UUID.class);
            }
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT person_id FROM public.people WHERE mobile_e164 = ?")) {
            select.setString(1, phone);
            return id(select);
        }
    }

    private static UUID id(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Expected a returned identifier");
            return result.getObject(1, UUID.class);
        }
    }

    private UUID transaction(String key, Work work) {
        Connection connection = null;
        boolean commitAttempted = false;
        boolean cleanupSafe = true;
        try {
            connection = openConnection(url, username, password);
            connection.setAutoCommit(false);
            UUID id = work.run(connection);
            commitAttempted = true;
            connection.commit();
            return id;
        } catch (SQLException | RuntimeException ex) {
            if (commitAttempted) {
                cleanupSafe = false;
            } else if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    cleanupSafe = false;
                }
            }
            if (key != null) {
                if (cleanupSafe) storage.cleanup(key);
                else log.error("Resume object requires reconciliation after uncertain transaction: bucket={} key={}",
                        storage.bucket(), key);
            }
            log.warn("Database submission failed; transaction outcome {}", cleanupSafe ? "rolled back" : "uncertain");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database submission unavailable");
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    log.warn("Unable to close database connection");
                }
            }
        }
    }

    static Connection openConnection(String url, String username, String password) throws SQLException {
        if (!url.matches("jdbc:postgresql://[^/?#@]+/[^/?#]+") || username.isBlank() || password.isBlank()) {
            throw new SQLException("Database configuration unavailable");
        }
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        return DriverManager.getConnection(url, properties);
    }

    @FunctionalInterface
    private interface Work {
        UUID run(Connection connection) throws SQLException;
    }
}

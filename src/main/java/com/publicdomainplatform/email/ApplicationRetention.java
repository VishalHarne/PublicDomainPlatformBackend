package com.publicdomainplatform.email;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.database-submissions.retention-enabled", havingValue = "true")
class ApplicationRetention {
    private static final Logger log = LoggerFactory.getLogger(ApplicationRetention.class);
    private final String url;
    private final String username;
    private final String password;
    private final ResumeStorage storage;

    ApplicationRetention(@Value("${app.database-submissions.jdbc-url:}") String url,
                         @Value("${app.database-submissions.retention-db-user:}") String username,
                         @Value("${app.database-submissions.retention-db-password:}") String password,
                         ResumeStorage storage) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.storage = storage;
    }

    @Scheduled(fixedDelayString = "${app.database-submissions.retention-interval-ms:3600000}")
    void purgeExpired() {
        try {
            for (int i = 0; i < 100; i++) {
                if (!purgeOne()) break;
            }
        } catch (SQLException | RuntimeException ex) {
            log.error("Application retention failed; expired records require retry and monitoring");
        }
    }

    boolean purgeOne() throws SQLException {
        try (Connection connection = DatabaseSubmissionService.openConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                UUID id;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT application_id FROM public.candidate_applications
                        WHERE created_at <= now() - interval '1 year'
                        ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                        """); ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return false;
                    }
                    id = result.getObject(1, UUID.class);
                }
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT storage_provider, bucket_name, object_key
                        FROM public.resume_documents WHERE application_id = ?
                        """)) {
                    select.setObject(1, id);
                    try (ResultSet result = select.executeQuery()) {
                        while (result.next()) {
                            storage.deleteForRetention(result.getString(1), result.getString(2), result.getString(3));
                        }
                    }
                }
                delete(connection, "DELETE FROM public.resume_documents WHERE application_id = ?", id);
                delete(connection, "DELETE FROM public.candidate_application_status_history WHERE application_id = ?", id);
                delete(connection, "DELETE FROM public.candidate_applications WHERE application_id = ?", id);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                throw ex;
            }
        }
    }

    private static void delete(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }
}

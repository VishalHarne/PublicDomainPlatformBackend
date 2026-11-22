package com.publicdomainplatform.email;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseSubmissionServiceTest {
    private final ResumeStorage storage = mock(ResumeStorage.class);
    private final DatabaseSubmissionService service = new DatabaseSubmissionService(
            "jdbc:postgresql://db.example/submissions", "user", "password", storage);
    private final DatabaseSubmissionValidator.Application app = new DatabaseSubmissionValidator.Application(
            "नाम", "+919876543210", null, "job_seeker", "पुणे", "v1");
    private final ResumeStorage.Document document = new ResumeStorage.Document(
            "a.pdf", "application/pdf", new byte[]{1}, "a".repeat(64));

    @Test
    void rollsBackAndDeletesUploadWhenInsertFails() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("failure"));
        when(storage.upload(document)).thenReturn("key");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any())).thenReturn(connection);
            assertThrows(ResponseStatusException.class, () -> service.application(app, document));
            verify(connection).rollback();
            verify(storage).cleanup("key");
            verify(connection, never()).commit();
        }
    }

    @Test
    void doesNotDeletePossiblyCommittedFileWhenCommitFails() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
        doThrow(new SQLException("connection lost")).when(connection).commit();
        when(storage.upload(document)).thenReturn("key");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any())).thenReturn(connection);
            assertThrows(ResponseStatusException.class, () -> service.application(app, document));
            verify(storage, never()).cleanup(anyString());
            verify(connection, never()).rollback();
        }
    }

    @Test
    void resolvesExistingPersonWithoutUpdatingIt() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement insertPerson = mock(PreparedStatement.class);
        PreparedStatement selectPerson = mock(PreparedStatement.class);
        PreparedStatement insertApplication = mock(PreparedStatement.class);
        ResultSet inserted = mock(ResultSet.class);
        ResultSet existing = mock(ResultSet.class);
        ResultSet application = mock(ResultSet.class);
        UUID personId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(connection.prepareStatement(anyString())).thenReturn(insertPerson, selectPerson, insertApplication);
        when(insertPerson.executeQuery()).thenReturn(inserted);
        when(inserted.next()).thenReturn(false);
        when(selectPerson.executeQuery()).thenReturn(existing);
        when(existing.next()).thenReturn(true);
        when(existing.getObject(1, UUID.class)).thenReturn(personId);
        when(insertApplication.executeQuery()).thenReturn(application);
        when(application.next()).thenReturn(true);
        when(application.getObject(1, UUID.class)).thenReturn(applicationId);
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any())).thenReturn(connection);
            assertEquals(applicationId, service.application(app, null));
            verify(connection).commit();
            verify(insertApplication).setObject(1, personId);
            verify(insertApplication).setString(3, "+919876543210");
            verify(connection, never()).prepareStatement(startsWith("UPDATE"));
        }
    }
}

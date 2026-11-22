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
    private final DatabaseSubmissionService service = new DatabaseSubmissionService(
            "jdbc:postgresql://db.example/submissions", "user", "password");
    private final DatabaseSubmissionValidator.Application app = new DatabaseSubmissionValidator.Application(
            "नाम", "+919876543210", "a@example.com", "job_seeker", "पुणे", "v1");

    @Test
    void rollsBackWhenInsertFails() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("failure"));
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any())).thenReturn(connection);
            assertThrows(ResponseStatusException.class, () -> service.application(app));
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
    }

    @Test
    void doesNotRollbackWhenCommitOutcomeIsUncertain() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
        doThrow(new SQLException("connection lost")).when(connection).commit();
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any())).thenReturn(connection);
            assertThrows(ResponseStatusException.class, () -> service.application(app));
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
            assertEquals(applicationId, service.application(app));
            verify(connection).commit();
            verify(insertApplication).setObject(1, personId);
            verify(insertApplication).setString(3, "+919876543210");
            verify(insertApplication).setString(4, "a@example.com");
            verify(connection, times(3)).prepareStatement(anyString());
            verify(connection, never()).prepareStatement(contains("resume_documents"));
            verify(connection, never()).prepareStatement(startsWith("UPDATE"));
        }
    }

    @Test
    void savesGrievancePhoneAndOptionalEmailSnapshot() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement insertPerson = mock(PreparedStatement.class);
        PreparedStatement insertGrievance = mock(PreparedStatement.class);
        ResultSet person = mock(ResultSet.class);
        ResultSet grievance = mock(ResultSet.class);
        UUID personId = UUID.randomUUID();
        UUID grievanceId = UUID.randomUUID();
        when(connection.prepareStatement(anyString())).thenReturn(insertPerson, insertGrievance);
        when(insertPerson.executeQuery()).thenReturn(person);
        when(person.next()).thenReturn(true);
        when(person.getObject(1, UUID.class)).thenReturn(personId);
        when(insertGrievance.executeQuery()).thenReturn(grievance);
        when(grievance.next()).thenReturn(true);
        when(grievance.getObject(1, UUID.class)).thenReturn(grievanceId);
        var data = new DatabaseSubmissionValidator.Grievance("A", "+919876543210", "a@example.com",
                "other", "message", "v1");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any())).thenReturn(connection);
            assertEquals(grievanceId, service.grievance(data));
            verify(insertPerson).setString(1, "+919876543210");
            verify(insertGrievance).setObject(1, personId);
            verify(insertGrievance).setString(3, "+919876543210");
            verify(insertGrievance).setString(4, "a@example.com");
            verify(connection).commit();
        }
    }
}

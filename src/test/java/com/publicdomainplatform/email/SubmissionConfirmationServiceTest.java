package com.publicdomainplatform.email;

import java.util.UUID;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubmissionConfirmationServiceTest {
    private final MailSettings settings = new MailSettings("sender@example.com", "admin@example.com",
            "", 993, "", "", "INBOX");

    @Test
    void sendsEachDatabaseIdToItsRecipient() throws Exception {
        GreenMail server = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        try {
            server.start();
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(server.getSmtp().getPort());
            var service = new SubmissionConfirmationService(sender, settings);
            UUID grievanceId = UUID.randomUUID();
            UUID applicationId = UUID.randomUUID();
            assertEquals("sent", service.send("grievance@example.com", "grievance", grievanceId));
            assertEquals("sent", service.send("application@example.com", "application", applicationId));
            assertTrue(server.waitForIncomingEmail(5000, 2));
            var messages = server.getReceivedMessages();
            assertEquals("grievance@example.com", messages[0].getAllRecipients()[0].toString());
            assertTrue(messages[0].getSubject().contains("grievance"));
            assertTrue(messages[0].getContent().toString().contains(grievanceId.toString()));
            assertEquals("application@example.com", messages[1].getAllRecipients()[0].toString());
            assertTrue(messages[1].getSubject().contains("application"));
            assertTrue(messages[1].getContent().toString().contains(applicationId.toString()));
        } finally {
            server.stop();
        }
    }

    @Test
    void reportsMissingEmailOrMailFailure() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var service = new SubmissionConfirmationService(sender, settings);
        assertEquals("not_requested", service.send(null, "grievance", UUID.randomUUID()));
        verifyNoInteractions(sender);
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(SimpleMailMessage.class));
        assertEquals("failed", service.send("a@example.com", "application", UUID.randomUUID()));
    }
}

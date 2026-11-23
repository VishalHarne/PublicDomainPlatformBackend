package com.publicdomainplatform.email;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SubmissionMailServiceTest {
    @Test
    void sendsApplicationAndResumeToConfiguredRecipient() throws Exception {
        GreenMail server = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        try {
            server.start();
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(server.getSmtp().getPort());
            MailSettings settings = new MailSettings("sender@example.com", "admin@example.com",
                    "", 993, "", "", "INBOX");
            SubmissionMailService service = new SubmissionMailService(sender, settings, new ObjectMapper());
            UUID id = UUID.randomUUID();
            var application = new DatabaseSubmissionValidator.Application("Candidate", "+919876543210",
                    "candidate@example.com", "job_seeker", "Pune", "v1");
            service.sendApplication(application, id,
                    new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1, 2}));
            assertTrue(server.waitForIncomingEmail(5000, 1));
            var message = server.getReceivedMessages()[0];
            assertEquals("admin@example.com", message.getAllRecipients()[0].toString());
            assertTrue(message.getSubject().contains(id.toString()));
            jakarta.mail.Multipart content = (jakarta.mail.Multipart) message.getContent();
            jakarta.mail.Multipart body = (jakarta.mail.Multipart) content.getBodyPart(0).getContent();
            assertTrue(body.getBodyPart(0).getContent().toString().contains("candidate@example.com"));
            assertEquals("resume.pdf", content.getBodyPart(1).getFileName());
            assertArrayEquals(new byte[]{1, 2}, content.getBodyPart(1).getInputStream().readAllBytes());
        } finally {
            server.stop();
        }
    }

    @Test
    void sendsAndReadsOnlySubmissionsWithoutMarkingThemRead(@TempDir Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("mail-test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "mail", "-keyalg", "RSA",
                "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-keypass", "changeit", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "1", "-noprompt").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = java.nio.file.Files.newInputStream(keystore)) {
            keys.load(input, "changeit".toCharArray());
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("mail", keys.getCertificate("mail"));
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext oldContext = SSLContext.getDefault();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        String oldKeystore = System.getProperty("greenmail.tls.keystore.file");
        SSLContext.setDefault(context);
        System.setProperty("greenmail.tls.keystore.file", keystore.toString());
        GreenMail server = new GreenMail(new ServerSetup[]{
                new ServerSetup(0, "127.0.0.1", "smtp"),
                new ServerSetup(0, "127.0.0.1", "imaps")
        });
        try {
            server.start();
            server.setUser("inbox@example.com", "inbox@example.com", "password");
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(server.getSmtp().getPort());
            MailSettings settings = new MailSettings("sender@example.com", "inbox@example.com",
                    "localhost", server.getImaps().getPort(), "inbox@example.com", "password", "INBOX");
            ObjectMapper mapper = new ObjectMapper();
            SubmissionMailService service = new SubmissionMailService(sender, settings, mapper);

            Instant before = Instant.now().minus(10, ChronoUnit.SECONDS);
            service.send("grievance", mapper.readTree("{\"message\":\"Road repair\"}"), null);
            service.send("resume", mapper.readTree("{\"name\":\"Candidate\"}"),
                    new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1, 2}));
            SimpleMailMessage unrelated = new SimpleMailMessage();
            unrelated.setFrom("sender@example.com");
            unrelated.setTo("inbox@example.com");
            unrelated.setSubject("Unrelated");
            unrelated.setText("Not a submission");
            sender.send(unrelated);
            assertTrue(server.waitForIncomingEmail(5000, 3));

            List<SubmissionMailService.Submission> results = service.find(before, Instant.now().plusSeconds(10));
            assertEquals(2, results.size());
            SubmissionMailService.Submission resume = results.stream()
                    .filter(item -> item.type().equals("resume")).findFirst().orElseThrow();
            assertEquals("Candidate", resume.data().get("name").asText());
            assertEquals(List.of("resume.pdf"), resume.attachments());
            assertEquals(results.stream().filter(item -> item.receivedAt().equals(resume.receivedAt())).count(),
                    service.find(resume.receivedAt(), resume.receivedAt().plusMillis(1)).size());
            assertEquals(results.stream().filter(item -> item.receivedAt().isBefore(resume.receivedAt())).count(),
                    service.find(before, resume.receivedAt()).size());

            Store store = Session.getInstance(new Properties()).getStore("imaps");
            store.connect("localhost", server.getImaps().getPort(), "inbox@example.com", "password");
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            assertFalse(folder.getMessage(1).isSet(Flags.Flag.SEEN));
            folder.close(false);
            store.close();
        } finally {
            server.stop();
            SSLContext.setDefault(oldContext);
            if (oldKeystore == null) {
                System.clearProperty("greenmail.tls.keystore.file");
            } else {
                System.setProperty("greenmail.tls.keystore.file", oldKeystore);
            }
        }
    }
}

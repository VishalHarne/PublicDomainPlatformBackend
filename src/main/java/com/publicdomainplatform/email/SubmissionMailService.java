package com.publicdomainplatform.email;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class SubmissionMailService {
    static final String TYPE_HEADER = "X-Public-Domain-Submission";
    private final JavaMailSender sender;
    private final MailSettings settings;
    private final ObjectMapper mapper;

    SubmissionMailService(JavaMailSender sender, MailSettings settings, ObjectMapper mapper) {
        this.sender = sender;
        this.settings = settings;
        this.mapper = mapper;
    }

    String send(String type, JsonNode data, MultipartFile attachment) throws MessagingException, IOException {
        String id = UUID.randomUUID().toString();
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(settings.from());
        helper.setTo(settings.recipient());
        helper.setSubject("Public Domain Platform - " + type + " - " + id);
        helper.setText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data), false);
        message.setHeader(TYPE_HEADER, type);
        if (attachment != null) {
            helper.addAttachment(attachment.getOriginalFilename(), attachment);
        }
        sender.send(message);
        return id;
    }

    List<Submission> find(Instant from, Instant to) throws MessagingException, IOException {
        Properties properties = new Properties();
        properties.setProperty("mail.imaps.connectiontimeout", "10000");
        properties.setProperty("mail.imaps.timeout", "10000");
        properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        properties.setProperty("mail.imaps.peek", "true");
        Store store = Session.getInstance(properties).getStore("imaps");
        try {
            store.connect(settings.imapHost(), settings.imapPort(),
                    settings.imapUsername(), settings.imapPassword());
            Folder folder = store.getFolder(settings.imapFolder());
            try {
                folder.open(Folder.READ_ONLY);
                // IMAP date searches operate on calendar days; filter exact instants below.
                Message[] messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE,
                        Date.from(from.minus(2, ChronoUnit.DAYS))));
                List<Submission> results = new ArrayList<>();
                for (Message message : messages) {
                    String[] types = message.getHeader(TYPE_HEADER);
                    Date received = message.getReceivedDate();
                    if (types == null || types.length != 1
                            || !(types[0].equals("grievance") || types[0].equals("resume"))
                            || received == null) {
                        continue;
                    }
                    Instant receivedAt = received.toInstant();
                    if (receivedAt.isBefore(from) || !receivedAt.isBefore(to)) {
                        continue;
                    }
                    List<String> attachments = new ArrayList<>();
                    String body = readContent(message, attachments);
                    if (body == null) {
                        throw new MessagingException("Submission email has no text body");
                    }
                    results.add(new Submission(types[0], receivedAt, mapper.readTree(body), attachments));
                }
                results.sort(Comparator.comparing(Submission::receivedAt).reversed());
                return results;
            } finally {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            }
        } finally {
            if (store.isConnected()) {
                store.close();
            }
        }
    }

    private String readContent(Part part, List<String> attachments) throws MessagingException, IOException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            attachments.add(part.getFileName());
            return null;
        }
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String body = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = readContent(multipart.getBodyPart(i), attachments);
                if (body == null && text != null) {
                    body = text;
                }
            }
            return body;
        }
        return null;
    }

    record Submission(String type, Instant receivedAt, JsonNode data, List<String> attachments) {
    }
}

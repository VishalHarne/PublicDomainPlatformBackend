package com.publicdomainplatform.email;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
class SubmissionConfirmationService {
    private static final Logger log = LoggerFactory.getLogger(SubmissionConfirmationService.class);
    private final JavaMailSender sender;
    private final MailSettings settings;

    SubmissionConfirmationService(JavaMailSender sender, MailSettings settings) {
        this.sender = sender;
        this.settings = settings;
    }

    String send(String recipient, String type, UUID id) {
        if (recipient == null) {
            return "not_requested";
        }
        return sendMessage(recipient, type, id, "Hello,\n\nYour " + type + " has been submitted successfully.\n"
                + "Your unique " + type + " ID is: " + id + "\n\n"
                + "Please keep this ID for your records.\n\nThank you.");
    }

    String sendApplication(DatabaseSubmissionValidator.Application application, UUID id) {
        return sendMessage(application.email(), "application", id,
                "Hello " + application.name() + ",\n\nYour application has been submitted successfully.\n"
                + "Your candidate application ID is: " + id + "\n"
                + "Role: " + application.role() + "\nTown: " + application.town() + "\n"
                + "Status: submitted\n\nPlease keep this ID for your records.\n\nThank you.");
    }

    private String sendMessage(String recipient, String type, UUID id, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(recipient);
        message.setSubject("Public Domain Platform - " + type + " submission confirmation");
        message.setText(text);
        try {
            sender.send(message);
            return "sent";
        } catch (MailException ex) {
            log.warn("Confirmation email was not confirmed for {} submission {}: {}",
                    type, id, ex.getClass().getSimpleName());
            return "failed";
        }
    }
}

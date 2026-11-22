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
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(recipient);
        message.setSubject("Public Domain Platform - " + type + " submission confirmation");
        message.setText("Hello,\n\nYour " + type + " has been submitted successfully.\n"
                + "Your unique " + type + " ID is: " + id + "\n\n"
                + "Please keep this ID for your records.\n\nThank you.");
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

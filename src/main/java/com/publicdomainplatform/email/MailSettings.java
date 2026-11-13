package com.publicdomainplatform.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "app.mail")
record MailSettings(String from, String recipient, String imapHost, int imapPort,
                    String imapUsername, String imapPassword, String imapFolder) {
}

@Configuration
@EnableConfigurationProperties(MailSettings.class)
class MailSettingsConfiguration {
}

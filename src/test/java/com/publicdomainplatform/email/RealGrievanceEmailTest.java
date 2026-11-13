package com.publicdomainplatform.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "SEND_REAL_TEST_EMAIL", matches = "true")
@SpringBootTest(properties = {
        "app.mail.imap-host=${IMAP_HOST:localhost}",
        "app.mail.imap-username=${IMAP_USERNAME:unused}",
        "app.mail.imap-password=${IMAP_PASSWORD:unused}"
})
@AutoConfigureMockMvc
class RealGrievanceEmailTest {
    @Autowired MockMvc mvc;

    @Test
    void sendsGrievanceToConfiguredMailbox() throws Exception {
        mvc.perform(post("/submitgrievances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test User","contact":"test@example.com",
                                 "message":"This is a test grievance. No action is required."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(isEmptyOrNullString())))
                .andExpect(jsonPath("$.status").value("sent"));
    }
}

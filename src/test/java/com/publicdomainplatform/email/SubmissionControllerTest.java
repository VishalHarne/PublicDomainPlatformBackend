package com.publicdomainplatform.email;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubmissionController.class)
class SubmissionControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean SubmissionMailService service;

    @Test
    void sendsGrievanceJson() throws Exception {
        when(service.send(eq("grievance"), any(), isNull())).thenReturn("123");
        mvc.perform(post("/submitgrievances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"message\":\"Road repair\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("123"))
                .andExpect(jsonPath("$.status").value("sent"));
        verify(service).send(eq("grievance"), eq(mapper.readTree("{\"name\":\"A\",\"message\":\"Road repair\"}")), isNull());
    }

    @Test
    void rejectsEmptyOrNonObjectData() throws Exception {
        mvc.perform(post("/submitgrievances").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/submitgrievances").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendsResumeWithOrWithoutAttachment() throws Exception {
        MockMultipartFile data = new MockMultipartFile("data", "", "application/json",
                "{\"name\":\"A\"}".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1, 2});
        when(service.send(eq("resume"), any(), any())).thenReturn("123");
        mvc.perform(multipart("/submitResume").file(data).file(file))
                .andExpect(status().isOk());
        mvc.perform(multipart("/submitResume").file(data))
                .andExpect(status().isOk());
        verify(service).send(eq("resume"), any(), isNull());
    }

    @Test
    void rejectsUnsupportedAttachment() throws Exception {
        MockMultipartFile data = new MockMultipartFile("data", "", "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile("file", "resume.exe", "application/octet-stream", new byte[]{1});
        mvc.perform(multipart("/submitResume").file(data).file(file)).andExpect(status().isBadRequest());
        MockMultipartFile validData = new MockMultipartFile("data", "", "application/json", "{\"name\":\"A\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/submitResume").file(validData).file(file)).andExpect(status().isBadRequest());
    }

    @Test
    void retrievesPeriodAndRejectsReversedRange() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(service.find(from, to)).thenReturn(List.of(new SubmissionMailService.Submission(
                "grievance", from, mapper.readTree("{\"name\":\"A\"}"), List.of())));
        mvc.perform(get("/getData").param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].data.name").value("A"));
        mvc.perform(get("/getData").param("from", to.toString()).param("to", from.toString()))
                .andExpect(status().isBadRequest());
    }
}

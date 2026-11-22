package com.publicdomainplatform.email;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class ResumeStorageTest {
    private final ResumeStorage storage = new ResumeStorage("bucket");

    @Test
    void acceptsRealPdfAndRejectsRenamedFiles() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(output);
        }
        var file = new MockMultipartFile("file", "resume.pdf", "application/octet-stream", output.toByteArray());
        var document = storage.validate(file);
        assertEquals("application/pdf", document.mediaType());
        assertEquals(64, document.sha256().length());
        assertThrows(ResponseStatusException.class, () -> storage.validate(
                new MockMultipartFile("file", "fake.pdf", "application/pdf", "not PDF".getBytes())));
        assertNull(storage.validate(null));
    }

    @Test
    void requiresRealDocxStructureAndSafeXml() throws Exception {
        String types = """
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Override PartName="/word/document.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String document = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body/></w:document>
                """;
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                storage.validate(docx(types, document)).mediaType());
        assertThrows(ResponseStatusException.class, () -> storage.validate(docx(types, "not xml")));
        assertThrows(ResponseStatusException.class, () -> storage.validate(docx(types,
                "<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///secret'>]><x>&x;</x>")));
    }

    private static MockMultipartFile docx(String types, String document) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(types.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(document.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("file", "resume.docx", "text/plain", output.toByteArray());
    }
}

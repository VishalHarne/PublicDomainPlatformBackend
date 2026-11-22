package com.publicdomainplatform.email;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.apache.pdfbox.Loader;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@Component
class ResumeStorage {
    private static final Logger log = LoggerFactory.getLogger(ResumeStorage.class);
    private static final int MAX_FILE = 10_485_760;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private final String bucket;

    ResumeStorage(@Value("${app.database-submissions.gcs-bucket:}") String bucket) {
        this.bucket = bucket;
    }

    Document validate(MultipartFile file) throws IOException {
        if (file == null) return null;
        if (file.getSize() < 1 || file.getSize() > MAX_FILE) {
            throw new ResponseStatusException(file.getSize() > MAX_FILE
                    ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST, "Invalid file size");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/")
                || filename.contains("\\") || filename.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_FILE || bytes.length != file.getSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file size");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        String media;
        try {
            if (lower.endsWith(".pdf")) {
                if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("%PDF-")) throw new IOException("Invalid PDF");
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.getNumberOfPages() < 1) throw new IOException("Empty PDF");
                }
                media = "application/pdf";
            } else if (lower.endsWith(".doc")) {
                try (var ole = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
                    if (!ole.getRoot().hasEntry("WordDocument")) throw new IOException("Not a Word document");
                    try (var input = ole.createDocumentInputStream("WordDocument")) {
                        if (input.read() != 0xec || input.read() != 0xa5) throw new IOException("Invalid Word document");
                    }
                }
                media = "application/msword";
            } else if (lower.endsWith(".docx")) {
                validateDocx(bytes);
                media = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else {
                throw new IOException("Unsupported file");
            }
        } catch (IOException | RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File content is not a valid PDF, DOC or DOCX");
        }
        try {
            return new Document(filename, media, bytes, HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void validateDocx(byte[] bytes) throws IOException {
        byte[] document = null;
        byte[] contentTypes = null;
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 1000) throw new IOException("Too many entries");
                boolean isTypes = entry.getName().equals("[Content_Types].xml");
                boolean isDocument = entry.getName().equals("word/document.xml");
                if ((isTypes && contentTypes != null) || (isDocument && document != null)) {
                    throw new IOException("Duplicate document entry");
                }
                ByteArrayOutputStream selected = isTypes || isDocument ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > MAX_EXPANDED) throw new IOException("Archive too large");
                    if (selected != null) {
                        if (selected.size() + count > (isTypes ? 1_000_000 : 16_000_000)) {
                            throw new IOException("Document XML too large");
                        }
                        selected.write(buffer, 0, count);
                    }
                }
                if (isTypes) contentTypes = selected.toByteArray();
                if (isDocument) document = selected.toByteArray();
            }
        }
        if (document == null || contentTypes == null) throw new IOException("Not a DOCX document");
        Element root = xmlRoot(document);
        String namespace = root.getNamespaceURI();
        if (!"document".equals(root.getLocalName())
                || !("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(namespace)
                || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(namespace))
                || root.getElementsByTagNameNS(namespace, "body").getLength() == 0) {
            throw new IOException("Invalid Word document XML");
        }
        Element types = xmlRoot(contentTypes);
        String typesNamespace = "http://schemas.openxmlformats.org/package/2006/content-types";
        if (!"Types".equals(types.getLocalName()) || !typesNamespace.equals(types.getNamespaceURI())) {
            throw new IOException("Invalid content types");
        }
        NodeList overrides = types.getElementsByTagNameNS(typesNamespace, "Override");
        for (int i = 0; i < overrides.getLength(); i++) {
            Element override = (Element) overrides.item(i);
            if ("/word/document.xml".equals(override.getAttribute("PartName"))
                    && "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                    .equals(override.getAttribute("ContentType"))) return;
        }
        throw new IOException("Missing Word content type");
    }

    private static Element xmlRoot(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IOException("Invalid document XML", ex);
        }
    }

    String upload(Document document) {
        if (bucket.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Storage unavailable");
        String key = "submissions/" + UUID.randomUUID();
        try {
            storage().create(BlobInfo.newBuilder(bucket, key).setContentType(document.mediaType()).build(),
                    document.bytes(), Storage.BlobTargetOption.doesNotExist());
            return key;
        } catch (RuntimeException ex) {
            // An upload timeout may leave an object behind.
            cleanup(key);
            log.error("Resume upload outcome requires reconciliation: bucket={} key={}", bucket, key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Storage unavailable");
        }
    }

    void cleanup(String key) {
        try {
            storage().delete(BlobId.of(bucket, key));
        } catch (RuntimeException ex) {
            log.error("Resume object requires reconciliation: bucket={} key={}", bucket, key);
        }
    }

    String bucket() {
        return bucket;
    }

    void deleteForRetention(String provider, String bucketName, String key) {
        if (!"gcs".equals(provider)) throw new IllegalStateException("Unsupported storage provider");
        storage().delete(BlobId.of(bucketName, key));
    }

    private Storage storage() {
        return StorageOptions.getDefaultInstance().getService();
    }

    record Document(String filename, String mediaType, byte[] bytes, String sha256) {}
}

package com.portfolio.ragobservability.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.portfolio.ragobservability.document.dto.DocumentDetailResponse;
import com.portfolio.ragobservability.document.dto.DocumentPageResponse;
import com.portfolio.ragobservability.document.dto.DocumentSummaryResponse;

@Service
public class DocumentService {

    static final long MAX_FILE_BYTES = 1024L * 1024L;

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional
    public DocumentSummaryResponse create(MultipartFile file, String requestedTitle) {
        ValidatedUpload upload = validate(file, requestedTitle);

        if (documentRepository.existsByContentSha256(upload.contentSha256())) {
            throw duplicateDocument();
        }

        try {
            StoredDocument stored = documentRepository.insert(
                    upload.title(),
                    upload.originalFilename(),
                    upload.sourceType(),
                    upload.contentSha256(),
                    upload.content());
            return toSummary(stored);
        } catch (DuplicateKeyException exception) {
            throw duplicateDocument();
        }
    }

    @Transactional(readOnly = true)
    public DocumentPageResponse findAll(int page, int size, DocumentStatus status) {
        long offset = (long) page * size;
        long totalElements = documentRepository.count(status);
        List<DocumentSummaryResponse> items = documentRepository.findPage(size, offset, status).stream()
                .map(DocumentService::toSummary)
                .toList();
        long totalPages = totalElements == 0 ? 0 : (totalElements + size - 1) / size;
        return new DocumentPageResponse(items, page, size, totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse findById(long id) {
        return documentRepository.findById(id)
                .map(DocumentService::toDetail)
                .orElseThrow(() -> notFound(id));
    }

    @Transactional
    public DocumentDetailResponse updateTitle(long id, String title) {
        String normalizedTitle = normalizeTitle(title);
        return documentRepository.updateTitle(id, normalizedTitle)
                .map(DocumentService::toDetail)
                .orElseThrow(() -> notFound(id));
    }

    @Transactional
    public void delete(long id) {
        if (!documentRepository.deleteById(id)) {
            throw notFound(id);
        }
    }

    private static ValidatedUpload validate(MultipartFile file, String requestedTitle) {
        if (file == null || file.isEmpty()) {
            throw invalid("Document file must not be empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new DocumentException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "DOCUMENT_TOO_LARGE",
                    "Document file must not exceed 1MB");
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        DocumentSourceType sourceType = sourceTypeOf(originalFilename);
        byte[] bytes = readBytes(file);
        String content = decodeUtf8(bytes);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        if (content.isBlank()) {
            throw invalid("Document content must not be blank");
        }

        String title = requestedTitle == null
                ? filenameWithoutExtension(originalFilename)
                : requestedTitle;
        title = normalizeTitle(title);

        return new ValidatedUpload(
                title,
                originalFilename,
                sourceType,
                sha256(bytes),
                content);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new DocumentException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_READ_FAILED",
                    "Document file could not be read");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("Document file must contain valid UTF-8 text");
        }
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw invalid("Document filename is required");
        }
        String normalized = filename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (basename.isEmpty() || basename.length() > 255) {
            throw invalid("Document filename must be between 1 and 255 characters");
        }
        return basename;
    }

    private static DocumentSourceType sourceTypeOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return DocumentSourceType.TXT;
        }
        if (lower.endsWith(".md")) {
            return DocumentSourceType.MARKDOWN;
        }
        throw new DocumentException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_DOCUMENT_TYPE",
                "Only .txt and .md files are supported");
    }

    private static String filenameWithoutExtension(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            throw invalid("Document title must not be blank");
        }
        String normalized = title.trim();
        if (normalized.isEmpty()) {
            throw invalid("Document title must not be blank");
        }
        if (normalized.length() > 255) {
            throw invalid("Document title must be at most 255 characters");
        }
        return normalized;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static DocumentSummaryResponse toSummary(StoredDocument document) {
        return new DocumentSummaryResponse(
                document.id(),
                document.title(),
                document.originalFilename(),
                document.sourceType(),
                document.status(),
                document.retryCount(),
                document.embeddingStatus(),
                document.createdAt(),
                document.updatedAt());
    }

    private static DocumentDetailResponse toDetail(StoredDocument document) {
        return new DocumentDetailResponse(
                document.id(),
                document.title(),
                document.originalFilename(),
                document.sourceType(),
                document.status(),
                document.retryCount(),
                document.embeddingStatus(),
                document.embeddingRetryCount(),
                document.rawContent(),
                document.contentSha256(),
                document.errorCode(),
                document.errorMessage(),
                document.embeddingErrorCode(),
                document.embeddingErrorMessage(),
                document.createdAt(),
                document.updatedAt());
    }

    private static DocumentException invalid(String message) {
        return new DocumentException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", message);
    }

    private static DocumentException duplicateDocument() {
        return new DocumentException(
                HttpStatus.CONFLICT,
                "DOCUMENT_DUPLICATE",
                "A document with the same content already exists");
    }

    private static DocumentException notFound(long id) {
        return new DocumentException(
                HttpStatus.NOT_FOUND,
                "DOCUMENT_NOT_FOUND",
                "Document %d was not found".formatted(id));
    }

    private record ValidatedUpload(
            String title,
            String originalFilename,
            DocumentSourceType sourceType,
            String contentSha256,
            String content) {
    }
}

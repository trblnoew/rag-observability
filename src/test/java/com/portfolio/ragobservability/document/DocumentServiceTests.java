package com.portfolio.ragobservability.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTests {

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private DocumentService documentService;

    @Test
    void derivesTitleAndPersistsValidatedUpload() {
        byte[] content = "hello rag".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "C:\\fakepath\\guide.md", "text/markdown", content);

        when(documentRepository.existsByContentSha256(anyString())).thenReturn(false);
        when(documentRepository.insert(anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(invocation.getArgument(0, String.class)).isEqualTo("guide");
                    assertThat(invocation.getArgument(1, String.class)).isEqualTo("guide.md");
                    assertThat(invocation.getArgument(2, DocumentSourceType.class))
                            .isEqualTo(DocumentSourceType.MARKDOWN);
                    assertThat(invocation.getArgument(3, String.class)).hasSize(64);
                    assertThat(invocation.getArgument(4, String.class)).isEqualTo("hello rag");
                    Instant now = Instant.now();
                    return new StoredDocument(
                            1L,
                            "guide",
                            "guide.md",
                            DocumentSourceType.MARKDOWN,
                            invocation.getArgument(3, String.class),
                            DocumentStatus.PENDING,
                            null,
                            null,
                            0,
                            "hello rag",
                            now,
                            now);
                });

        var response = documentService.create(file, null);

        assertThat(response.title()).isEqualTo("guide");
        assertThat(response.status()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    void rejectsMalformedUtf8() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.txt", "text/plain", new byte[] {(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> documentService.create(file, null))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("INVALID_DOCUMENT");
                });
        verify(documentRepository, never()).insert(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void rejectsOversizedFileBeforeDatabaseAccess() {
        byte[] content = new byte[(int) DocumentService.MAX_FILE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", content);

        assertThatThrownBy(() -> documentService.create(file, "large"))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(exception.code()).isEqualTo("DOCUMENT_TOO_LARGE");
                });
        verify(documentRepository, never()).existsByContentSha256(anyString());
    }

    @Test
    void mapsDatabaseUniqueRaceToDuplicateConflict() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "race.txt", "text/plain", "same".getBytes(StandardCharsets.UTF_8));
        when(documentRepository.existsByContentSha256(anyString())).thenReturn(false);
        when(documentRepository.insert(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_document_content_sha256"));

        assertThatThrownBy(() -> documentService.create(file, null))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("DOCUMENT_DUPLICATE");
                });
    }
}

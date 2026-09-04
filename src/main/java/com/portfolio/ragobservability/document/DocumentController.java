package com.portfolio.ragobservability.document;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.portfolio.ragobservability.document.dto.DocumentDetailResponse;
import com.portfolio.ragobservability.document.dto.DocumentPageResponse;
import com.portfolio.ragobservability.document.dto.DocumentSummaryResponse;
import com.portfolio.ragobservability.document.dto.UpdateDocumentRequest;
import com.portfolio.ragobservability.document.chunk.DocumentProcessingService;
import com.portfolio.ragobservability.document.chunk.dto.DocumentChunksResponse;
import com.portfolio.ragobservability.document.chunk.dto.DocumentProcessingResponse;
import com.portfolio.ragobservability.document.embedding.EmbeddingService;
import com.portfolio.ragobservability.document.embedding.dto.EmbeddingProcessingResponse;
import com.portfolio.ragobservability.document.embedding.dto.EmbeddingStatusResponse;

@Validated
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentProcessingService documentProcessingService;
    private final EmbeddingService embeddingService;

    public DocumentController(
            DocumentService documentService,
            DocumentProcessingService documentProcessingService,
            EmbeddingService embeddingService) {
        this.documentService = documentService;
        this.documentProcessingService = documentProcessingService;
        this.embeddingService = embeddingService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentSummaryResponse> create(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title) {
        DocumentSummaryResponse created = documentService.create(file, title);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public DocumentPageResponse findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) DocumentStatus status) {
        return documentService.findAll(page, size, status);
    }

    @GetMapping("/{id}")
    public DocumentDetailResponse findById(@PathVariable @Min(1) long id) {
        return documentService.findById(id);
    }

    @PostMapping("/{id}/process")
    public DocumentProcessingResponse process(@PathVariable @Min(1) long id) {
        return documentProcessingService.process(id);
    }

    @GetMapping("/{id}/chunks")
    public DocumentChunksResponse findChunks(@PathVariable @Min(1) long id) {
        return documentProcessingService.findChunks(id);
    }

    @PostMapping("/{id}/embeddings")
    public EmbeddingProcessingResponse createEmbeddings(@PathVariable @Min(1) long id) {
        return embeddingService.process(id);
    }

    @GetMapping("/{id}/embeddings")
    public EmbeddingStatusResponse findEmbeddingStatus(@PathVariable @Min(1) long id) {
        return embeddingService.getStatus(id);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DocumentDetailResponse updateTitle(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody UpdateDocumentRequest request) {
        return documentService.updateTitle(id, request.title());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Min(1) long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

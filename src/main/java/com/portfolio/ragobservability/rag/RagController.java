package com.portfolio.ragobservability.rag;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.portfolio.ragobservability.rag.dto.AnswerRequest;
import com.portfolio.ragobservability.rag.dto.AnswerResponse;

@Validated
@RestController
@RequestMapping("/api/v1/answers")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnswerResponse answer(@Valid @RequestBody AnswerRequest request) {
        return AnswerResponse.from(ragService.answer(
                request.question(),
                request.topK(),
                request.minimumSimilarity()));
    }
}

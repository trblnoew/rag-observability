package com.portfolio.ragobservability.rag.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AnswerRequest(
        @NotBlank String question,
        @Min(1) @Max(20) Integer topK,
        @DecimalMin("-1.0") @DecimalMax("1.0") Double minimumSimilarity) {
}

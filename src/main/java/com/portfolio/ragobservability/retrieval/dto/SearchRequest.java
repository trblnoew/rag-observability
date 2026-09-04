package com.portfolio.ragobservability.retrieval.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank String query,
        @Min(1) @Max(20) Integer topK,
        @DecimalMin("-1.0") @DecimalMax("1.0") Double minimumSimilarity) {
}

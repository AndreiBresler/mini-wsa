package com.akamai.miniwsa.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record Rule(
        @NotBlank String id,
        @NotBlank String name,
        String message,
        @NotNull Severity severity,
        @NotNull Category category
) {
}

package com.akamai.miniwsa.api;

import com.akamai.miniwsa.generator.ScenarioLibrary.UnknownScenarioException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(Instant timestamp, int status, String error, List<FieldError> details) {
    }

    public record FieldError(String field, String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return badRequest("Validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            String field = ife.getPath().stream()
                    .map(ref -> ref.getFieldName() == null ? "[" + ref.getIndex() + "]" : ref.getFieldName())
                    .reduce((a, b) -> a + "." + b)
                    .orElse("body");
            return badRequest("Invalid value",
                    List.of(new FieldError(field, "invalid value: " + ife.getValue())));
        }
        return badRequest("Malformed request body", List.of());
    }

    /**
     * Triggered when a query parameter can't be coerced — e.g. {@code limit=abc}
     * to {@code int}, or {@code category=SCANNER} to {@code Category} enum.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleParamMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName();
        Object value = ex.getValue();
        return badRequest("Invalid query parameter",
                List.of(new FieldError(field, "invalid value: " + value)));
    }

    @ExceptionHandler(UnknownScenarioException.class)
    public ResponseEntity<ApiError> handleUnknownScenario(UnknownScenarioException ex) {
        return badRequest("Unknown scenario",
                List.of(new FieldError("scenario", ex.getMessage())));
    }

    private ResponseEntity<ApiError> badRequest(String error, List<FieldError> details) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(Instant.now(), 400, error, details));
    }
}

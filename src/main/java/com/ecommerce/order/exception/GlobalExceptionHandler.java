package com.ecommerce.order.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidOrderStateException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage())).collect(Collectors.toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse.builder().timestamp(LocalDateTime.now()).status(400).error("Bad Request")
                .message("Validation failed").path(req.getRequestURI())
                .correlationId(MDC.get("correlationId")).fieldErrors(errors).build());
    }

    // A Feign call that fails because the caller referenced something that does
    // not exist is a client error, not a server fault. Without this the
    // FeignException falls into handleRuntime and every "unknown product id"
    // was reported as 500.
    @ExceptionHandler(feign.FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeign(feign.FeignException ex, HttpServletRequest req) {
        HttpStatus status = switch (ex.status()) {
            case 404 -> HttpStatus.NOT_FOUND;
            case 400 -> HttpStatus.BAD_REQUEST;
            case 409 -> HttpStatus.CONFLICT;
            // -1 means the call never completed (no instance, connect timeout).
            case 503, -1 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        if (status.is4xxClientError()) {
            // Caller's mistake, and the message already says what was rejected.
            log.warn("Downstream rejected {} {} -> {}", req.getMethod(), req.getRequestURI(), status);
        } else {
            log.error("Downstream call failed on {} {} -> feign status {} mapped to {}",
                    req.getMethod(), req.getRequestURI(), ex.status(), status, ex);
        }
        return ResponseEntity.status(status).body(build(status, status.getReasonPhrase(),
                "Downstream service call failed: " + ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException ex, HttpServletRequest req) {
        if (ex.getMessage() != null && ex.getMessage().contains("unavailable")) {
            log.warn("Downstream unavailable on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(build(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", ex.getMessage(), req.getRequestURI()));
        }
        // Same reasoning as handleGeneral: the client gets a generic message, so
        // the cause has to be logged here or it is lost entirely.
        log.error("Unhandled runtime exception on {} {} (correlationId={})",
                req.getMethod(), req.getRequestURI(), MDC.get("correlationId"), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI()));
    }

    // Spring MVC raises these for malformed client requests. Without explicit
    // handlers they fall through to the Exception catch-all below and are
    // reported as 500, hiding the fact that the caller sent something invalid.
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleClientError(Exception ex, HttpServletRequest httpRequest) {
        HttpStatus status = (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException)
                ? HttpStatus.METHOD_NOT_ALLOWED
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(ex.getMessage())
                .path(httpRequest.getRequestURI())
                .correlationId(MDC.get("correlationId"))
                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {} (correlationId={})", req.getMethod(), req.getRequestURI(), MDC.get("correlationId"), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI()));
    }

    private ErrorResponse build(HttpStatus status, String error, String message, String path) {
        return ErrorResponse.builder().timestamp(LocalDateTime.now()).status(status.value())
            .error(error).message(message).path(path).correlationId(MDC.get("correlationId")).build();
    }
}

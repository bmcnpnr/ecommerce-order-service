package com.ecommerce.order.exception;

import com.ecommerce.order.logging.LogThrottle;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Every client error is a 4xx with a fixed message per error kind: no Java type names, parser text,
 * SQL, internal URLs, Feign client names or downstream bodies reach the response. Details go to the log.
 *
 * <p>What reaches the log is bounded (L3, L4, L6): a failure that repeats at request rate writes one line
 * per kind per 10 s with a count of what was swallowed, never a stack trace per request. A dependency that
 * does not answer (database, broker, product-service) is a 503 and one line without a stack trace; a bug is
 * a 500 and its stack trace once per failure site per window. The client gets the same body either way,
 * with its correlation id, and the trace of every request is in Tempo.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String UNAVAILABLE_MESSAGE = "Service temporarily unavailable";
    private static final String DOWNSTREAM_UNAVAILABLE_MESSAGE = "Downstream service call failed: service unavailable";

    private static final LogThrottle ERROR_LOG = new LogThrottle(10, TimeUnit.SECONDS);
    private static final LogThrottle UNAVAILABLE_LOG = new LogThrottle(10, TimeUnit.SECONDS);
    private static final LogThrottle CLIENT_ERROR_LOG = new LogThrottle(10, TimeUnit.SECONDS);

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    // 401/403 bodies carry no path: it can contain the very identifier the caller is not allowed to see.
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(build(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required", null));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(build(HttpStatus.FORBIDDEN, "Forbidden", "Access denied", null));
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(IllegalStatusTransitionException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(build(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidOrderStateException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(UpstreamDataException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamData(UpstreamDataException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(build(HttpStatus.BAD_GATEWAY, "Bad Gateway", ex.getMessage(), req.getRequestURI()));
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
    // not exist is a client error, not a server fault. The message is fixed per
    // status: FeignException.getMessage() carries the internal URL, the client
    // interface and the downstream body, none of which may reach the caller.
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
            // Any authenticated caller can make product-service answer 4xx at request rate (unknown ids).
            long suppressed = CLIENT_ERROR_LOG.acquire("downstream.4xx");
            if (suppressed >= 0) {
                log.warn("Downstream rejected {} {} -> {}{}", req.getMethod(), req.getRequestURI(), status,
                        LogThrottle.suppressedSuffix(suppressed));
            }
        } else if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            logUnavailable(ex, req);
        } else {
            logUnexpected("Downstream call failed (feign status " + ex.status() + " mapped to " + status + ")", ex, req);
        }
        String message = switch (status) {
            case NOT_FOUND -> "Downstream service call failed: resource not found";
            case BAD_REQUEST, CONFLICT -> "Downstream service call failed: request rejected";
            case SERVICE_UNAVAILABLE -> DOWNSTREAM_UNAVAILABLE_MESSAGE;
            default -> "Downstream service call failed";
        };
        return ResponseEntity.status(status).body(build(status, status.getReasonPhrase(), message, req.getRequestURI()));
    }

    /** The product-service breaker is open: fail fast, with the same answer as a peer that does not respond. */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleBreakerOpen(CallNotPermittedException ex, HttpServletRequest req) {
        logUnavailable(ex, req);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(build(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", DOWNSTREAM_UNAVAILABLE_MESSAGE, req.getRequestURI()));
    }

    /** Two callers changed the same order at once (add item, confirm, cancel, payment event): the later write lost. */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConflict(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.CONFLICT, "The order was modified by another request", req);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException ex, HttpServletRequest req) {
        return handleUnexpected(ex, req);
    }

    // ---- Spring MVC client errors: fixed messages, never the framework's text ----

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.METHOD_NOT_ALLOWED, "Request method not supported", req);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", req);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.NOT_ACCEPTABLE, "Requested media type not acceptable", req);
    }

    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleNoResource(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.NOT_FOUND, "Resource not found", req);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.BAD_REQUEST, "Malformed request body", req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return client(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + safeName(ex.getName()) + "'", req);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(org.springframework.web.bind.MissingServletRequestParameterException ex, HttpServletRequest req) {
        return client(HttpStatus.BAD_REQUEST, "Missing required parameter '" + safeName(ex.getParameterName()) + "'", req);
    }

    @ExceptionHandler({
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorResponse> handleBinding(Exception ex, HttpServletRequest req) {
        return client(HttpStatus.BAD_REQUEST, "Invalid request", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) {
        return handleUnexpected(ex, req);
    }

    // ---- everything that is not the caller's fault ----

    private ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        Throwable dependency = Unavailability.dependencyFailure(ex);
        if (dependency != null) {
            logUnavailable(dependency, req);
            String message = Unavailability.isPeerUnavailable(dependency) ? DOWNSTREAM_UNAVAILABLE_MESSAGE : UNAVAILABLE_MESSAGE;
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(build(HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable", message, req.getRequestURI()));
        }
        // The client gets a generic message, so the cause has to be logged here or it is lost entirely.
        logUnexpected("Unhandled exception", ex, req);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI()));
    }

    /** A dead dependency is not a bug: one WARN per kind per window, no stack trace. */
    private void logUnavailable(Throwable dependency, HttpServletRequest req) {
        long suppressed = UNAVAILABLE_LOG.acquire(dependency.getClass().getName());
        if (suppressed >= 0) {
            log.warn("Dependency unavailable on {} {}: {}{}", req.getMethod(), req.getRequestURI(),
                    Unavailability.describe(dependency), LogThrottle.suppressedSuffix(suppressed));
        }
    }

    private void logUnexpected(String what, Exception ex, HttpServletRequest req) {
        long suppressed = ERROR_LOG.acquire(LogThrottle.keyOf(ex));
        if (suppressed >= 0) {
            log.error("{} on {} {} (correlationId={}){}", what, req.getMethod(), req.getRequestURI(),
                    MDC.get("correlationId"), LogThrottle.suppressedSuffix(suppressed), ex);
        }
    }

    private ResponseEntity<ErrorResponse> client(HttpStatus status, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(build(status, status.getReasonPhrase(), message, req.getRequestURI()));
    }

    /** Parameter names come from our own code, but keep the body inert regardless. */
    private static String safeName(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    private ErrorResponse build(HttpStatus status, String error, String message, String path) {
        return ErrorResponse.builder().timestamp(LocalDateTime.now()).status(status.value())
            .error(error).message(message).path(path).correlationId(MDC.get("correlationId")).build();
    }
}

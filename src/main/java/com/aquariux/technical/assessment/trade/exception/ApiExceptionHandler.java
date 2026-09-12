package com.aquariux.technical.assessment.trade.exception;

import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(TradeException.class)
    ResponseEntity<ProblemDetail> trade(TradeException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
        var response =
                problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed");
        response.getBody()
                .setProperty(
                        "fieldErrors",
                        ex.getBindingResult().getFieldErrors().stream()
                                .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
                                .toList());
        return response;
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        ConstraintViolationException.class
    })
    ResponseEntity<ProblemDetail> malformed(Exception ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request body, path, or required header");
    }

    @ExceptionHandler({
        TransientDataAccessException.class,
        DataAccessResourceFailureException.class,
        TransactionException.class
    })
    ResponseEntity<ProblemDetail> unavailable(Exception ex) {
        log.warn("Database operation unavailable type={}", ex.getClass().getSimpleName());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRADE_BUSY",
                "Retry with the same Idempotency-Key");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(Exception ex) {
        return problem(
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Unsupported HTTP method");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media(Exception ex) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Use application/json");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> missing(Exception ex) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        // A15: Rejected attempts are operational events, never fake executed trade rows.
        log.error("Unexpected API failure type={}", ex.getClass().getSimpleName());
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        var builder = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.TOO_MANY_REQUESTS)
            builder.header("Retry-After", "1");
        return builder.body(body);
    }

    record FieldError(String field, String message) {}
}

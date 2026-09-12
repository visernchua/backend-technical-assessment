package com.aquariux.technical.assessment.trade.exception;

import lombok.Getter;

import org.springframework.http.HttpStatus;

@Getter
public class TradeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public TradeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

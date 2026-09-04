package com.portfolio.ragobservability.retrieval;

import org.springframework.http.HttpStatus;

public class RetrievalException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public RetrievalException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

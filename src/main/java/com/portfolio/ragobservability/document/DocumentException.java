package com.portfolio.ragobservability.document;

import org.springframework.http.HttpStatus;

public class DocumentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DocumentException(HttpStatus status, String code, String message) {
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

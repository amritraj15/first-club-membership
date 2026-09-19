package com.firstclub.membership.exception;

import org.springframework.http.HttpStatus;

public abstract class ApiException extends RuntimeException {
    protected ApiException(String message) {
        super(message);
    }

    public abstract HttpStatus getStatus();
}

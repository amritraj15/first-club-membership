package com.firstclub.membership.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a request conflicts with existing state, e.g. subscribing while an active
 *  subscription already exists. */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}

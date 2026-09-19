package com.firstclub.membership.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a subscription lifecycle transition is not allowed from its current status -
 *  see SubscriptionStateMachine. 422 (Unprocessable Entity), not 400: the REQUEST itself is
 *  well-formed and valid (right shape, right types) - it's a business rule, not a malformed
 *  request, that prevents it. Reserving 400 for genuinely malformed/invalid requests (see
 *  GlobalExceptionHandler's handling of validation and IllegalArgumentException) keeps the
 *  two failure classes distinguishable by status code alone, which callers rely on. */
public class InvalidTransitionException extends ApiException {
    public InvalidTransitionException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}

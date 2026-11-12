package com.ticketdrop.web;

/**
 * A request that is well-formed JSON but breaks a rule that bean validation cannot express.
 * Answered with 400 VALIDATION_FAILED and this message.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}

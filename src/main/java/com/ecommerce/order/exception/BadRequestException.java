package com.ecommerce.order.exception;

/** A client mistake with a message that is safe to show (400). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}

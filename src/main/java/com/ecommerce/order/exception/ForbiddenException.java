package com.ecommerce.order.exception;

/** Authenticated, but not the owner and not an admin (403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("Access denied");
    }
}

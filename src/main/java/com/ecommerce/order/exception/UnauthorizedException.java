package com.ecommerce.order.exception;

/** No usable identity on the request (401). */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() {
        super("Authentication required");
    }
}

package com.ecommerce.order.exception;

/** product-service answered with data the order cannot be built on (for example a missing or non-positive price): 502. */
public class UpstreamDataException extends RuntimeException {
    public UpstreamDataException(String message) {
        super(message);
    }
}

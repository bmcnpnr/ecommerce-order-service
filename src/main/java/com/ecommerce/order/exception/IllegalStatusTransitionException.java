package com.ecommerce.order.exception;

/** The requested status change is not in the transition table (409). */
public class IllegalStatusTransitionException extends InvalidOrderStateException {
    public IllegalStatusTransitionException(String message) {
        super(message);
    }
}

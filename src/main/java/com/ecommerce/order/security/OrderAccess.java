package com.ecommerce.order.security;

import com.ecommerce.order.exception.BadRequestException;
import com.ecommerce.order.exception.ForbiddenException;
import com.ecommerce.order.exception.UnauthorizedException;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authorization rules from the platform security spec. The gateway verifies the JWT and sets
 * {@code X-Username} and {@code X-User-Role}; this service trusts them. The role is compared
 * exactly ({@code ADMIN}), with no trimming or case folding. The owner of an order is its
 * {@code customerId}, compared with {@code X-Username}.
 */
@Component
@RequiredArgsConstructor
public class OrderAccess {

    public static final String ADMIN = "ADMIN";
    static final int MAX_CUSTOMER_ID_LENGTH = 255;

    private final OrderRepository orderRepository;

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    private static void requireIdentity(String username) {
        if (username == null || username.isBlank()) {
            throw new UnauthorizedException();
        }
    }

    /** Authenticated: any non-blank {@code X-Username}. */
    public void requireAuthenticated(String username) {
        requireIdentity(username);
    }

    public void requireAdmin(String username, String role) {
        requireIdentity(username);
        if (!isAdmin(role)) {
            throw new ForbiddenException();
        }
    }

    /**
     * The customer an order is created for: {@code requested} when given (a non-admin may only name
     * themselves), otherwise the caller.
     */
    public String resolveCustomer(String username, String role, String requested) {
        requireIdentity(username);
        String customerId = requested == null ? username : requested;
        validateCustomerId(customerId);
        if (!isAdmin(role) && !customerId.equals(username)) {
            throw new ForbiddenException();
        }
        return customerId;
    }

    /** Reading all orders of {@code customerId}: that customer or an admin. */
    public void requireCustomerOrAdmin(String username, String role, String customerId) {
        requireIdentity(username);
        if (!isAdmin(role) && !username.equals(customerId)) {
            throw new ForbiddenException();
        }
    }

    /** One order: its owner or an admin. A missing order is reported as such (404) after the identity check. */
    public void requireOwnerOrAdmin(String username, String role, Long orderId) {
        requireIdentity(username);
        if (isAdmin(role)) {
            return;
        }
        String owner = orderRepository.findCustomerIdByOrderId(orderId).orElse(null);
        if (owner == null) {
            throw new com.ecommerce.order.exception.OrderNotFoundException("Order not found: " + orderId);
        }
        if (!owner.equals(username)) {
            throw new ForbiddenException();
        }
    }

    static void validateCustomerId(String customerId) {
        if (customerId.isBlank()) {
            throw new BadRequestException("customerId must not be blank");
        }
        if (customerId.length() > MAX_CUSTOMER_ID_LENGTH) {
            throw new BadRequestException("customerId must be at most " + MAX_CUSTOMER_ID_LENGTH + " characters");
        }
        for (int i = 0; i < customerId.length(); i++) {
            if (Character.isISOControl(customerId.charAt(i))) {
                throw new BadRequestException("customerId contains invalid characters");
            }
        }
    }
}

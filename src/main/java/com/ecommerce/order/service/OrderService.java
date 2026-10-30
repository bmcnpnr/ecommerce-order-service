package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.event.*;
import com.ecommerce.order.exception.*;
import com.ecommerce.order.logging.LogThrottle;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.*;
import com.ecommerce.order.repository.OrderRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Order use cases.
 *
 * <p><b>No database transaction is held across a call to product-service.</b> A slow peer would otherwise
 * pin one pooled connection per request for up to the Feign read timeout, and ten stuck requests exhaust
 * the pool for every other endpoint, the payment consumer and payment-service's order lookup included.
 * Methods that call out therefore run as steps: read, remote call, then a short write transaction
 * (programmatic, so it also works when one method calls another). The order carries a version, so a
 * writer that started from a state somebody else changed meanwhile fails instead of overwriting it.
 *
 * <p>Stock deltas are not idempotent, so they are never repeated blindly: a reservation is released only
 * when product-service is known to have applied it and the order could not be saved, and a release after
 * a cancellation happens once, after the cancellation committed.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Every line travels in the order.placed event; a record near Kafka's 1 MB limit could never be published. */
    static final int MAX_LINES_PER_ORDER = 200;
    /** total_amount is NUMERIC(19,4): 15 integer digits. */
    private static final BigDecimal MAX_TOTAL = new BigDecimal("999999999999999.9999");
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Stock and upstream-data problems repeat for as long as the peer misbehaves: one line per 10 s, not one per request.
     * Per instance (the service is a singleton), so a test's service starts with a fresh window.
     */
    private final LogThrottle stockLog = new LogThrottle(10, TimeUnit.SECONDS);

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final OrderEventProducer orderEventProducer;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orderRepository, ProductServiceClient productServiceClient,
                        OrderEventProducer orderEventProducer, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.productServiceClient = productServiceClient;
        this.orderEventProducer = orderEventProducer;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public String getHelloMessage() {
        return "Hello from Order Service!";
    }

    @Transactional
    public OrderDTO createOrder(String customerId) {
        Order order = Order.builder()
                .customerId(customerId)
                .orderDate(LocalDateTime.now())
                .totalAmount(BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .build();
        Order saved = orderRepository.save(order);
        log.info("Order created: orderId={}, customerId={}", saved.getOrderId(), customerId);
        return toDTO(saved);
    }

    public OrderDTO addItemToOrder(Long orderId, Long productId, Integer quantity) {
        // 1. Read. An unknown or closed order must not cost a remote call.
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        requireOpenForItems(order);

        // 2. Ask product-service, outside any transaction.
        ProductDTO product = productServiceClient.getProductById(productId);
        if (product == null || product.getPrice() == null || product.getPrice().signum() <= 0) {
            if (stockLog.acquire("upstream.invalid-price") >= 0) {
                log.error("product-service returned a missing or non-positive price for productId={}", productId);
            }
            throw new UpstreamDataException("Product price is not available");
        }
        BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(quantity));
        requireTotalFits(order, lineTotal);
        if (product.getStockQuantity() != null && product.getStockQuantity() < quantity) {
            throw new InvalidOrderStateException("Insufficient stock for product " + product.getSku()
                    + ". Available: " + product.getStockQuantity());
        }

        // 3. Reserve the stock. If this call fails nothing is known to have changed, so nothing is released:
        //    after a read timeout the reservation may or may not have been applied, and guessing wrong
        //    in either direction is worse than reporting the failure.
        productServiceClient.updateStock(productId, new StockUpdateRequest(-quantity));

        // 4. Save the line in a short transaction. Product-service now holds the reservation, so any failure
        //    from here on (order closed or changed meanwhile, database down, version conflict) gives it back.
        try {
            OrderDTO saved = tx.execute(status -> {
                Order current = orderRepository.findById(orderId)
                        .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
                requireOpenForItems(current);
                requireTotalFits(current, lineTotal);
                current.addItem(OrderItem.builder()
                        .productId(productId)
                        .productName(product.getName())
                        .productPrice(product.getPrice())
                        .quantity(quantity)
                        .build());
                return toDTO(orderRepository.save(current));
            });
            log.info("Item added to order {}: productId={}, quantity={}", orderId, productId, quantity);
            return saved;
        } catch (RuntimeException e) {
            releaseReservation(orderId, productId, quantity);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public OrderDTO getOrderById(Long orderId) {
        return toDTO(orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId)));
    }

    /** One page of the customer's orders, newest first; {@code size} is capped at {@value #MAX_PAGE_SIZE}. */
    @Transactional(readOnly = true)
    public List<OrderDTO> getOrdersByCustomer(String customerId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "orderId"));
        return orderRepository.findByCustomerId(customerId, pageable).stream()
                .map(this::toDTO).collect(Collectors.toList());
    }

    public OrderDTO updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        requireTransition(order, status);
        // Cancelling and confirming have side effects (stock restore, order.cancelled, order.placed):
        // go through the same code paths the customer endpoints use.
        if (status == OrderStatus.CANCELLED) {
            cancelOrder(orderId);
            return toDTO(orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId)));
        }
        if (status == OrderStatus.CONFIRMED) {
            return confirmOrder(orderId);
        }
        return tx.execute(s -> {
            Order current = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
            requireTransition(current, status);
            current.setStatus(status);
            return toDTO(orderRepository.save(current));
        });
    }

    public void cancelOrder(Long orderId) {
        // The cancellation and its event commit together. Whoever commits first wins; a concurrent second
        // cancel fails on the version and never reaches the stock below, so stock is returned once.
        List<StockLine> lines = tx.execute(status -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED) {
                throw new InvalidOrderStateException("Cannot cancel order in status: " + order.getStatus());
            }
            return markCancelled(orderId, order, true);
        });

        // After the commit and outside any transaction: a slow or dead product-service cannot hold a database
        // connection, and cannot undo the cancellation.
        restoreStock(orderId, lines);
        log.info("Order {} cancelled", orderId);
    }

    /** What {@link #cancelAfterFailedPayment} did with a {@code payment.failed} event. */
    public enum PaymentFailureOutcome { CANCELLED, ALREADY_CANCELLED, NOT_APPLICABLE, UNKNOWN_ORDER }

    /** The outcome, and the status the order was found in (null when there is no such order). */
    public record PaymentFailureResult(PaymentFailureOutcome outcome, OrderStatus status) {
    }

    private record FailureDecision(PaymentFailureResult result, List<StockLine> lines) {
    }

    /**
     * {@code payment.failed}: the order will never be paid, so it is cancelled and the stock it reserved goes
     * back (I-4). Same shape as a customer's cancel: the cancellation commits first, the stock follows outside
     * the transaction, once. No {@code order.cancelled} is published; that event stays the answer to a cancel
     * somebody asked for, as it always was.
     *
     * <p>Idempotent. The status guard makes a replayed event a no-op ({@link PaymentFailureOutcome#ALREADY_CANCELLED}:
     * no stock goes back a second time), and the version makes a replay that races a customer's cancel fail on
     * commit instead of cancelling twice. That failure is thrown, so the listener retries and then finds the
     * final state.
     */
    public PaymentFailureResult cancelAfterFailedPayment(Long orderId) {
        FailureDecision decision = tx.execute(status -> {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return new FailureDecision(new PaymentFailureResult(PaymentFailureOutcome.UNKNOWN_ORDER, null), List.of());
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                return new FailureDecision(new PaymentFailureResult(PaymentFailureOutcome.ALREADY_CANCELLED, order.getStatus()), List.of());
            }
            if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.CONFIRMED) {
                return new FailureDecision(new PaymentFailureResult(PaymentFailureOutcome.NOT_APPLICABLE, order.getStatus()), List.of());
            }
            List<StockLine> lines = markCancelled(orderId, order, false);
            return new FailureDecision(new PaymentFailureResult(PaymentFailureOutcome.CANCELLED, OrderStatus.CANCELLED), lines);
        });

        if (decision.result().outcome() == PaymentFailureOutcome.CANCELLED) {
            restoreStock(orderId, decision.lines());
            log.info("Order {} status updated to CANCELLED due to payment failure", orderId);
        }
        return decision.result();
    }

    /** What {@link #expireAbandonedCart} did with a candidate. */
    public enum ExpiryOutcome { EXPIRED, SKIPPED, PEER_UNAVAILABLE }

    /**
     * Cancels one abandoned cart, by the path a customer's cancel takes: status and {@code order.cancelled}
     * commit together, then the stock goes back outside the transaction. Safe to call from several replicas at
     * once for the same candidate, and for a candidate that moved on since it was selected:
     * <ul>
     *   <li>only a PENDING order whose last change is strictly before {@code cutoff} is touched, checked again inside
     *       the transaction (CONFIRMED and later are never expired: a payment may be on its way);</li>
     *   <li>whoever commits first wins; the others see a cancelled order or fail on the version and answer
     *       {@code SKIPPED}, without a log line, and never reach the stock.</li>
     * </ul>
     * A cart with lines is only cancelled while product-service answers, because stock that cannot be returned is
     * lost for good; the cart waits for the next run instead ({@code PEER_UNAVAILABLE}). An empty cart holds
     * nothing and always expires.
     */
    public ExpiryOutcome expireAbandonedCart(Long orderId, LocalDateTime cutoff) {
        Order candidate = orderRepository.findById(orderId).orElse(null);
        if (candidate == null || !isAbandoned(candidate, cutoff)) {
            return ExpiryOutcome.SKIPPED;
        }
        if (!candidate.getOrderItems().isEmpty() && !productServiceAnswers(candidate.getOrderItems().get(0).getProductId())) {
            return ExpiryOutcome.PEER_UNAVAILABLE;
        }
        List<StockLine> lines;
        try {
            lines = tx.execute(status -> {
                Order order = orderRepository.findById(orderId).orElse(null);
                // Checked again where it counts: the customer may have added an item since the read above.
                if (order == null || !isAbandoned(order, cutoff)) {
                    return null;
                }
                return markCancelled(orderId, order, true);
            });
        } catch (OptimisticLockingFailureException conflict) {
            return ExpiryOutcome.SKIPPED; // another replica, the customer or a payment event got there first
        }
        if (lines == null) {
            return ExpiryOutcome.SKIPPED;
        }
        restoreStock(orderId, lines);
        log.debug("Cart {} expired: not changed since before {}", orderId, cutoff);
        return ExpiryOutcome.EXPIRED;
    }

    public OrderDTO confirmOrder(Long orderId) {
        OrderDTO confirmed = tx.execute(status -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

            if (order.getStatus() != OrderStatus.PENDING) {
                throw new InvalidOrderStateException("Can only confirm PENDING orders. Current status: " + order.getStatus());
            }
            if (order.getOrderItems().isEmpty()) {
                throw new InvalidOrderStateException("Cannot confirm an order with no items");
            }

            order.setStatus(OrderStatus.CONFIRMED);
            Order saved = orderRepository.save(order);

            // Order placed event: written to the outbox in this transaction, published after it commits.
            List<OrderPlacedEvent.OrderItemEvent> itemEvents = order.getOrderItems().stream()
                    .map(item -> OrderPlacedEvent.OrderItemEvent.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .productPrice(item.getProductPrice())
                            .quantity(item.getQuantity())
                            .build())
                    .collect(Collectors.toList());

            orderEventProducer.publishOrderPlaced(OrderPlacedEvent.builder()
                    .orderId(orderId)
                    .customerId(order.getCustomerId())
                    .totalAmount(order.getTotalAmount())
                    .orderDate(order.getOrderDate())
                    .items(itemEvents)
                    .build());
            return toDTO(saved);
        });
        log.info("Order {} confirmed", orderId);
        return confirmed;
    }

    // ---- cancellation ---------------------------------------------------------------------------

    /**
     * The writes every cancellation shares, inside the caller's transaction: the status, optionally the
     * {@code order.cancelled} event (through the outbox), and the lines whose stock has to go back.
     */
    private List<StockLine> markCancelled(Long orderId, Order order, boolean announce) {
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        if (announce) {
            orderEventProducer.publishOrderCancelled(OrderCancelledEvent.builder()
                    .orderId(orderId)
                    .customerId(order.getCustomerId())
                    .cancelledAt(LocalDateTime.now())
                    .build());
        }
        return order.getOrderItems().stream()
                .map(item -> new StockLine(item.getProductId(), item.getQuantity()))
                .toList();
    }

    /** A PENDING order that has not changed for longer than the TTL: the only thing the expiry job may cancel. */
    private static boolean isAbandoned(Order order, LocalDateTime cutoff) {
        return order.getStatus() == OrderStatus.PENDING && order.lastChange().isBefore(cutoff);
    }

    /** Does product-service answer? A 4xx is an answer (the product is just unknown); no answer, a 5xx or an open breaker is not. */
    private boolean productServiceAnswers(Long productId) {
        try {
            productServiceClient.getProductById(productId);
            return true;
        } catch (FeignException e) {
            return e.status() >= 400 && e.status() < 500;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- stock ----------------------------------------------------------------------------------

    /**
     * Gives the reserved stock of a cancelled order back. Best effort, once: a failure never undoes the
     * cancellation, and once product-service is evidently unreachable the remaining lines are not tried
     * (each attempt could cost seconds against a dead peer). Everything that could not be returned is
     * reported in one line, so it can be corrected by hand.
     */
    private void restoreStock(Long orderId, List<StockLine> lines) {
        List<StockLine> notReturned = new ArrayList<>();
        Throwable lastFailure = null;
        boolean peerUnavailable = false;
        for (StockLine line : lines) {
            if (peerUnavailable) {
                notReturned.add(line);
                continue;
            }
            try {
                productServiceClient.updateStock(line.productId(), new StockUpdateRequest(line.quantity()));
            } catch (RuntimeException e) {
                notReturned.add(line);
                lastFailure = e;
                peerUnavailable = Unavailability.isPeerUnavailable(e);
            }
        }
        if (!notReturned.isEmpty()) {
            long suppressed = stockLog.acquire("stock.restore");
            if (suppressed >= 0) {
                log.warn("Order {} cancelled, but stock was not returned to product-service for {} line(s) {} ({}){}",
                        orderId, notReturned.size(), notReturned, lastFailure.getClass().getSimpleName(),
                        LogThrottle.suppressedSuffix(suppressed));
            }
        }
    }

    /** The reservation succeeded but the line could not be saved: put the stock back. */
    private void releaseReservation(Long orderId, Long productId, int quantity) {
        try {
            productServiceClient.updateStock(productId, new StockUpdateRequest(quantity));
        } catch (RuntimeException e) {
            long suppressed = stockLog.acquire("stock.release");
            if (suppressed >= 0) {
                log.warn("Item for order {} was not saved and {} unit(s) of productId={} could not be released ({}){}",
                        orderId, quantity, productId, e.getClass().getSimpleName(), LogThrottle.suppressedSuffix(suppressed));
            }
        }
    }

    // ---- guards ---------------------------------------------------------------------------------

    private static void requireOpenForItems(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Cannot add items to order in status: " + order.getStatus());
        }
        if (order.getOrderItems().size() >= MAX_LINES_PER_ORDER) {
            throw new InvalidOrderStateException("An order can have at most " + MAX_LINES_PER_ORDER + " lines");
        }
    }

    private static void requireTotalFits(Order order, BigDecimal lineTotal) {
        BigDecimal base = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        if (base.add(lineTotal).compareTo(MAX_TOTAL) > 0) {
            throw new InvalidOrderStateException("The order total is too large");
        }
    }

    private static void requireTransition(Order order, OrderStatus next) {
        if (!order.getStatus().canTransitionTo(next)) {
            throw new IllegalStatusTransitionException(
                    "Cannot change order status from " + order.getStatus() + " to " + next);
        }
    }

    private record StockLine(Long productId, Integer quantity) {
        @Override
        public String toString() {
            return "productId=" + productId + " x" + quantity;
        }
    }

    private OrderDTO toDTO(Order order) {
        List<OrderItemDTO> itemDTOs = order.getOrderItems().stream()
                .map(item -> OrderItemDTO.builder()
                        .orderItemId(item.getOrderItemId())
                        .orderId(order.getOrderId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productPrice(item.getProductPrice())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        return OrderDTO.builder()
                .orderId(order.getOrderId())
                .customerId(order.getCustomerId())
                .orderItems(itemDTOs)
                .orderDate(order.getOrderDate())
                .billingAddress(order.getBillingAddress())
                .shippingAddress(order.getShippingAddress())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .build();
    }
}

package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.event.*;
import com.ecommerce.order.exception.*;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.*;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final ModelMapper modelMapper;
    private final OrderEventProducer orderEventProducer;

    public String getHelloMessage() {
        return "Hello from Order Service!";
    }

    @Transactional
    public OrderDTO createOrder(String customerId) {
        log.info("Creating order for customer: {}", customerId);
        Order order = Order.builder()
                .customerId(customerId)
                .orderDate(LocalDateTime.now())
                .totalAmount(BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .build();
        Order saved = orderRepository.save(order);
        log.info("Order created: orderId={}", saved.getOrderId());
        return toDTO(saved);
    }

    @Transactional
    public OrderDTO addItemToOrder(Long orderId, Long productId, Integer quantity) {
        log.info("Adding item to order {}: productId={}, quantity={}", orderId, productId, quantity);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.PAID) {
            throw new InvalidOrderStateException("Cannot add items to order in status: " + order.getStatus());
        }

        ProductDTO product = productServiceClient.getProductById(productId);

        if (product.getStockQuantity() != null && product.getStockQuantity() < quantity) {
            throw new InvalidOrderStateException("Insufficient stock for product " + product.getSku()
                    + ". Available: " + product.getStockQuantity());
        }

        // Reduce stock
        productServiceClient.updateStock(productId, new StockUpdateRequest(-quantity));

        OrderItem item = OrderItem.builder()
                .productId(productId)
                .productName(product.getName())
                .productPrice(product.getPrice())
                .quantity(quantity)
                .build();

        order.addItem(item);
        Order saved = orderRepository.save(order);
        log.info("Item added to order {}: product={}", orderId, product.getName());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public OrderDTO getOrderById(Long orderId) {
        return toDTO(orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId)));
    }

    @Transactional(readOnly = true)
    public List<OrderDTO> getOrdersByCustomer(String customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional
    public OrderDTO updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        order.setStatus(status);
        return toDTO(orderRepository.save(order));
    }

    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED) {
            throw new InvalidOrderStateException("Cannot cancel order in status: " + order.getStatus());
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // Restore stock for each item
        for (OrderItem item : order.getOrderItems()) {
            try {
                productServiceClient.updateStock(item.getProductId(), new StockUpdateRequest(item.getQuantity()));
            } catch (Exception e) {
                log.error("Failed to restore stock for productId={} during order cancellation", item.getProductId(), e);
            }
        }

        orderEventProducer.publishOrderCancelled(OrderCancelledEvent.builder()
                .orderId(orderId)
                .customerId(order.getCustomerId())
                .cancelledAt(LocalDateTime.now())
                .build());

        log.info("Order {} cancelled", orderId);
    }

    @Transactional
    public OrderDTO confirmOrder(Long orderId) {
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

        // Publish order placed event
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

        log.info("Order {} confirmed and event published", orderId);
        return toDTO(saved);
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

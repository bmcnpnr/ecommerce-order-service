package com.ecommerce.order.service;

import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.repository.OrderRepository;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;

//todo make db ops transactional?

@Service
public final class OrderService {

    public static final String HELLO_FROM_ORDER_SERVICE = "Hello from Order Service!";

    private final OrderRepository orderRepository;

    private final ModelMapper modelMapper;

    private final ProductServiceClient productServiceClient;

    @Autowired
    public OrderService(final OrderRepository orderRepository, final ModelMapper modelMapper, final ProductServiceClient productServiceClient) {
        this.orderRepository = orderRepository;
        this.modelMapper = modelMapper;
        this.productServiceClient = productServiceClient;
    }

    // Constructor, business methods

    public String getHelloMessage() {
        return HELLO_FROM_ORDER_SERVICE;
    }

    public OrderDTO createOrder(final String customerId) {
        var order = Order.builder()
                .customerId(customerId)
                .orderDate(LocalDateTime.now())
                .orderItems(new ArrayList<>())
                .build();
        return saveAndReturn(order);
    }

    public OrderDTO addProductToOrder(final Long orderId, final String productId) {
        var productDTO = productServiceClient.getProductDetails(productId);
        var order = orderRepository.findById(orderId).orElseThrow();

        var orderItem = new OrderItem();
        orderItem.setProductId(productId);
        orderItem.setProductName(productDTO.getName());
        orderItem.setProductPrice(productDTO.getPrice());
        // todo ... set other product details

        order.addItem(orderItem);
        return saveAndReturn(order);
    }

    private OrderDTO saveAndReturn(final Order order) {
        var savedEntity = orderRepository.save(order);
        return modelMapper.map(savedEntity, OrderDTO.class);
    }
}

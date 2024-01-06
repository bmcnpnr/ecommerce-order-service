package com.ecommerce.order.service;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    public static final String HELLO_FROM_ORDER_SERVICE = "Hello from Order Service!";

    public String getHelloMessage() {
        return HELLO_FROM_ORDER_SERVICE;
    }
}

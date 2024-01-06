package com.ecommerce.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ecommerce.order.service.OrderService.HELLO_FROM_ORDER_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Test
    void testGetHelloMessage() {
        // when
        var result = orderService.getHelloMessage();

        // then
        assertEquals(HELLO_FROM_ORDER_SERVICE, result);
    }
}

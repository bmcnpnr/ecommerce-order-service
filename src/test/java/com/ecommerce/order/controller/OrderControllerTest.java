package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.ecommerce.order.service.OrderService.HELLO_FROM_ORDER_SERVICE;
import static com.ecommerce.order.util.TestUtil.generateOrderDTO;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    void testSayHello() throws Exception {
        // Mocking service response
        given(orderService.getHelloMessage()).willReturn(HELLO_FROM_ORDER_SERVICE);

        // Perform GET request and verify
        mockMvc.perform(get("/orders/hello"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(HELLO_FROM_ORDER_SERVICE));
    }

    @Test
    void testCreateOrder() throws Exception {
        var orderDTO = generateOrderDTO();

        // Mocking service response
        given(orderService.createOrder("user_1")).willReturn(orderDTO);

        // Perform GET request and verify
        mockMvc.perform(post("/orders").param("customerId", "user_1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(objectMapper.writeValueAsString(orderDTO)));
    }

    @Test
    void testAddItemToOrder() throws Exception {
        var orderDTO = generateOrderDTO();

        // Mocking service response
        given(orderService.addProductToOrder(1L, "nike_1")).willReturn(orderDTO);

        // Perform GET request and verify
        mockMvc.perform(post("/orders/" + "1" + "/products")
                        .param("productId", "nike_1")
                )
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(objectMapper.writeValueAsString(orderDTO)));
    }
}

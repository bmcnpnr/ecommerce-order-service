package com.ecommerce.order.controller;

import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

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

    @MockitoBean
    private OrderService orderService;

    @Test
    void testSayHello() throws Exception {
        given(orderService.getHelloMessage()).willReturn("Hello from Order Service!");

        mockMvc.perform(get("/api/v1/orders/hello"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("Hello from Order Service!"));
    }

    @Test
    void testCreateOrder() throws Exception {
        var orderDTO = generateOrderDTO();

        given(orderService.createOrder("user_1")).willReturn(orderDTO);

        mockMvc.perform(post("/api/v1/orders").param("customerId", "user_1"))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(content().string(objectMapper.writeValueAsString(orderDTO)));
    }

    @Test
    void testGetOrderById() throws Exception {
        var orderDTO = generateOrderDTO();

        given(orderService.getOrderById(1L)).willReturn(orderDTO);

        mockMvc.perform(get("/api/v1/orders/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(objectMapper.writeValueAsString(orderDTO)));
    }
}

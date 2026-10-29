package com.ecommerce.order.controller;

import com.ecommerce.order.dto.AddItemRequest;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.ecommerce.order.util.TestUtil.generateOrderDTO;
import static com.ecommerce.order.util.TestUtil.generateOrderItemDTO;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Test
    void testGetOrdersByCustomer() throws Exception {
        var orderDTO = generateOrderDTO();

        given(orderService.getOrdersByCustomer("user_1")).willReturn(List.of(orderDTO));

        mockMvc.perform(get("/api/v1/orders/customer/user_1"))
                .andExpect(status().isOk())
                .andExpect(content().string(objectMapper.writeValueAsString(List.of(orderDTO))));
    }

    @Test
    void testAddItemToOrder() throws Exception {
        OrderDTO orderDTO = generateOrderDTO();
        orderDTO.setOrderItems(List.of(generateOrderItemDTO(1L)));
        given(orderService.addItemToOrder(1L, 1L, 2)).willReturn(orderDTO);

        mockMvc.perform(post("/api/v1/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddItemRequest(1L, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderItems[0].productId").value(1))
                .andExpect(content().string(objectMapper.writeValueAsString(orderDTO)));
    }

    @Test
    void testLegacyAddProductDefaultsQuantityToOne() throws Exception {
        given(orderService.addItemToOrder(1L, 7L, 1)).willReturn(generateOrderDTO());

        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "7"))
                .andExpect(status().isOk());

        verify(orderService).addItemToOrder(1L, 7L, 1);
    }

    @Test
    void testLegacyAddProductWithQuantity() throws Exception {
        given(orderService.addItemToOrder(1L, 7L, 3)).willReturn(generateOrderDTO());

        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "7").param("quantity", "3"))
                .andExpect(status().isOk());

        verify(orderService).addItemToOrder(1L, 7L, 3);
    }

    @Test
    void testConfirmOrder() throws Exception {
        OrderDTO orderDTO = generateOrderDTO();
        orderDTO.setStatus("CONFIRMED");
        given(orderService.confirmOrder(1L)).willReturn(orderDTO);

        mockMvc.perform(post("/api/v1/orders/1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void testUpdateStatus() throws Exception {
        OrderDTO orderDTO = generateOrderDTO();
        orderDTO.setStatus("SHIPPED");
        given(orderService.updateOrderStatus(1L, OrderStatus.SHIPPED)).willReturn(orderDTO);

        mockMvc.perform(patch("/api/v1/orders/1/status").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void testCancelOrder() throws Exception {
        mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(orderService).cancelOrder(1L);
    }

    @Test
    void testResponsesCarryCorrelationId() throws Exception {
        given(orderService.getOrderById(1L)).willReturn(generateOrderDTO());

        mockMvc.perform(get("/api/v1/orders/1").header("X-Correlation-Id", "cid-42"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "cid-42"));
    }
}

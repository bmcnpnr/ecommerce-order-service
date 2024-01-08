package com.ecommerce.order.config;

import com.ecommerce.order.dto.OrderItemDTO;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.model.OrderItem;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.ecommerce.order.util.TestUtil.generateOrderDTO;
import static com.ecommerce.order.util.TestUtil.generateOrderEntity;
import static com.ecommerce.order.util.TestUtil.generateOrderItemDTO;
import static com.ecommerce.order.util.TestUtil.generateOrderItemEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ModelMapperTest {

    @Autowired
    private ModelMapper modelMapper;

    @Test
    void testOrderDtoToEntity() {
        final var orderDTO = generateOrderDTO();
        orderDTO.setOrderItems(List.of(generateOrderItemDTO(orderDTO)));

        final var order = modelMapper.map(orderDTO, Order.class);
        assertEquals(orderDTO.getOrderId(), order.getOrderId());
        assertEquals(orderDTO.getCustomerId(), order.getCustomerId());
        assertEquals(orderDTO.getOrderDate(), order.getOrderDate());
        assertEquals(orderDTO.getBillingAddress(), order.getBillingAddress());
        assertEquals(orderDTO.getShippingAddress(), order.getShippingAddress());
        assertEquals(orderDTO.getTotalAmount(), order.getTotalAmount());

        assertEquals(orderDTO.getOrderItems().size(), order.getOrderItems().size());

        for (int i = 0; i < orderDTO.getOrderItems().size(); i++) {
            OrderItemDTO orderItemDTO = orderDTO.getOrderItems().get(i);
            OrderItem orderItemEntity = order.getOrderItems().get(i);
            assertEquals(orderItemDTO.getOrderItemId(), orderItemEntity.getOrderItemId());
            assertEquals(orderItemDTO.getOrder().getOrderId(), orderItemEntity.getOrder().getOrderId());
            assertEquals(orderItemDTO.getProductId(), orderItemEntity.getProductId());
            assertEquals(orderItemDTO.getProductName(), orderItemEntity.getProductName());
            assertEquals(orderItemDTO.getProductPrice(), orderItemEntity.getProductPrice());
        }
    }

    @Test
    void testOrderEntityToDto() {
        final var order = generateOrderEntity();
        order.setOrderItems(List.of(generateOrderItemEntity(order)));

        final var orderDTO = modelMapper.map(order, OrderDTO.class);
        assertEquals(order.getOrderId(), orderDTO.getOrderId());
        assertEquals(order.getCustomerId(), orderDTO.getCustomerId());
        assertEquals(order.getOrderDate(), orderDTO.getOrderDate());
        assertEquals(order.getBillingAddress(), orderDTO.getBillingAddress());
        assertEquals(order.getShippingAddress(), orderDTO.getShippingAddress());
        assertEquals(order.getTotalAmount(), orderDTO.getTotalAmount());

        assertEquals(order.getOrderItems().size(), orderDTO.getOrderItems().size());

        for (int i = 0; i < order.getOrderItems().size(); i++) {
            OrderItem orderItemEntity = order.getOrderItems().get(i);
            OrderItemDTO orderItemDTO = orderDTO.getOrderItems().get(i);
            assertEquals(orderItemEntity.getOrderItemId(), orderItemDTO.getOrderItemId());
            assertEquals(orderItemEntity.getOrder().getOrderId(), orderItemDTO.getOrder().getOrderId());
            assertEquals(orderItemEntity.getProductId(), orderItemDTO.getProductId());
            assertEquals(orderItemEntity.getProductName(), orderItemDTO.getProductName());
            assertEquals(orderItemEntity.getProductPrice(), orderItemDTO.getProductPrice());
        }
    }
}

package com.ecommerce.order.client;

import com.ecommerce.order.dto.ProductDTO;
import com.ecommerce.order.dto.StockUpdateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * product-service. Both methods share the circuit breaker {@code product-service}
 * ({@code spring.cloud.openfeign.circuitbreaker.enabled}, named by {@link com.ecommerce.order.config.FeignConfig}).
 */
@FeignClient(name = "product-service", fallbackFactory = ProductServiceClientFallbackFactory.class)
public interface ProductServiceClient {

    @GetMapping("/api/v1/products/{id}")
    ProductDTO getProductById(@PathVariable("id") Long productId);

    // Internal endpoint: the public stock endpoint is admin-only and the gateway never routes /internal.
    @PatchMapping("/internal/v1/products/{id}/stock")
    ProductDTO updateStock(@PathVariable("id") Long productId, @RequestBody StockUpdateRequest request);
}

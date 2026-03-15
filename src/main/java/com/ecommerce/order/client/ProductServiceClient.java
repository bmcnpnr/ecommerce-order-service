package com.ecommerce.order.client;

import com.ecommerce.order.dto.ProductDTO;
import com.ecommerce.order.dto.StockUpdateRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "product-service", fallback = ProductServiceClientFallback.class)
public interface ProductServiceClient {

    @GetMapping("/api/v1/products/{id}")
    @CircuitBreaker(name = "product-service")
    ProductDTO getProductById(@PathVariable("id") Long productId);

    @PatchMapping("/api/v1/products/{id}/stock")
    @CircuitBreaker(name = "product-service")
    ProductDTO updateStock(@PathVariable("id") Long productId, @RequestBody StockUpdateRequest request);
}

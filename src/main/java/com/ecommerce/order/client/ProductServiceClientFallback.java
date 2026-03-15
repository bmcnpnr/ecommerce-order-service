package com.ecommerce.order.client;

import com.ecommerce.order.dto.ProductDTO;
import com.ecommerce.order.dto.StockUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProductServiceClientFallback implements ProductServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceClientFallback.class);

    @Override
    public ProductDTO getProductById(Long productId) {
        log.error("Circuit breaker open: product-service getProductById({})", productId);
        throw new RuntimeException("Product service unavailable. Please try again later.");
    }

    @Override
    public ProductDTO updateStock(Long productId, StockUpdateRequest request) {
        log.error("Circuit breaker open: product-service updateStock({}, {})", productId, request.getDelta());
        throw new RuntimeException("Product service unavailable. Please try again later.");
    }
}

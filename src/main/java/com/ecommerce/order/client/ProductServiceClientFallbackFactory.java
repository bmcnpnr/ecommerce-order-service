package com.ecommerce.order.client;

import com.ecommerce.order.dto.ProductDTO;
import com.ecommerce.order.dto.StockUpdateRequest;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * The circuit breaker hands every failure to its fallback, a 404 or 400 from product-service included,
 * and without a fallback it would wrap them all in {@code NoFallbackAvailableException}. This one gives
 * the original exception back untouched, so {@code GlobalExceptionHandler} maps it exactly as it would
 * without a breaker (404 stays 404, 400 stays 400) and an open breaker
 * ({@code CallNotPermittedException}) becomes a 503 there.
 *
 * <p>It logs nothing: during an outage it would run once per request. The breaker's transitions are
 * logged once ({@code CircuitBreakerTransitionLogger}) and its metrics count the calls.
 */
@Component
public class ProductServiceClientFallbackFactory implements FallbackFactory<ProductServiceClient> {

    @Override
    public ProductServiceClient create(Throwable cause) {
        return new ProductServiceClient() {
            @Override
            public ProductDTO getProductById(Long productId) {
                throw rethrow(cause);
            }

            @Override
            public ProductDTO updateStock(Long productId, StockUpdateRequest request) {
                throw rethrow(cause);
            }
        };
    }

    private static RuntimeException rethrow(Throwable cause) {
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
    }
}

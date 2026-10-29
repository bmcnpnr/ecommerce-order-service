package com.ecommerce.order.client;

import com.ecommerce.order.dto.StockUpdateRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fallback fails fast with a message containing "unavailable": that word is
 * what GlobalExceptionHandler.handleRuntime keys on to answer 503 instead of 500.
 */
class ProductServiceClientFallbackTest {

    private final ProductServiceClientFallback fallback = new ProductServiceClientFallback();

    @Test
    void getProductById_throwsServiceUnavailable() {
        assertThatThrownBy(() -> fallback.getProductById(1L))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Product service unavailable. Please try again later.");
    }

    @Test
    void updateStock_throwsServiceUnavailable() {
        assertThatThrownBy(() -> fallback.updateStock(1L, new StockUpdateRequest(-2)))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessageContaining("unavailable");
    }
}

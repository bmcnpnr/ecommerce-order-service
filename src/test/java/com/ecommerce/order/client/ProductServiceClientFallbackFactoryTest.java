package com.ecommerce.order.client;

import com.ecommerce.order.dto.StockUpdateRequest;
import feign.FeignException;
import feign.Request;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fallback exists so the circuit breaker does not wrap product-service's own answers in
 * {@code NoFallbackAvailableException}: whatever failed is thrown again, unchanged, so the exception handler
 * maps a 404 to 404 and an open breaker to 503 exactly as it would without one.
 */
class ProductServiceClientFallbackFactoryTest {

    private final ProductServiceClientFallbackFactory factory = new ProductServiceClientFallbackFactory();

    private static FeignException.NotFound notFound() {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new FeignException.NotFound("not found", request, null, null);
    }

    @Test
    void aClientError_comesBackAsItself_forBothCalls() {
        FeignException.NotFound cause = notFound();
        ProductServiceClient fallback = factory.create(cause);

        assertThatThrownBy(() -> fallback.getProductById(1L)).isSameAs(cause);
        assertThatThrownBy(() -> fallback.updateStock(1L, new StockUpdateRequest(-2))).isSameAs(cause);
    }

    @Test
    void anOpenBreaker_comesBackAsCallNotPermitted() {
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("product-service"));

        assertThatThrownBy(() -> factory.create(open).getProductById(1L)).isSameAs(open);
    }

    @Test
    void aCheckedFailure_isWrappedNotLost() {
        Exception checked = new java.io.IOException("reset");

        assertThatThrownBy(() -> factory.create(checked).getProductById(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(checked);
    }
}

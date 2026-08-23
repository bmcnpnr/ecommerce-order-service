package com.ecommerce.order.contract;

import au.com.dius.pact.consumer.dsl.LambdaDsl;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.MockServerConfig;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.config.AppConfig;
import com.ecommerce.order.config.FeignConfig;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.dto.StockUpdateRequest;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer side of the contract between order-service and product-service.
 *
 * <p>order-service reaches product-service through {@link ProductServiceClient}
 * (Feign, resolved via Eureka as {@code product-service}). These tests run the
 * <em>real</em> Feign client — the production {@link FeignConfig} (converters,
 * retryer), the production Jackson setup from {@link AppConfig} and the hc5
 * transport — against a Pact mock server that plays product-service, and drive
 * it through {@link OrderService} so the pact records what order-service
 * actually sends and which response fields it actually reads.
 *
 * <p>Running this class writes {@code target/pacts/order-service-product-service.json}.
 * That file is copied into product-service's {@code src/test/resources/pacts/} (see
 * {@code ecommerce-platform/sync-pacts.sh}) where {@code ProductServiceProviderPactTest}
 * replays every interaction against the real product-service.
 *
 * <p>Only the response fields order-service reads are part of the contract:
 * {@code sku}, {@code name}, {@code price}, {@code stockQuantity} (and {@code id}).
 * {@code ProductDTO} also declares {@code brand} and {@code status}, but nothing in
 * order-service reads them, so product-service stays free to change those.
 */
@SpringBootTest(
        classes = ProductServiceConsumerPactTest.FeignOnlyConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Point the production Feign client (lb://product-service) at the Pact mock
                // server instead of the load balancer. The port is fixed because the Spring
                // context is built before Pact starts the mock server for each test.
                "spring.cloud.openfeign.client.config.product-service.url=http://localhost:" + ProductServiceConsumerPactTest.MOCK_PORT,
                "spring.cloud.openfeign.client.config.default.connect-timeout=2000",
                "spring.cloud.openfeign.client.config.default.read-timeout=10000",
                // Pact restarts its mock server for every test method; a pooled keep-alive
                // connection from the previous test would be stale. Expire pooled
                // connections immediately so each call opens a fresh one (test-only).
                "spring.cloud.openfeign.httpclient.time-to-live=1",
                "spring.cloud.openfeign.httpclient.time-to-live-unit=MILLISECONDS",
                "spring.cloud.discovery.enabled=false",
                "eureka.client.enabled=false"
        })
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "product-service", pactVersion = PactSpecVersion.V4)
@MockServerConfig(port = ProductServiceConsumerPactTest.MOCK_PORT)
class ProductServiceConsumerPactTest {

    static final String CONSUMER = "order-service";
    static final String MOCK_PORT = "18082";

    /**
     * Just enough of the application to build the real Feign client: Spring Cloud
     * OpenFeign, Boot's HTTP message converters and Jackson, plus the service's own
     * {@link FeignConfig} and {@link AppConfig}. No JPA, Kafka or Eureka.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @EnableFeignClients(clients = ProductServiceClient.class)
    @Import({FeignConfig.class, AppConfig.class, OrderService.class})
    static class FeignOnlyConfig {
    }

    @Autowired
    private ProductServiceClient productServiceClient;

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private OrderEventProducer orderEventProducer;

    // ───────────────────────────── pacts ─────────────────────────────

    @Pact(consumer = CONSUMER)
    V4Pact addItemFetchesProductAndReservesStock(PactDslWithProvider builder) {
        return builder
                .given("product 1 exists")
                .uponReceiving("a request for product 1")
                    .method("GET")
                    .path("/api/v1/products/1")
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(product -> product
                            .integerType("id", 1)
                            .stringType("sku", "SKU-001")
                            .stringType("name", "Wireless Mouse")
                            // BigDecimal on both sides; any JSON number is acceptable.
                            .numberType("price", 49.99)
                            .integerType("stockQuantity", 50)).build())
                .given("product 1 exists")
                .uponReceiving("a request to reserve 2 units of product 1")
                    .method("PATCH")
                    .path("/api/v1/products/1/stock")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body("{\"delta\":-2}")
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(product -> product
                            .integerType("id", 1)
                            .integerType("stockQuantity", 48)).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact cancelOrderRestoresStock(PactDslWithProvider builder) {
        return builder
                .given("product 1 exists")
                .uponReceiving("a request to restore 2 units of product 1")
                    .method("PATCH")
                    .path("/api/v1/products/1/stock")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body("{\"delta\":2}")
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(product -> product
                            .integerType("id", 1)
                            .integerType("stockQuantity", 52)).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownProductIsNotFound(PactDslWithProvider builder) {
        return builder
                .given("product 999 does not exist")
                .uponReceiving("a request for product 999")
                    .method("GET")
                    .path("/api/v1/products/999")
                .willRespondWith()
                    .status(404)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(error -> error
                            .integerType("status", 404)
                            .stringType("error", "Not Found")
                            .stringType("message", "Product not found with id: 999")).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact reservingMoreThanAvailableIsRejected(PactDslWithProvider builder) {
        return builder
                .given("product 1 exists")
                .uponReceiving("a request to reserve 1000 units of product 1")
                    .method("PATCH")
                    .path("/api/v1/products/1/stock")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body("{\"delta\":-1000}")
                .willRespondWith()
                    .status(400)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(error -> error
                            .integerType("status", 400)
                            .stringType("error", "Bad Request")
                            .stringType("message", "Insufficient stock for product SKU-001. Available: 50, Requested: 1000")).build())
                .toPact(V4Pact.class);
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    @PactTestFor(pactMethod = "addItemFetchesProductAndReservesStock")
    void addItemToOrder_readsNamePriceAndStock_andReservesStock() {
        Order order = pendingOrder(7L);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderDTO result = orderService.addItemToOrder(7L, 1L, 2);

        // Fields order-service copies out of product-service's response.
        assertThat(result.getOrderItems()).hasSize(1);
        assertThat(result.getOrderItems().get(0).getProductId()).isEqualTo(1L);
        assertThat(result.getOrderItems().get(0).getProductName()).isEqualTo("Wireless Mouse");
        assertThat(result.getOrderItems().get(0).getProductPrice()).isEqualByComparingTo(new BigDecimal("49.99"));
        assertThat(result.getOrderItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("99.98"));
        // The PATCH with {"delta":-2} is asserted by the mock server: Pact fails the
        // test if any interaction of the pact was not received exactly as declared.
    }

    @Test
    @PactTestFor(pactMethod = "cancelOrderRestoresStock")
    void cancelOrder_restoresStockPerItem() {
        Order order = pendingOrder(7L);
        order.addItem(OrderItem.builder().productId(1L).productName("Wireless Mouse")
                .productPrice(new BigDecimal("49.99")).quantity(2).build());
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrder(7L);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderEventProducer).publishOrderCancelled(any());
    }

    @Test
    @PactTestFor(pactMethod = "unknownProductIsNotFound")
    void getProductById_unknownProduct_surfacesAsFeignNotFound() {
        // GlobalExceptionHandler maps FeignException by status(): 404 -> 404 to the caller.
        assertThatThrownBy(() -> productServiceClient.getProductById(999L))
                .isInstanceOf(FeignException.NotFound.class)
                .satisfies(ex -> assertThat(((FeignException) ex).status()).isEqualTo(404));
    }

    @Test
    @PactTestFor(pactMethod = "reservingMoreThanAvailableIsRejected")
    void updateStock_insufficientStock_surfacesAsFeignBadRequest() {
        assertThatThrownBy(() -> productServiceClient.updateStock(1L, new StockUpdateRequest(-1000)))
                .isInstanceOf(FeignException.BadRequest.class)
                .satisfies(ex -> assertThat(((FeignException) ex).status()).isEqualTo(400));
    }

    private static Order pendingOrder(long id) {
        return Order.builder()
                .orderId(id)
                .customerId("customer-42")
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .orderItems(new ArrayList<>())
                .build();
    }
}

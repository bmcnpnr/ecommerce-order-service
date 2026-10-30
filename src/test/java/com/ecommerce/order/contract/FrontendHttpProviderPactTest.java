package com.ecommerce.order.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Provider side of the HTTP contract the web frontend (consumer {@code ecommerce-frontend})
 * holds against order-service.
 *
 * <p>The pact lives in its own folder ({@code src/test/resources/pacts-frontend}) so the
 * Kafka message verification in {@link OrderEventsProviderPactTest} ({@code pacts}) never
 * sees these HTTP interactions and vice versa.
 *
 * <p>The SPA calls through the API gateway, which validates the JWT and injects
 * {@code X-Username}, {@code X-User-Id} and {@code X-User-Role}; paths are not rewritten.
 * {@link #verifyPact} reproduces the header injection from the bearer token in the pact request.
 * product-service (Feign) is played by WireMock, Kafka publishing is mocked, the database is H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Provider("order-service")
@PactFolder("pacts-frontend")
class FrontendHttpProviderPactTest {

    static final WireMockServer PRODUCT_SERVICE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PRODUCT_SERVICE.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PRODUCT_SERVICE::stop));
    }

    @DynamicPropertySource
    static void productServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri",
                PRODUCT_SERVICE::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context, HttpRequest request) {
        addGatewayIdentityHeaders(request);
        context.verifyInteraction();
    }

    /** What api-gateway's JwtAuthenticationFilter does for an authenticated route (signature not checked). */
    static void addGatewayIdentityHeaders(HttpRequest request) {
        Header auth = request.getFirstHeader("Authorization");
        if (auth == null || !auth.getValue().startsWith("Bearer ")) {
            return; // public route: the gateway adds nothing
        }
        String payload = auth.getValue().substring("Bearer ".length()).split("\\.")[1];
        JsonNode claims = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(payload));
        request.setHeader("X-Username", text(claims, "sub"));
        request.setHeader("X-User-Id", text(claims, "userId"));
        request.setHeader("X-User-Role", text(claims, "role"));
    }

    private static String text(JsonNode claims, String name) {
        return claims.has(name) ? claims.get(name).asText() : "";
    }

    // ───────────────────────── provider states ─────────────────────────

    /** Order 7 (customer alice) PENDING with one item: product 1, 2 x 49.99. */
    @State("order 7 is pending with items")
    void order7PendingWithItems() {
        reset();
        insertOrder(7L, "alice", "PENDING", new BigDecimal("99.98"));
        insertItem(70L, 7L, 1L, "Wireless Mouse", new BigDecimal("49.99"), 2);
    }

    /** Order 7 PENDING and empty; product-service reports product 1 with 50 in stock. */
    @State("order 7 is pending and product 1 has stock")
    void order7PendingAndProductHasStock() {
        reset();
        insertOrder(7L, "alice", "PENDING", BigDecimal.ZERO);
    }

    /**
     * The admin interaction ships the order. PATCH /status only allows legal transitions
     * (CONFIRMED -> SHIPPED skips payment and is a 409), so the data behind this state is a paid order,
     * which is what an administrator can actually ship.
     */
    @State("order 7 is confirmed")
    void order7Confirmed() {
        reset();
        insertOrder(7L, "alice", "PAID", new BigDecimal("99.98"));
        insertItem(70L, 7L, 1L, "Wireless Mouse", new BigDecimal("49.99"), 2);
    }

    @State("customer alice has orders")
    void customerAliceHasOrders() {
        reset();
        insertOrder(7L, "alice", "PENDING", new BigDecimal("99.98"));
        insertItem(70L, 7L, 1L, "Wireless Mouse", new BigDecimal("49.99"), 2);
        insertOrder(8L, "alice", "CONFIRMED", new BigDecimal("49.99"));
        insertItem(80L, 8L, 1L, "Wireless Mouse", new BigDecimal("49.99"), 1);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Clean tables and (re)stub product-service: product 1 with price 49.99 and 50 in stock. */
    private void reset() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        PRODUCT_SERVICE.resetAll();
        String product = "{\"id\":1,\"sku\":\"SKU-001\",\"name\":\"Wireless Mouse\",\"price\":49.99,"
                + "\"stockQuantity\":50,\"brand\":\"Logi\",\"status\":\"ACTIVE\"}";
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/1")).willReturn(okJson(product)));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/1/stock")).willReturn(okJson(product)));
    }

    private void insertOrder(Long id, String customerId, String status, BigDecimal total) {
        jdbc.update("INSERT INTO orders (order_id, customer_id, order_date, total_amount, status) VALUES (?, ?, ?, ?, ?)",
                id, customerId, LocalDateTime.now(), total, status);
    }

    private void insertItem(Long id, Long orderId, Long productId, String name, BigDecimal price, int quantity) {
        jdbc.update("INSERT INTO order_items (order_item_id, order_id, product_id, product_name, product_price, quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, orderId, productId, name, price, quantity);
    }
}

package com.ecommerce.order.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * How the order service behaves around product-service, end to end through the real Feign client, the
 * Spring Cloud circuit breaker, the exception handler and Postgres: the pieces H1/H2 are about.
 */
class ProductServiceResilienceIT extends ApplicationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private DataSource dataSource;
    @Autowired private ApplicationContext context;

    private final ExecutorService clients = Executors.newFixedThreadPool(40);

    @BeforeEach
    void closedBreaker() {
        breakers.circuitBreaker("product-service").reset();
    }

    @AfterEach
    void cleanUp() {
        breakers.circuitBreaker("product-service").reset();
        clients.shutdownNow();
    }

    private static String productJson(long id, String price) {
        return "{\"id\":%d,\"sku\":\"SKU-%d\",\"name\":\"Trail Shoe %d\",\"price\":%s,\"stockQuantity\":500,\"status\":\"ACTIVE\"}"
                .formatted(id, id, id, price);
    }

    private long newOrder(String customer) {
        return JSON.readTree(callAsAdmin(HttpMethod.POST, "/api/v1/orders?customerId=" + customer, null).getBody()).get("orderId").asLong();
    }

    private ResponseEntity<String> addItem(long orderId, long productId) {
        return callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", productId, "quantity", 1));
    }

    /** The highest number of requests for {@code url} that product-service had in flight at the same time. */
    private static int maxOverlap(String url) {
        List<long[]> spans = new ArrayList<>();
        for (ServeEvent event : PRODUCT_SERVICE.getAllServeEvents()) {
            if (event.getRequest().getUrl().equals(url) && event.getTiming() != null) {
                long start = event.getRequest().getLoggedDate().getTime();
                spans.add(new long[]{start, start + event.getTiming().getTotalTime()});
            }
        }
        int max = 0;
        for (long[] a : spans) {
            int overlapping = 0;
            for (long[] b : spans) {
                if (b[0] <= a[0] && a[0] < b[1]) {
                    overlapping++;
                }
            }
            max = Math.max(max, overlapping);
        }
        return max;
    }

    // ---- the Spring Cloud circuit breaker must not impose limits nobody asked for ----

    /** Resilience4j's time limiter defaults to 1 s and would turn every slower (but healthy) answer into a failure. */
    @Test
    void aSlowButHealthyProductService_isNotCutOffByTheDefaultOneSecondTimeLimiter() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/91")).willReturn(okJson(productJson(91, "9.99")).withFixedDelay(2500)));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/91/stock")).willReturn(okJson(productJson(91, "9.99"))));
        long orderId = newOrder("cust-slow");

        long start = System.nanoTime();
        ResponseEntity<String> response = addItem(orderId, 91);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2400);
    }

    /**
     * The default is a thread-pool bulkhead of one thread per CPU (two in a 2-CPU container) with a 100-call
     * queue: everything beyond that is rejected with BulkheadFullException.
     */
    @Test
    void theBreakerAddsNoBulkhead_soManyCallsToProductServiceRunAtTheSameTime() throws Exception {
        assertThat(context.getBeanProvider(Resilience4jBulkheadProvider.class).getIfAvailable())
                .as("spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false").isNull();
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/92")).willReturn(okJson(productJson(92, "1.00")).withFixedDelay(1500)));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/92/stock")).willReturn(okJson(productJson(92, "1.00"))));
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            orders.add(newOrder("cust-concurrent-" + i));
        }

        List<CompletableFuture<ResponseEntity<String>>> calls = orders.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> addItem(id, 92), clients)).toList();
        for (CompletableFuture<ResponseEntity<String>> call : calls) {
            assertThat(call.get().getStatusCode().value()).as(call.get().getBody()).isEqualTo(200);
        }

        assertThat(maxOverlap("/api/v1/products/92")).as("product-service calls in flight at once").isGreaterThanOrEqualTo(16);
    }

    // ---- no hidden retries ----

    /** Apache HttpClient 5 retries an idempotent GET once on a 503 by itself (R-6); the only retry is the connect-failure one. */
    @Test
    void aGetThatGets503_reachesProductServiceExactlyOnce() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/93")).willReturn(aResponse().withStatus(503)));
        long orderId = newOrder("cust-503");

        ResponseEntity<String> response = addItem(orderId, 93);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/93")));
    }

    @Test
    void aConnectionResetByThePeer_is503_andReachesProductServiceExactlyOnce() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/94"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        long orderId = newOrder("cust-reset");

        ResponseEntity<String> response = addItem(orderId, 94);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/94")));
    }

    // ---- the breaker: counts the right things, fails fast, speaks once ----

    @Test
    void clientErrorsFromProductService_neverTripTheBreaker() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/95")).willReturn(aResponse().withStatus(404)));
        long orderId = newOrder("cust-404");

        for (int i = 0; i < 30; i++) {
            assertThat(addItem(orderId, 95).getStatusCode().value()).isEqualTo(404);
        }

        CircuitBreaker breaker = breakers.circuitBreaker("product-service");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void aFailingProductService_opensTheBreaker_whichThenFailsFastWith503_logsTwiceAndRecovers() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/96")).willReturn(aResponse().withStatus(500)));
        long orderId = newOrder("cust-outage");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger ours = (Logger) LoggerFactory.getLogger("com.ecommerce.order");
        ours.addAppender(logs);
        try {
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                statuses.add(addItem(orderId, 96).getStatusCode().value());
            }

            // until the breaker opens product-service's 500 is a 502; afterwards the call is not even made
            assertThat(statuses.subList(0, 5)).containsOnly(502);
            assertThat(statuses.subList(5, 60)).containsOnly(503);
            PRODUCT_SERVICE.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/96")));
            assertThat(breakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 60 failing requests: a handful of lines, one stack trace at most, none per rejected call
            List<ILoggingEvent> loud = logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
            assertThat(loud).hasSizeLessThanOrEqualTo(5);
            assertThat(loud.stream().filter(e -> e.getThrowableProxy() != null)).hasSizeLessThanOrEqualTo(1);
            assertThat(loud).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("Circuit breaker 'product-service'").contains("OPEN"));

            // product-service heals: after the wait the breaker lets calls through and closes again
            PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/96")).willReturn(okJson(productJson(96, "5.00"))));
            PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/96/stock")).willReturn(okJson(productJson(96, "5.00"))));
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                assertThat(addItem(orderId, 96).getStatusCode().value()).isEqualTo(200);
                assertThat(breakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            });
            assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("-> CLOSED"));
        } finally {
            ours.detachAppender(logs);
        }
    }

    /** Slow is a failure mode too: every call that takes the full read timeout holds a request thread. */
    @Test
    void aProductServiceThatAnswersTooSlowly_opensTheBreaker_andLaterCallsFailFast() throws Exception {
        // both endpoints share one breaker, so a product-service that is slow is slow for the lookup and the stock call alike
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/99")).willReturn(okJson(productJson(99, "3.00")).withFixedDelay(5300)));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/99/stock")).willReturn(okJson(productJson(99, "3.00")).withFixedDelay(5300)));
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orders.add(newOrder("cust-slowcalls-" + i));
        }

        // five slow (but successful) answers fill the window, 100 % of them over the 5 s threshold. The breaker
        // may open between a request's lookup and its stock call, so each of the five ends 200 or 503.
        List<CompletableFuture<ResponseEntity<String>>> slow = orders.subList(0, 5).stream()
                .map(id -> CompletableFuture.supplyAsync(() -> addItem(id, 99), clients)).toList();
        for (CompletableFuture<ResponseEntity<String>> call : slow) {
            assertThat(call.get().getStatusCode().value()).as(call.get().getBody()).isIn(200, 503);
        }
        assertThat(breakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        ResponseEntity<String> refused = addItem(orders.get(5), 99);
        long refusedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refusedMs).as("refused by the open breaker, not after another 5 s").isLessThan(1000);
        PRODUCT_SERVICE.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/99")));
    }

    @Test
    void theBreakerMetricsAreExported() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/97")).willReturn(okJson(productJson(97, "5.00"))));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/97/stock")).willReturn(okJson(productJson(97, "5.00"))));
        addItem(newOrder("cust-metrics"), 97);

        String scrape = call(HttpMethod.GET, "/actuator/prometheus", null).getBody();

        assertThat(scrape).contains("resilience4j_circuitbreaker_state").contains("name=\"product-service\"");
    }

    // ---- no database connection is held while product-service is slow (H2) ----

    @Test
    void aSlowProductService_doesNotPinDatabaseConnections_soTheRestOfTheServiceStaysResponsive() throws Exception {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/98")).willReturn(okJson(productJson(98, "2.00")).withFixedDelay(3000)));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/98/stock")).willReturn(okJson(productJson(98, "2.00"))));
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {   // twice the pool size (10)
            orders.add(newOrder("cust-pin-" + i));
        }
        long probeOrder = newOrder("cust-probe");
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        List<CompletableFuture<ResponseEntity<String>>> slow = orders.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> addItem(id, 98), clients)).toList();
        Thread.sleep(1000); // all twenty are now waiting on product-service

        int busiest = 0;
        for (int i = 0; i < 5; i++) {
            busiest = Math.max(busiest, pool.getHikariPoolMXBean().getActiveConnections());
            Thread.sleep(100);
        }
        long start = System.nanoTime();
        ResponseEntity<String> probe = callAsAdmin(HttpMethod.GET, "/api/v1/orders/" + probeOrder, null);
        long probeMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(busiest).as("pooled connections held while 20 requests wait for product-service").isLessThanOrEqualTo(2);
        assertThat(probe.getStatusCode().value()).isEqualTo(200);
        assertThat(probeMs).as("an unrelated read while 20 slow requests are in flight").isLessThan(1000);
        for (CompletableFuture<ResponseEntity<String>> call : slow) {
            assertThat(call.get().getStatusCode().value()).as(call.get().getBody()).isEqualTo(200);
        }
    }
}

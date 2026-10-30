package com.ecommerce.order.exception;

import com.ecommerce.order.controller.OrderController;
import com.ecommerce.order.service.OrderService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error paths of the order API through the real MVC stack: controller, bean
 * validation, CorrelationIdFilter and GlobalExceptionHandler, service mocked.
 */
@WebMvcTest(OrderController.class)
class GlobalExceptionHandlerTest {

    private static final String CID = "X-Correlation-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    // Authorization rules are unit-tested in OrderAccessTest and end to end in OrderSecurityIT;
    // here the controller wiring is under test, so the guard lets the owner argument through.
    @MockitoBean
    private com.ecommerce.order.security.OrderAccess access;

    @org.junit.jupiter.api.BeforeEach
    void passThroughOwner() {
        org.mockito.BDDMockito.given(access.resolveCustomer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willAnswer(i -> i.getArgument(2));
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).reason("reason").request(request)
                .headers(Map.of()).body("downstream body", StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("ProductServiceClient#getProductById(Long)", response);
    }

    @Test
    void orderNotFound_is404WithErrorBody() throws Exception {
        given(orderService.getOrderById(99L)).willThrow(new OrderNotFoundException("Order not found: 99"));

        mockMvc.perform(get("/api/v1/orders/99").header(CID, "cid-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Order not found: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/orders/99"))
                .andExpect(jsonPath("$.correlationId").value("cid-1"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void invalidOrderStateOnConfirm_is400() throws Exception {
        given(orderService.confirmOrder(1L))
                .willThrow(new InvalidOrderStateException("Can only confirm PENDING orders. Current status: PAID"));

        mockMvc.perform(post("/api/v1/orders/1/confirm"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Can only confirm PENDING orders. Current status: PAID"))
                .andExpect(jsonPath("$.correlationId").value(notNullValue()));
    }

    @Test
    void invalidOrderStateOnCancel_is400() throws Exception {
        willThrow(new InvalidOrderStateException("Cannot cancel order in status: SHIPPED"))
                .given(orderService).cancelOrder(1L);

        mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot cancel order in status: SHIPPED"));
    }

    @Test
    void addItem_missingFields_is400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items").header(CID, "cid-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.correlationId").value("cid-2"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(containsInAnyOrder("productId", "quantity")))
                .andExpect(jsonPath("$.fieldErrors[*].message")
                        .value(containsInAnyOrder("Product ID is required", "Quantity is required")));
        verifyNoInteractions(orderService);
    }

    @Test
    void addItem_quantityBelowOne_is400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Quantity must be at least 1"));
        verifyNoInteractions(orderService);
    }

    @Test
    void addItem_malformedJson_is400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createOrder_missingCustomerId_is400() throws Exception {
        mockMvc.perform(post("/api/v1/orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("customerId")));
    }

    @Test
    void nonNumericOrderId_is400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void unknownStatusValue_is400() throws Exception {
        mockMvc.perform(patch("/api/v1/orders/1/status").param("status", "LOST"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void unsupportedMethod_is405() throws Exception {
        mockMvc.perform(put("/api/v1/orders/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @ParameterizedTest
    @CsvSource({
            "404, 404",
            "400, 400",
            "409, 409",
            "503, 503",
            "500, 502",
            "502, 502"
    })
    void feignErrors_areMappedToMatchingStatus(int downstream, int expected) throws Exception {
        given(orderService.addItemToOrder(1L, 7L, 1)).willThrow(feignError(downstream));

        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "7"))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.message").value(containsString("Downstream service call failed")));
    }

    @Test
    void feignCallThatNeverCompleted_is503() throws Exception {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        given(orderService.addItemToOrder(1L, 7L, 1))
                .willThrow(new RetryableException(-1, "Connection refused", Request.HttpMethod.GET, (Long) null, request));

        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "7"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unexpectedRuntimeException_is500WithoutLeakingDetails() throws Exception {
        given(orderService.getOrderById(1L)).willThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void unexpectedCheckedException_is500() throws Exception {
        willAnswer(inv -> { throw new java.io.IOException("disk"); }).given(orderService).getOrderById(1L);

        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    // ---- dependencies that do not answer are a 503 and one line, not a 500 and a stack trace (L3, L4) ----

    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;

    @BeforeEach
    void captureHandlerLog() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseHandlerLog() {
        handlerLogger.detachAppender(logs);
    }

    @Test
    void breakerOpen_is503WithTheDownstreamMessage() throws Exception {
        given(orderService.addItemToOrder(1L, 7L, 1))
                .willThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("product-service")));

        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Downstream service call failed: service unavailable"))
                .andExpect(jsonPath("$.correlationId").value(notNullValue()));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("databaseOutages")
    void databaseTrouble_is503WithAFixedMessage(RuntimeException outage) throws Exception {
        given(orderService.getOrderById(1L)).willThrow(outage);

        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret-host"))));
    }

    static java.util.stream.Stream<RuntimeException> databaseOutages() {
        java.sql.SQLException cause = new java.sql.SQLException("connect to secret-host:5432 refused");
        return java.util.stream.Stream.of(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", cause),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction", cause),
                new DataAccessResourceFailureException("connection reset", cause),
                new QueryTimeoutException("statement timeout", cause),
                new CannotAcquireLockException("lock timeout", cause),
                // wrapped by something else on the way up
                new IllegalStateException("tx failed", new CannotGetJdbcConnectionException("pool", cause)));
    }

    @Test
    void optimisticLockConflict_is409NotAnOutage() throws Exception {
        given(orderService.confirmOrder(1L)).willThrow(new ObjectOptimisticLockingFailureException(com.ecommerce.order.model.Order.class, 1L));

        mockMvc.perform(post("/api/v1/orders/1/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The order was modified by another request"));
    }

    private static final class DatabaseGoneForThisTest extends CannotGetJdbcConnectionException {
        DatabaseGoneForThisTest() {
            super("pool exhausted");
        }
    }

    private static final class BugForThisTest extends RuntimeException {
        BugForThisTest() {
            super("boom");
        }
    }

    /** A dead database under load must cost one line, not one per request, and no stack trace. */
    @Test
    void aHundredRequestsDuringAnOutage_writeOneWarnWithoutStackTrace() throws Exception {
        given(orderService.getOrderById(1L)).willThrow(new DatabaseGoneForThisTest());

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/orders/1")).andExpect(status().isServiceUnavailable());
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getThrowableProxy()).as("no stack trace for a dead dependency").isNull();
                });
    }

    /** A bug on a hot path logs its stack trace once per window, however many requests hit it. */
    @Test
    void aHundredRequestsHittingOneBug_writeOneStackTrace() throws Exception {
        given(orderService.getOrderById(1L)).willThrow(new BugForThisTest());

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/orders/1")).andExpect(status().isInternalServerError());
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(1)
                .allSatisfy(e -> assertThat(e.getThrowableProxy()).isNotNull());
    }

    @Test
    void aHundredDownstream404s_writeAtMostOneLine() throws Exception {
        given(orderService.addItemToOrder(1L, 7L, 1)).willThrow(feignError(404));

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "7")).andExpect(status().isNotFound());
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).hasSizeLessThanOrEqualTo(1)
                .allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    @Test
    void aPeerThatNeverAnswers_isAtMostOneLineWithoutStackTrace() throws Exception {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        given(orderService.addItemToOrder(1L, 8L, 1))
                .willThrow(new RetryableException(-1, "Read timed out", Request.HttpMethod.GET,
                        new java.net.SocketTimeoutException("Read timed out"), (Long) null, request));

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "8")).andExpect(status().isServiceUnavailable());
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).hasSizeLessThanOrEqualTo(1)
                .allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    /**
     * A killed database backend: the rollback fails too, and Spring's TransactionSystemException replaces the original
     * error. It is an outage (503, one line), not a bug (500 and hundreds of lines of stack trace).
     */
    @Test
    void aRollbackThatFailedBecauseTheBackendWasKilled_is503_andCostsOneLineForAHundredRequests() throws Exception {
        org.springframework.transaction.TransactionSystemException failure = new org.springframework.transaction.TransactionSystemException(
                "Could not roll back JPA transaction", new jakarta.persistence.PersistenceException("Unable to rollback against JDBC Connection",
                new java.sql.SQLException("This connection has been closed. secret-host", "08003")));
        given(orderService.getOrderById(1L)).willThrow(failure);

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/orders/1"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret-host"))));
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).hasSizeLessThanOrEqualTo(1)
                .allSatisfy(e -> {
                    assertThat(e.getThrowableProxy()).as("no stack trace for a dead database").isNull();
                    assertThat(e.getFormattedMessage()).contains("TransactionSystemException (SQLException: This connection has been closed.");
                });
    }
}

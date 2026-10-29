package com.ecommerce.order.security;

import com.ecommerce.order.controller.OrderController;
import com.ecommerce.order.exception.InvalidOrderStateException;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input-validation and error-body hygiene of the HTTP layer, with OrderService mocked: whatever
 * a hostile caller sends must be refused before it reaches the service, and the refusal must not
 * leak internals. Authorization (IDOR) is covered end to end in OrderSecurityIT.
 */
@Tag("security")
@WebMvcTest(OrderController.class)
class OrderControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;
    // Authorization is covered by OrderAccessTest and OrderSecurityIT; here the guard lets the owner argument through.
    @MockitoBean private com.ecommerce.order.security.OrderAccess access;

    @org.junit.jupiter.api.BeforeEach
    void passThroughOwner() {
        org.mockito.BDDMockito.given(access.resolveCustomer(any(), any(), any())).willAnswer(i -> i.getArgument(2));
    }

    private static final String[] INTERNALS = {
            "com.ecommerce", "java.lang", "java.math", "org.springframework", "org.hibernate", "org.postgresql",
            "jakarta.", "tools.jackson", "com.fasterxml", "Exception", "\tat ", "select ", "SQLState", "ProductServiceClient#"
    };

    private static void assertNoInternals(String body) {
        for (String marker : INTERNALS) {
            assertThat(body).as("error body must not leak '%s'", marker).doesNotContain(marker);
        }
    }

    // ---- /items: bean validation -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"productId\":1,\"quantity\":0}",
            "{\"productId\":1,\"quantity\":-1}",
            "{\"productId\":1,\"quantity\":-2147483648}",
            "{\"productId\":1}",
            "{\"quantity\":1}",
            "{}"
    })
    void addItem_invalidQuantityOrProduct_is400_andNeverReachesTheService(String body) throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"productId\":1,\"quantity\":2147483648}",           // int overflow by one
            "{\"productId\":1,\"quantity\":99999999999999999999}", // far beyond long
            "{\"productId\":1,\"quantity\":\"ten\"}",
            "{\"productId\":\"1 OR 1=1\",\"quantity\":1}",
            "{\"productId\":[1],\"quantity\":1}",
            "{not json",
            ""
    })
    void addItem_unparseableOrOverflowingBody_is400_notAServerError(String body) throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void addItem_clientSuppliedPriceFields_areNotPartOfWhatReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":5,\"quantity\":2,\"price\":0.01,\"productPrice\":0.01,\"totalAmount\":0}"));

        // the service signature has no price at all: only (orderId, productId, quantity) can be passed
        verify(orderService).addItemToOrder(1L, 5L, 2);
    }

    // ---- legacy endpoint ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-5", "-2147483648"})
    void legacyAddProduct_nonPositiveQuantity_isRejected(String quantity) throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "1").param("quantity", quantity))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).addItemToOrder(anyLong(), anyLong(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648", "1;DROP TABLE orders", "1.5"})
    void legacyAddProduct_nonIntegerQuantity_is400(String quantity) throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/products").param("productId", "1").param("quantity", quantity))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    // ---- path / query parameter typing --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1%20OR%201=1", "1%3BDROP%20TABLE%20orders", "-", "abc", "9999999999999999999999"})
    void orderId_thatIsNotALong_is400_andNeverReachesTheService(String orderId) throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + orderId)).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/orders/" + orderId)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")).andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HACKED", "paid", "PAID' OR '1'='1", "", "1"})
    void patchStatus_unknownStatus_is400_andNothingChanges(String status) throws Exception {
        mockMvc.perform(patch("/api/v1/orders/1/status").param("status", status)).andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withoutCustomerId_is400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")).andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void createOrder_blankCustomerId_isRejected(String customerId) throws Exception {
        mockMvc.perform(post("/api/v1/orders").param("customerId", customerId)).andExpect(status().isBadRequest());

        verify(orderService, never()).createOrder(any());
    }

    // ---- HTTP method and media type tampering ------------------------------------------------------

    @Test
    void unsupportedMethods_are405() throws Exception {
        mockMvc.perform(put("/api/v1/orders/1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/v1/orders/1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/orders/1/confirm")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/orders/1/status")).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded"})
    void addItem_wrongContentType_is415(String contentType) throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/items").contentType(contentType).content("productId=1&quantity=1"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(orderService);
    }

    // ---- error bodies ----------------------------------------------------------------------------------

    @Test
    void unexpectedServiceFailure_is500WithAGenericBody() throws Exception {
        given(orderService.getOrderById(7L)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement [ERROR: relation \"orders\" does not exist] [select o1_0.order_id from orders o1_0]"));

        MvcResult result = mockMvc.perform(get("/api/v1/orders/7")).andExpect(status().isInternalServerError()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("An unexpected error occurred");
        assertNoInternals(body);
        assertThat(body).doesNotContain("orders o1_0").doesNotContain("does not exist");
    }

    @Test
    void runtimeFailure_is500WithAGenericBody() throws Exception {
        given(orderService.getOrderById(7L)).willThrow(new IllegalStateException("jdbc:postgresql://orderdb:5432/orderdb password=orderpass"));

        MvcResult result = mockMvc.perform(get("/api/v1/orders/7")).andExpect(status().isInternalServerError()).andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc:").doesNotContain("orderpass");
    }

    @Test
    void businessRuleViolation_is400_andOnlyCarriesTheBusinessMessage() throws Exception {
        given(orderService.confirmOrder(1L)).willThrow(new InvalidOrderStateException("Can only confirm PENDING orders. Current status: PAID"));

        MvcResult result = mockMvc.perform(post("/api/v1/orders/1/confirm")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void malformedJson_errorBodyLeaksNoClassNames() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders/1/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"abc\",\"quantity\":1}")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void wrongTypedPathVariable_errorBodyLeaksNoClassNames() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/abc")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void downstreamFailure_errorBodyLeaksNoInternalTopology() throws Exception {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.GET, "http://product-service/api/v1/products/9",
                java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, new feign.RequestTemplate());
        given(orderService.addItemToOrder(anyLong(), anyLong(), anyInt())).willThrow(new feign.FeignException.NotFound(
                "[404] during [GET] to [http://product-service/api/v1/products/9] [ProductServiceClient#getProductById(Long)]: [secret-body]",
                request, null, null));

        MvcResult result = mockMvc.perform(post("/api/v1/orders/1/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":9,\"quantity\":1}")).andExpect(status().isNotFound()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("product-service/api").doesNotContain("ProductServiceClient").doesNotContain("secret-body");
    }
}

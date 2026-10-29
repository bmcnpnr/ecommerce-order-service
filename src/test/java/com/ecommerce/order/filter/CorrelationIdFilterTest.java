package com.ecommerce.order.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcDuringChain = new AtomicReference<>();
    private final FilterChain capturingChain = (req, res) -> mdcDuringChain.set(MDC.get("correlationId"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesIncomingHeaderToResponseAndMdc() throws Exception {
        request.addHeader(HEADER, "abc-123");

        filter.doFilter(request, response, capturingChain);

        assertThat(response.getHeader(HEADER)).isEqualTo("abc-123");
        assertThat(mdcDuringChain.get()).isEqualTo("abc-123");
    }

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        filter.doFilter(request, response, capturingChain);

        String generated = response.getHeader(HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(mdcDuringChain.get()).isEqualTo(generated);
    }

    @Test
    void generatesUuidWhenHeaderBlank() throws Exception {
        request.addHeader(HEADER, "   ");

        filter.doFilter(request, response, capturingChain);

        assertThat(response.getHeader(HEADER)).isNotBlank().isNotEqualTo("   ");
        assertThat(mdcDuringChain.get()).isEqualTo(response.getHeader(HEADER));
    }

    @Test
    void generatesDistinctIdsPerRequest() throws Exception {
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain);
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), second, capturingChain);

        assertThat(response.getHeader(HEADER)).isNotEqualTo(second.getHeader(HEADER));
    }

    @Test
    void removesMdcEntryAfterRequest() throws Exception {
        request.addHeader(HEADER, "abc-123");

        filter.doFilter(request, response, capturingChain);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void removesMdcEntryEvenWhenChainThrows() {
        request.addHeader(HEADER, "abc-123");
        FilterChain failing = (req, res) -> { throw new IllegalStateException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(response.getHeader(HEADER)).isEqualTo("abc-123");
    }
}

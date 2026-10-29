package com.ecommerce.order.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security")
class ApiFiltersTest {

    private final AtomicBoolean reached = new AtomicBoolean();
    private final FilterChain chain = (req, res) -> reached.set(true);

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders/1", "/internal/v1/orders/1"})
    void securityHeaders_onApiAndInternal(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", path), response, chain);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(reached).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad id", "<script>", "id;drop", "id/1", "a b"})
    void invalidCorrelationId_isReplacedByAUuid_andNeverEchoed(String incoming) throws Exception {
        assertReplaced(incoming);
    }

    @Test
    void overlongCorrelationId_isReplaced() throws Exception {
        assertReplaced("x".repeat(65));
    }

    private void assertReplaced(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        request.addHeader("X-Correlation-Id", incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, chain);

        String sent = response.getHeader("X-Correlation-Id");
        assertThat(sent).isNotEqualTo(incoming);
        assertThat(UUID.fromString(sent).toString()).isEqualTo(sent);
    }

    @Test
    void validCorrelationId_isKept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        request.addHeader("X-Correlation-Id", "Abc.12_3-x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("Abc.12_3-x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "192.168.0.7", "::2", "8.8.8.8", "evil.example.com"})
    void serviceRegistry_isForbiddenFromNonLoopback(String remote) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/serviceregistry");
        request.setRemoteAddr(remote);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ActuatorLoopbackFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(reached).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1"})
    void serviceRegistry_isAllowedFromLoopback(String remote) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/serviceregistry");
        request.setRemoteAddr(remote);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ActuatorLoopbackFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reached).isTrue();
    }

    @Test
    void otherActuatorEndpoints_areNotAffected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("10.1.2.3");

        new ActuatorLoopbackFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(reached).isTrue();
    }

    @Test
    void encodedSpellingOfServiceRegistry_isStillForbidden() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/service%72egistry");
        request.setRemoteAddr("10.1.2.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ActuatorLoopbackFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }
}

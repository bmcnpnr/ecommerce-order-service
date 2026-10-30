package com.ecommerce.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Full application on a random port against the shared Postgres/Kafka containers,
 * with product-service played by WireMock: the real Feign client, load balancer
 * (simple discovery instead of Eureka), decoder and error handling are exercised.
 * All subclasses share one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
abstract class ApplicationTestBase extends ContainersBase {

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
    int port;

    RestClient http;

    @BeforeEach
    void setUpHttp() {
        PRODUCT_SERVICE.resetAll();
        // Apache HttpClient 5 re-sends a request by itself when it is answered with a 503, which would hide
        // (and double) exactly the failures these tests count; and it allows 5 connections per route, which
        // would cap the concurrency the load-shaped tests are meant to create.
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(
                        org.apache.hc.client5.http.impl.classic.HttpClients.custom().disableAutomaticRetries()
                                .setConnectionManager(org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                                        .setMaxConnPerRoute(100).setMaxConnTotal(200).build())
                                .build()))
                .build();
    }

    ResponseEntity<String> call(HttpMethod method, String path, Object jsonBody) {
        var spec = http.method(method).uri(path);
        if (jsonBody != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        return spec.retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Identity the gateway would forward for an administrator. */
    static final java.util.function.Consumer<org.springframework.http.HttpHeaders> ADMIN = h -> {
        h.set("X-Username", "root");
        h.set("X-User-Id", "1");
        h.set("X-User-Role", "ADMIN");
    };

    /** Like {@link #call} but as an administrator (the gateway-forwarded identity headers). */
    ResponseEntity<String> callAsAdmin(HttpMethod method, String path, Object jsonBody) {
        var spec = http.method(method).uri(path).headers(ADMIN);
        if (jsonBody != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        return spec.retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
    }
}

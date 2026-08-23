package com.ecommerce.order.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import feign.Request;
import feign.RetryableException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.junit.jupiter.api.Test;

class ConnectionFailureRetryerTest {

    private static RetryableException retryable(Throwable cause) {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1", Map.of(), null, null, null);
        return new RetryableException(-1, "boom", Request.HttpMethod.GET, cause, (Long) null, request);
    }

    @Test
    void connectionFailuresAreRetriedThenPropagatedAfterTheLastAttempt() {
        FeignConfig.ConnectionFailureRetryer retryer = new FeignConfig.ConnectionFailureRetryer(3, 0);
        retryer.continueOrPropagate(retryable(new HttpHostConnectException("refused")));
        retryer.continueOrPropagate(retryable(new ConnectTimeoutException("connect")));
        RetryableException last = retryable(new ConnectException("refused"));
        assertSame(last, assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(last)));
    }

    @Test
    void aReadTimeoutIsNeverRetried() {
        FeignConfig.ConnectionFailureRetryer retryer = new FeignConfig.ConnectionFailureRetryer(3, 0);
        RetryableException readTimeout = retryable(new SocketTimeoutException("Read timed out"));
        assertSame(readTimeout, assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(readTimeout)));
    }

    @Test
    void cloneStartsAFreshAttemptCounter() {
        FeignConfig.ConnectionFailureRetryer exhausted = new FeignConfig.ConnectionFailureRetryer(2, 0);
        exhausted.continueOrPropagate(retryable(new ConnectException("refused")));
        assertThrows(RetryableException.class, () -> exhausted.continueOrPropagate(retryable(new ConnectException("refused"))));
        exhausted.clone().continueOrPropagate(retryable(new ConnectException("refused")));
    }
}

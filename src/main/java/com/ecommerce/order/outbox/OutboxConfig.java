package com.ecommerce.order.outbox;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} for {@link OutboxRelay}; the relay itself is switched by {@code outbox.relay.enabled}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxConfig {

    /**
     * Spring observes every {@code @Scheduled} execution, and the relay runs twice a second whether or not there
     * is anything to send: with tracing sampled at 100 % that is some 170,000 traces a day of nothing, per
     * service, plus a timer. The relay reports through its own counters and gauges ({@code outbox.*}).
     */
    @Bean
    ObservationPredicate noObservationOfScheduledTasks() {
        return (name, context) -> !"tasks.scheduled.execution".equals(name);
    }
}

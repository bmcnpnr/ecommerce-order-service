# ecommerce-order-service

Orders and their lines. An order starts `PENDING`, and the storefront uses it
as the cart. Adding a line reads the product over Feign, snapshots its name and
price, and reserves stock in product-service. Confirming publishes
`order.placed`. The order becomes `PAID` or `CANCELLED` when payment-service's
`payment.completed` or `payment.failed` arrives on Kafka. Cancelling releases
the stock and publishes `order.cancelled`.

| | |
|---|---|
| Port | 8084 |
| Gateway routes | `/api/v1/orders/**` (JWT required) |
| Data store | PostgreSQL `orderdb` (`orders`, `order_items`, Flyway) |
| Calls | product-service (Feign: get product, adjust stock), with a resilience4j circuit breaker and connect-failure retries |
| Publishes / consumes | `order.placed`, `order.cancelled` / `payment.completed`, `payment.failed` |
| Docs | [Deep dive](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/services/order-service.md) · [API reference](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/api/order-service.md) · [Purchase flow](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/flows.md) · Swagger UI at http://localhost:8080/swagger-ui.html (`order-service`) |

With the repositories checked out side by side, the same docs are in
`../ecommerce-platform/docs/`.

> **Known gaps** (see the deep dive and the
> [findings](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/findings.md)):
> - no ownership checks;
> - `customerId` is taken from the request;
> - the legacy `/products` endpoint accepts negative quantities;
> - a declined payment keeps its stock reserved;
> - payment events are applied whatever the order's state.

## Build and run

```bash
mvn -B verify                       # unit, controller and contract tests (H2, embedded Kafka)
mvn -B clean package -DskipTests    # the jar the Docker image copies
cd ../ecommerce-platform && docker compose up -d --build order-service
```

Kafka is configured in `config/KafkaProducerConfig.java`, not in
`application.yaml`, because Boot's Kafka auto-configuration isn't on the
classpath. Events are JSON from spring-kafka's `JacksonJsonSerializer`, with
`LocalDateTime` kept as integer arrays (`WRITE_DATES_AS_TIMESTAMPS`) as the
consumers and pacts expect.

## Contract tests (Pact)

- `contract/ProductServiceConsumerPactTest`: **consumer** of product-service (Feign). Covers `GET /api/v1/products/{id}`, `PATCH /api/v1/products/{id}/stock`, and 404/400 handling. Writes `target/pacts/order-service-product-service.json`.
- `contract/PaymentEventsConsumerPactTest`: **consumer** of payment-service's `payment.completed` / `payment.failed`, delivered through the real listener container (`StringDeserializer` + `JacksonJsonMessageConverter`) on an embedded Kafka. Writes `target/pacts/order-service-payment-service.json`.
- `contract/OrderEventsProviderPactTest`: **provider** of `order.placed` / `order.cancelled`. Replays notification-service's pact (`src/test/resources/pacts/`) against the real `OrderService` and `OrderEventProducer`, serialized with the production `JacksonJsonSerializer`.

Run them alone with `mvn test -Dtest='*PactTest'`. Redistribute pacts with
`ecommerce-platform/sync-pacts.sh`.

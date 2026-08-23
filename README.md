# ecommerce-order-service
I will develop an e-commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t order-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag order-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-order-service:latest
docker push bmcnpnr/ecommerce-order-service:latest

http://localhost:8080/swagger-ui.html SWAGGER UI
http://localhost:8080/h2-console H2 CONSOLE UI
to be done:
1. Data Consistency: Implement mechanisms to handle failures (e.g., what happens if the Product Microservice is unavailable, or the product ID does not exist). This might involve retry mechanisms, fallbacks, or using a messaging queue for reliable communication.
2. API Design: Ensure your APIs are well-designed and can handle the necessary load. Use API versioning for future compatibility.
3. Security: Secure the communication between services, possibly using OAuth or similar mechanisms.
4. Error Handling: Implement comprehensive error handling for network issues, service unavailability, and data inconsistencies.
5. Asynchronous Communication: In some cases, especially when dealing with long-running operations, it might be beneficial to use asynchronous communication patterns like event-driven architecture.
## Contract tests (Pact)

- `src/test/java/.../contract/ProductServiceConsumerPactTest` — **consumer** of product-service (Feign): `GET /api/v1/products/{id}`, `PATCH /api/v1/products/{id}/stock`, 404/400 handling. Writes `target/pacts/order-service-product-service.json`.
- `.../contract/PaymentEventsConsumerPactTest` — **consumer** of payment-service's `payment.completed` / `payment.failed` records, delivered through the real listener container on an embedded Kafka. Writes `target/pacts/order-service-payment-service.json`.
- `.../contract/OrderEventsProviderPactTest` — **provider** of `order.placed` / `order.cancelled`; replays notification-service's pact (`src/test/resources/pacts/`) against the real `OrderService` + `OrderEventProducer` and the configured `JsonSerializer`.

Run them alone with `mvn test -Dtest='*PactTest'`; they are ordinary Surefire tests, so `mvn verify` and CI run them too. Regenerate and redistribute pacts across repositories with `ecommerce-platform/sync-pacts.sh` (see its README, "Contract tests").

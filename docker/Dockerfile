# Runtime-only base: the jar is built on the host by build-all.ps1 / build-all.sh.
# JRE (not JDK) keeps the image ~150MB smaller; alpine provides busybox wget,
# which the compose healthcheck depends on.
FROM eclipse-temurin:25-jre-alpine

WORKDIR /usr/app

COPY ./target/order-service-*.jar app.jar

# Run unprivileged.
RUN addgroup -S app && adduser -S -G app app && chown -R app:app /usr/app
USER app

EXPOSE 8084

# JAVA_OPTS is set per-service in docker-compose.yml (heap sizing, CPU count).
# 'sh -c' is needed for the variable to be expanded; 'exec' then replaces the
# shell so the JVM is PID 1 and receives SIGTERM directly on `docker stop`.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
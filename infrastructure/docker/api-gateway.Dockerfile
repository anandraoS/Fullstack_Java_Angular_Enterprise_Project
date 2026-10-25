# syntax=docker/dockerfile:1.7
# api-gateway image. Build context = REPOSITORY ROOT; the fat jar must already exist (mvn -f services/pom.xml package).
#   docker build -f infrastructure/docker/api-gateway.Dockerfile -t oms/api-gateway:1.0.0 .
#
# Base image pinning: the tag below is pinned to an exact JRE build. For production ALSO pin the digest
# (tags are mutable):  docker buildx imagetools inspect eclipse-temurin:17.0.13_11-jre-alpine
# then use  eclipse-temurin:17.0.13_11-jre-alpine@sha256:<digest>  and let Renovate/Dependabot bump it.
# The tag was NOT digest-resolved here (no registry access during authoring).
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine-3.24

# ---- stage 1: split the Spring Boot fat jar into cache-friendly layers (deps rarely change, app code often)
FROM ${JRE_IMAGE} AS extract
ARG JAR_FILE=gateway/target/api-gateway-1.0.0.jar
WORKDIR /build
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- stage 2: minimal runtime
FROM ${JRE_IMAGE} AS runtime
ARG APP_PORT=18080
# Non-root, fixed numeric uid/gid so Kubernetes runAsNonRoot can verify it. No shell login, no home.
RUN addgroup -S -g 10001 oms && adduser -S -u 10001 -G oms -H -s /sbin/nologin oms \
    && mkdir -p /app && chown -R oms:oms /app
WORKDIR /app
COPY --from=extract --chown=oms:oms /build/extracted/dependencies/ ./
COPY --from=extract --chown=oms:oms /build/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=oms:oms /build/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=oms:oms /build/extracted/application/ ./

USER 10001:10001
ENV SERVER_PORT=${APP_PORT} \
    SPRING_PROFILES_ACTIVE=json-logs \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s \
    TZ=UTC \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Djava.io.tmpdir=/tmp -Djava.security.egd=file:/dev/urandom -Dfile.encoding=UTF-8"
EXPOSE 18080
# Container-level healthcheck (Compose/Docker). Kubernetes ignores HEALTHCHECK and uses its own probes.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness | grep -q '"status":"UP"' || exit 1
# SIGTERM -> JVM shutdown hook -> Spring graceful shutdown (drains in-flight HTTP, stops Kafka listeners).
STOPSIGNAL SIGTERM
# 'exec' makes the JVM PID 1 so it receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

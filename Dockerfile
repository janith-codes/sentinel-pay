# syntax=docker/dockerfile:1

# Java 21 matches pom.xml java.version.
# Maven 3.9.14 matches the Maven Wrapper distribution in .mvn/wrapper/maven-wrapper.properties.
FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /workspace

# Docker Desktop often advertises IPv6 without a working route. Maven Central is reachable over IPv4.
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"

COPY pom.xml .
COPY sentinel-pay-domain/pom.xml sentinel-pay-domain/pom.xml
COPY sentinel-pay-application/pom.xml sentinel-pay-application/pom.xml
COPY sentinel-pay-infrastructure/pom.xml sentinel-pay-infrastructure/pom.xml
COPY sentinel-pay-api/pom.xml sentinel-pay-api/pom.xml

COPY sentinel-pay-domain/src sentinel-pay-domain/src
COPY sentinel-pay-application/src sentinel-pay-application/src
COPY sentinel-pay-infrastructure/src sentinel-pay-infrastructure/src
COPY sentinel-pay-api/src sentinel-pay-api/src

# The executable archive is the -exec jar. The plain jar stays a library artifact.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package \
    && mkdir -p /out \
    && cp sentinel-pay-api/target/sentinel-pay-api-*-exec.jar /out/app.jar \
    && jar tf /out/app.jar | grep -q 'BOOT-INF/'

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S sentinel \
    && adduser -S -G sentinel -H -h /app sentinel

WORKDIR /app

COPY --from=build --chown=sentinel:sentinel /out/app.jar /app/app.jar

USER sentinel

EXPOSE 8080

# Probes this container only. External services are reached by Compose DNS names.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=8 \
    CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '{"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

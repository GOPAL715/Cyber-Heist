# ---------------------------------------------------------------------------
# Cyber Heist
#
# Multi-stage build. The runtime image carries only the compiled jar, so no
# build tooling or source code ends up in production.
# ---------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are resolved first so this layer is cached across source changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root.
RUN addgroup -S cyberheist && adduser -S cyberheist -G cyberheist

COPY --from=build /build/target/cyber-heist-backend-*.jar app.jar

USER cyberheist
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Uses the Actuator health endpoint; no shell required.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
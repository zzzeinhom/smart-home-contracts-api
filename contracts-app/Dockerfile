# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

COPY pom.xml .
COPY contracts-domain/pom.xml contracts-domain/
COPY contracts-ai/pom.xml contracts-ai/
COPY contracts-persistence/pom.xml contracts-persistence/
COPY contracts-app/pom.xml contracts-app/
COPY contracts-mcp-server/pom.xml contracts-mcp-server/
RUN mvn dependency:go-offline -pl contracts-app -am -B

COPY contracts-domain/src contracts-domain/src
COPY contracts-ai/src contracts-ai/src
COPY contracts-persistence/src contracts-persistence/src
COPY contracts-app/src contracts-app/src
RUN mvn clean package -pl contracts-app -am -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /workspace/contracts-app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

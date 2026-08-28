# Stage 1: Build React Frontend
FROM node:20-alpine AS frontend-builder
WORKDIR /app/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# Stage 2: Build Spring Boot Application
FROM eclipse-temurin:21-jdk-alpine AS backend-builder
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw
# Cache dependencies
RUN ./mvnw dependency:go-offline -B
COPY src ./src
# Copy static frontend assets from stage 1 into Spring Boot resources
COPY --from=frontend-builder /app/src/main/resources/static ./src/main/resources/static
RUN ./mvnw package -DskipTests -B

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as an unprivileged user. Nothing here needs root, and the default is only the
# default because it is easy -- a container that never writes outside /app has no reason
# to hold the one account that could.
RUN addgroup -S mailsentinel && adduser -S -G mailsentinel mailsentinel
COPY --from=backend-builder --chown=mailsentinel:mailsentinel /app/target/mailsentinel-*.jar app.jar
USER mailsentinel

# The app binds ${PORT:8080} (see application.properties), so a platform that assigns a
# port by injecting PORT is honoured; 8080 stays the default for a plain `docker run`.
EXPOSE 8080
ENV PORT=8080

ENTRYPOINT ["java", "-jar", "app.jar"]

# Build stage
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src

# Grant execute permission for gradlew
RUN chmod +x gradlew

# Build the application
RUN ./gradlew build -x test

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the built jar file from the build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# Run the application
# Render's free plan kills the process above 512MB in total, not just heap, so every JVM memory area is capped:
# heap ~256MB + metaspace 128MB + code cache 48MB + direct buffers 48MB + thread stacks stays under the limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxDirectMemorySize=48m", "-Xss512k", "-XX:+UseSerialGC", "-jar", "app.jar"]

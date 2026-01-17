# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build with production profile
COPY src ./src
RUN mvn clean package -DskipTests -Pproduction

# Stage 2: Runtime image
FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.source="https://github.com/stormino/vixsrc-downloader-java"
LABEL org.opencontainers.image.description="VixSrc Video Downloader - Spring Boot + Vaadin"
LABEL org.opencontainers.image.licenses="MIT"

# Install ffmpeg and wget (for healthcheck)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ffmpeg \
    wget && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd -r vixsrc && useradd -r -g vixsrc vixsrc

WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Create downloads directory and set permissions
RUN mkdir -p /downloads /downloads/temp && \
    chown -R vixsrc:vixsrc /app /downloads

# Set environment variables
ENV DOWNLOAD_BASE_PATH=/downloads
ENV DOWNLOAD_TEMP_PATH=/downloads/temp
ENV SERVER_PORT=8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Switch to non-root user
USER vixsrc

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

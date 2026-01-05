FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (for caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -Pproduction

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

# Install ffmpeg
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ffmpeg && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Create downloads directory
RUN mkdir -p /downloads && \
    mkdir -p /downloads/temp

# Set environment variables
ENV DOWNLOAD_BASE_PATH=/downloads
ENV DOWNLOAD_TEMP_PATH=/downloads/temp
ENV SERVER_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

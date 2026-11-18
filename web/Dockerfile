# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir -p /data/uploads && chown -R app /data
COPY --from=build /src/target/web.jar /app/web.jar
USER app
ENV UPLOADS_DIR=/data/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/web.jar"]

FROM maven:3.9.12-eclipse-temurin-17 AS build
WORKDIR /build
COPY back/pom.xml pom.xml
COPY back/src src
COPY deploy/application-container.properties src/main/resources/application.properties
RUN mvn -B -Dmaven.test.skip=true package
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app app && mkdir -p /app/uploads && chown -R app:app /app
WORKDIR /app
COPY --from=build /build/target/back-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

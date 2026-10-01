FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /source
COPY services/gateway/pom.xml /source/services/gateway/pom.xml
RUN mvn -q -f services/gateway/pom.xml dependency:go-offline
COPY contracts/schemas /source/contracts/schemas
COPY policies/examples /source/policies/examples
COPY services/gateway/src /source/services/gateway/src
RUN mvn -q -f services/gateway/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir /app/runtime && chown 10001:10001 /app/runtime
COPY --from=build /source/services/gateway/target/gateway-0.1.0.jar /app/gateway.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/gateway.jar"]

FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src src
RUN mvn -B clean package
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/roomflow-0.2.0.jar app.jar
RUN mkdir -p /app/.local && chown -R 10001:10001 /app
USER 10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8091
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]

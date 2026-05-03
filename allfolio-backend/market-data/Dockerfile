FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/market-data-*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]

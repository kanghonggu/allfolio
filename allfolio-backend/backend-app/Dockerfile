FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/backend-app-*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]

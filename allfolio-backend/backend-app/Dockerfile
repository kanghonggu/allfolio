FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
WORKDIR /workspace/allfolio-backend
RUN chmod +x ./gradlew && ./gradlew :backend-app:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/allfolio-backend/backend-app/build/libs/backend-app-*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]

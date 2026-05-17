FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN mkdir -p /data
COPY build/libs/*.jar app.jar
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "-jar", "app.jar"]

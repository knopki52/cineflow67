FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
ENV TZ=Europe/Moscow
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Duser.timezone=Europe/Moscow -jar app.jar"]

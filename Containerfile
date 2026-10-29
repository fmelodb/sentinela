FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /w
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY db db
COPY src src
RUN mvn -B -q package -DskipTests

FROM docker.io/library/eclipse-temurin:25-jre
COPY --from=build /w/target/sentinela.jar /app/sentinela.jar
WORKDIR /
ENTRYPOINT ["java", "-XX:+UseZGC", "-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch", "-Duser.timezone=UTC", "-jar", "/app/sentinela.jar"]

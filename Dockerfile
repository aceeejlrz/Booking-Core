# syntax=docker/dockerfile:1

# Build stage: compile and package with the project's own Maven wrapper.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# The cache mount keeps downloaded dependencies between builds without baking them into a layer.
# Tests are skipped here: they need Docker themselves and run in CI, not while building the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package

# Run stage: a Java runtime and the jar, nothing else, as a non-root user.
FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home ticketdrop
WORKDIR /app
COPY --from=build /src/target/ticketdrop-*.jar /app/ticketdrop.jar
USER ticketdrop
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ticketdrop.jar"]

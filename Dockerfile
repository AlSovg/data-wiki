FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir /data && chown app /data
COPY --from=build /src/target/data-wiki-*.jar app.jar
USER app
ENV LUCENE_INDEX_DIR=/data/lucene-index
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

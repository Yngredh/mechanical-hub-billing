# syntax=docker/dockerfile:1.7
#
# Imagem do mechanical-hub-billing em dois estagios: o build compila e empacota
# com o Maven Wrapper (testes rodam no pipeline, antes deste build); o runtime
# leva so o JRE, o jar e o agente OpenTelemetry.

# ── Build ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Dependencias primeiro: so mudam quando o pom muda.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw

COPY src src
COPY otel otel

# `package` tambem copia o agente para target/otel (maven-dependency-plugin).
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -DskipTests \
 && cp target/mechanical-hub-billing-*.jar target/app.jar

# ── Runtime ───────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

WORKDIR /app

COPY --from=build /workspace/target/app.jar app.jar
COPY --from=build /workspace/target/otel/opentelemetry-javaagent.jar otel/opentelemetry-javaagent.jar
COPY --from=build /workspace/otel/agent.properties otel/agent.properties

# Agente OpenTelemetry (trace propagado nos message attributes do SQS) e heap
# proporcional ao limite de memoria do container.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel/opentelemetry-javaagent.jar -Dotel.javaagent.configuration-file=/app/otel/agent.properties -XX:MaxRAMPercentage=75"

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

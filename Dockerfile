# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build stage
FROM maven:3.10-eclipse-temurin-27 AS build
WORKDIR /src

COPY pom.xml ./
COPY core core
COPY cli cli
COPY web web

# A BuildKit cache mount keeps the Maven repository between builds, which is both
# simpler and more reliable than a separate dependency-resolution layer.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:27-jre

LABEL org.opencontainers.image.title="SPAN HomeKit bridge" \
      org.opencontainers.image.description="SPAN panel circuits and power in Apple Home, with a dashboard" \
      org.opencontainers.image.source="https://github.com/chelin1974/SpanHome" \
      org.opencontainers.image.licenses="MIT"

RUN useradd --system --uid 10001 --create-home --home-dir /app --shell /usr/sbin/nologin span \
 && mkdir -p /data /config \
 && chown span:span /data

WORKDIR /app
# The Spring Boot flavour: the Breaker Board dashboard on Tomcat, a REST API and actuator
# health with liveness and readiness groups. The plain jar (cli/target/span-home.jar) is what
# launchd and systemd run outside a container; in one, the health endpoints earn their keep.
COPY --from=build --chown=span:span /src/web/target/span-home-web.jar ./span-home-web.jar

USER 10001

# Everything the bridge writes lives here and MUST outlive the container:
#
#   span-home.state.json        HomeKit pairing identity and paired controllers' keys
#   span-home.credentials.json  the panel token earned by pressing the panel's door button
#   span-home.accessories.json  stable HomeKit accessory IDs
#   span-home.layout.json       the HomeKit configuration number
#
# These are resolved relative to the directory holding the config file, so the config file
# itself belongs in here too - see deploy/k8s/deployment.yaml, which copies it in. Losing
# the volume means re-pairing in the Home app AND re-registering with the panel.
VOLUME ["/data"]

# 8089 is the dashboard. 51828 is the HomeKit accessory port.
#
# Neither is the whole story: HomeKit discovery is mDNS multicast on 224.0.0.251:5353,
# which a container on a bridge/overlay network cannot send or receive, and the bridge also
# talks MQTT to the panels on the local network. Run this with the host's network
# (`--network host`, or hostNetwork in Kubernetes). See deploy/k8s/README.md.
EXPOSE 8089/tcp 51828/tcp

# No shell, so the JVM is PID 1 and gets SIGTERM directly.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/span-home-web.jar"]
CMD ["--config", "/data/span-home.config.json"]

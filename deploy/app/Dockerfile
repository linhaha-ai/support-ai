FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS builder

WORKDIR /workspace

COPY backend ./backend

RUN mvn -B -ntp -f backend/pom.xml \
    -pl support-ai-bootstrap -am \
    -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555

RUN addgroup -S -g 10001 supportai \
    && adduser -S -D -H -u 10001 -G supportai supportai \
    && mkdir -p /opt/support-ai /var/lib/support-ai/storage \
    && chown -R supportai:supportai /opt/support-ai /var/lib/support-ai

WORKDIR /opt/support-ai

COPY --from=builder --chown=supportai:supportai \
    /workspace/backend/support-ai-bootstrap/target/support-ai-bootstrap-1.0.0-SNAPSHOT.jar \
    /opt/support-ai/support-ai.jar

USER supportai

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/support-ai/support-ai.jar"]

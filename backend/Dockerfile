# ── build ──
# 로컬과 동일한 Gradle 버전을 쓰기 위해 이미지의 gradle이 아니라 래퍼를 쓴다.
# (래퍼 9.2 vs 이미지 8.11 불일치는 "로컬은 되는데 도커에선 안 되는"의 전형적 원인)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY settings.gradle build.gradle ./
# 의존성 레이어 캐싱 — 소스만 바뀌면 재다운로드하지 않는다
RUN ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ── runtime ──
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

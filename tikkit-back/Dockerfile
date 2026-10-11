# syntax=docker/dockerfile:1

# ── 1단계: 빌드 + layered jar 추출 ──────────────────────────────────────────
# build.gradle의 toolchain(21)과 같은 JDK를 써서 Gradle이 툴체인을 따로 내려받지 않게 한다.
# gradle:8.14.4-jdk21 이미지를 안 쓴 이유: Gradle 버전 선언이 gradle-wrapper.properties와
# Dockerfile 두 곳으로 늘어난다. CI도 래퍼를 쓰므로 진실은 한 곳이어야 한다.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /builder

# 의존성 해석에 필요한 파일만 먼저 복사한다. src가 바뀌어도 아래 의존성 레이어는 재사용된다.
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
# git index는 100755지만 Windows 작업 트리에는 실행 권한 개념이 없어 방어적으로 넣는다.
RUN chmod +x gradlew
# Gradle 배포본과 전체 의존성을 미리 받아 레이어로 굳힌다.
# `dependencies` 태스크는 트리를 출력하려고 실제로 configuration을 해석하므로 캐시 워밍에 쓸 수 있다.
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# -x test: 테스트는 CI의 backend 잡이 책임진다. 이미지 빌드에서 두 번 돌릴 이유가 없다.
RUN ./gradlew --no-daemon bootJar -x test

# `jar` 태스크가 만드는 -plain.jar(의존성 없는 jar)을 피한다. CI(ci.yml)와 같은 방식이다.
RUN cp "$(ls build/libs/*.jar | grep -v plain | head -n 1)" app.jar

# Boot 4의 jarmode는 'layertools'가 아니라 'tools extract'다 (3.3에서 교체됨).
#   --layers                 layers.idx의 레이어별 디렉터리로 나눠 추출
#   --application-filename   기본값이 uber jar 파일명(TIKKIT-0.0.1-SNAPSHOT.jar)이라
#                            ENTRYPOINT가 버전 문자열에 묶이는 것을 막는다
# --launcher를 주지 않는다: 안 주면 Main-Class가 Start-Class로 치환되고 Class-Path가
# lib/*.jar를 가리키는 thin jar가 생성된다 → JarLauncher 없이 `java -jar`로 바로 뜬다.
RUN java -Djarmode=tools -jar app.jar extract \
        --layers \
        --application-filename application.jar \
        --destination extracted

# ── 2단계: 런타임 ──────────────────────────────────────────────────────────
# jre-alpine: JDK 도구가 없어 공격면이 작고, busybox wget이 있어서 compose healthcheck에
# 패키지를 추가할 필요가 없다 (이 이미지에 curl은 없다).
# musl 위험이 낮은 근거: jar/class만 복사하고 네이티브 라이브러리를 쓰지 않는다.
# ZoneId.of("Asia/Seoul")도 OS tzdata가 아니라 JDK 번들 tzdb.dat을 읽으므로 안전하다.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /application

RUN addgroup -S spring && adduser -S spring -G spring

# 변경 빈도가 낮은 레이어부터 복사한다. dependencies가 가장 안 변하고 application이 가장 자주 변한다.
# spring-boot-loader/ 와 snapshot-dependencies/ 는 이 구성에서 빈 디렉터리다
# (--launcher 미사용 + 스냅샷 의존성 0건). COPY는 실패하지 않으며, 구성이 바뀌어도
# Dockerfile을 다시 안 고치려고 4줄을 그대로 둔다.
COPY --from=builder --chown=spring:spring /builder/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/application/ ./

USER spring
EXPOSE 8080

# exec 형식을 유지해 PID 1이 JVM이 되게 한다 (SIGTERM이 그대로 전달된다).
# JVM 옵션은 compose에서 JAVA_TOOL_OPTIONS로 주면 JVM이 알아서 읽는다 —
# sh -c로 감싸 $JAVA_OPTS를 펼치는 방식은 신호 전달이 깨져서 쓰지 않는다.
ENTRYPOINT ["java", "-jar", "application.jar"]

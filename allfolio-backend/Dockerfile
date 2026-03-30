FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# bootJar 결과물 복사
COPY backend-app/build/libs/*.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", \
  # GC: ZGC — 저지연, Java 21 권장
  "-XX:+UseZGC", \
  "-XX:MaxGCPauseMillis=50", \
  # 힙: 컨테이너 메모리 75% 자동 사용
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  # Virtual Thread (Java 21) — Tomcat 스레드 대신 활용 가능
  "-Djdk.tracePinnedThreads=short", \
  # JIT 워밍업 가속
  "-XX:+TieredCompilation", \
  # 컨테이너 CPU 인식
  "-XX:+UseContainerSupport", \
  # 진단
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
  "-jar", "app.jar" \
]

# :server の実行イメージ。ビルドは CI (gradle :server:installDist) で済ませ、
# ここでは配布物を載せるだけにする (docker build をリポジトリルートで実行する):
#   ./gradlew :server:installDist
#   docker build -f server/Dockerfile -t adaptive-pulse-server .
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY server/build/install/server/ /app/

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]

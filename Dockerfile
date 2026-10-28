# 多阶段构建：前端产物必须先进 jar，再打包后端
FROM node:20-alpine AS frontend
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
# vite 的 outDir 指向 ../src/main/resources/static
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY pom.xml ./
# 先拉依赖，源码变动时可复用这层缓存
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
# curl 供 healthcheck 和排障使用
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=backend /build/target/kudu-console.jar /app/builtin.jar
RUN mkdir -p /app/config /app/logs /app/release
EXPOSE 8088
ENV JAVA_OPTS="-Xms256m -Xmx1g"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8088/ >/dev/null || exit 1
# /app/release/kudu-console.jar 由宿主机挂载进来自定义版本；
# 没有（首次部署或 release 目录为空）就用镜像内置的 builtin.jar，不会因为缺文件起不来
ENTRYPOINT ["sh", "-c", "JAR=/app/release/kudu-console.jar; [ -f \"$JAR\" ] || JAR=/app/builtin.jar; exec java $JAVA_OPTS -jar \"$JAR\""]

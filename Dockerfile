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

# 按 Spring Boot 的分层规则拆包：依赖 35MB、业务代码 0.5MB 分开成两层。
# 这决定了 docker pull 的增量大小——只改业务代码时，只需下载最上面那层。
FROM eclipse-temurin:17-jre AS layers
WORKDIR /extract
COPY --from=backend /build/target/kudu-console.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract \
 && mkdir -p dependencies spring-boot-loader snapshot-dependencies application

FROM eclipse-temurin:17-jre
WORKDIR /app
# curl 供 healthcheck 和排障使用
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /app/config /app/logs /app/release
EXPOSE 8088
ENV JAVA_OPTS="-Xms256m -Xmx1g"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8088/ >/dev/null || exit 1

# 顺序按变更频率从低到高。前四层命中缓存时不会重新下载，
# 改后端或前端只影响最后的 application 层（约 1MB）
COPY --from=layers /extract/dependencies/ ./
COPY --from=layers /extract/spring-boot-loader/ ./
COPY --from=layers /extract/snapshot-dependencies/ ./
COPY --from=layers /extract/application/ ./

# 两种启动方式：
#   /app/release/kudu-console.jar 存在 → 跑挂载进来的 jar（源码构建模式，配 release/ 目录）
#   不存在 → 跑镜像内置的分层应用（镜像模式）
ENTRYPOINT ["sh", "-c", "if [ -f /app/release/kudu-console.jar ]; then exec java $JAVA_OPTS -jar /app/release/kudu-console.jar; else exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher; fi"]

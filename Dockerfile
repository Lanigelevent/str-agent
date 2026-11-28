# D15 T9: str-agent Dockerfile（多阶段构建）
#
# 构建产物用 application 插件的 installDist（bin/ + lib/ 含全部运行时依赖）。
# 早期版本用 `:host:jar` + 手动拼 /app/lib，但普通 jar 不含 kotlinx/duckdb 等依赖，
# 容器启动会 NoClassDefFoundError；且 `--offline` 在空缓存镜像里必然失败。
#
# 构建阶段用 **gradle:8.10.2-jdk17** 而不是 ./gradlew：官方镜像自带同版本发行包，
# 省掉 wrapper 首次下载 130MB（实测在容器里只有约 1MB/min，构建变成几十分钟）。
# Gradle 版本必须与 gradle/wrapper/gradle-wrapper.properties 的 8.10.2 保持一致。
#
# T-057 / D17 G-20：基础镜像按 manifest-list digest 固定。tag 只是可读注释，
# 真正生效的是 @sha256；上游 tag 漂移不会悄悄换掉构建环境。
# 两个 digest 用两个独立镜像代理（docker.1ms.run / docker.m.daocloud.io）
# `buildx imagetools inspect` 交叉核对一致；升级需同时改 digest 并重建验证。
FROM gradle:8.10.2-jdk17@sha256:c2900027f3f0681c2cbfb09d527813851ad67aeafbb409997297efa2df20e748 AS builder

WORKDIR /build
COPY . .
RUN gradle --no-daemon :host:installDist

# Stage 2: JRE 17 运行时（最小镜像；同样按 digest 固定）
FROM eclipse-temurin:17-jre-jammy@sha256:c35794d1348777e6bc310b9b7f420098ef8f37e988199549b545fded79a895a2

RUN useradd --system --uid 10001 --create-home stragent
WORKDIR /app
COPY --from=builder /build/host/build/install/str-agent-mcp/ /app/

ENV STRAGENT_HOME=/data
# 监听地址默认 127.0.0.1（安全默认值）；容器内要经端口映射访问时，
# 必须显式 `-e STRAGENT_HTTP_BIND=0.0.0.0` **且** `-e STRAGENT_OAUTH_ISSUER=<IdP 元数据 URL>`
# （T-146 起远程绑定未配 issuer 拒绝启动——无过期/无 scope 的静态 token 不能用于远程暴露）。
RUN mkdir -p /data && chown -R stragent:stragent /app /data
USER stragent

EXPOSE 7787

ENTRYPOINT ["/app/bin/str-agent-mcp"]
CMD ["serve"]

# ============================================================
# 应用镜像
#
# 注意：激活哪个 profile 不能写死在 ENTRYPOINT 里。
#   Spring Boot 配置优先级：命令行参数 > 环境变量 > application.yml
#   之前写成 ENTRYPOINT ["java","-jar","app.jar","--spring.profiles.active=docker"]
#   结果 compose 里设置的 SPRING_PROFILES_ACTIVE=cloud 被命令行参数覆盖，
#   容器一直拿 application-docker.yml 里的 host.docker.internal:6380 连 Redis → 启动失败。
#   现在用 ENV 给默认值，由 compose 的 environment 覆盖。
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/seckill-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

# 容器内默认 profile（compose 可通过环境变量覆盖）
ENV SPRING_PROFILES_ACTIVE=cloud
ENV TZ=Asia/Shanghai

ENTRYPOINT ["java", "-jar", "app.jar"]

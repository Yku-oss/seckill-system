# 基础镜像（你填：JRE 还是 JDK？哪个版本？）
FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制 jar（提示：你打的 jar 叫什么名字？）
COPY target/seckill-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=docker"]
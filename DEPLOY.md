# 部署手册（云服务器）

> 目标：把项目跑在公网服务器上，得到一个能直接打开、能真实下单的地址。
> 本项目已在本地用同一套编排完整验证通过（HTTP 200、下单成功、一人一单生效、对账正常）。

---

## 0. 前置条件

| 项目 | 要求 |
|---|---|
| 服务器 | 2 核 2G 起，Ubuntu 22.04 / 24.04 LTS |
| 安全组放行 | **只开 22（SSH）和 80（HTTP）** |
| 本地 | 已安装 JDK 21 + Maven + Node.js |

> ⚠️ 不要开放 3306 / 6379 / 5672 / 15672。这几个端口一旦暴露公网，通常几小时内就会被扫描并植入挖矿程序。它们在 Docker 内网通信，公网不需要。

实测内存占用（空闲态）：

```
app 391MB + mysql 371MB + rabbitmq 173MB + nginx 15MB + redis 6MB ≈ 956MB
```

加上系统本身约 1.2G，2G 机器可用，余量约 700MB。

---

## 1. 服务器初始化（只需一次）

SSH 登录后执行：

```bash
# 安装 Docker（官方脚本，国内可用）
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun

# 配置镜像加速（否则拉镜像会很慢或失败）
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com"
  ]
}
EOF
systemctl restart docker

# 验证
docker version && docker compose version
```

---

## 2. 本地打包

在项目根目录（Git Bash）执行：

```bash
# ① 打 jar（必须，Dockerfile 要 COPY 它）
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" mvn clean package -Dmaven.test.skip=true

# ② 构建前端（必须，nginx 直接挂载 frontend/dist）
cd frontend && npm run build && cd ..

# ③ 打包上传（排除依赖与无关文件）
tar --exclude='./frontend/node_modules' \
    --exclude='./.git' \
    --exclude='./target/*.original' \
    -czf /tmp/seckill-deploy.tar.gz .

ls -lh /tmp/seckill-deploy.tar.gz   # 约 60MB（主要是 jar）
```

---

## 3. 上传并启动

```bash
# 上传
scp /tmp/seckill-deploy.tar.gz root@你的服务器IP:/root/

# 解压 + 启动
ssh root@你的服务器IP
mkdir -p /opt/seckill && tar xzf /root/seckill-deploy.tar.gz -C /opt/seckill
cd /opt/seckill

# ⚠️ Windows 打包的文件是 CRLF 换行，Linux 上的 nginx 会解析失败
#    这一步不能省，否则容器起不来还看不出原因
find . -type f \( -name "*.conf" -o -name "*.yml" -o -name "Dockerfile" \) -exec sed -i 's/\r$//' {} \;

docker compose -f docker-compose.prod.yml up -d --build
```

首次启动会拉取镜像（MySQL 600MB、RabbitMQ 180MB 等），约 3–8 分钟。

观察进度：

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

看到这行就是成功了：

```
Started SeckillApplication in 7.064 seconds
【库存预热】seckill:stock:1 = 100
【对账】商品 1 通过：stock=100 redis=100 occupied=0 initial=100
```

---

## 4. 验证清单

```bash
# 首页
curl -I http://你的服务器IP                     # 期望 200

# 接口（验证反向代理 + 数据库）
curl http://你的服务器IP/api/seckill/goods      # 期望返回商品 JSON

# 前端 history 路由（验证 try_files）
curl -I http://你的服务器IP/goods/1             # 期望 200，不是 404
```

浏览器打开 `http://你的服务器IP`，应看到商品列表；点「立即抢购」能返回订单号。

---

## 5. 常用运维命令

```bash
cd /opt/seckill

# 查看状态
docker compose -f docker-compose.prod.yml ps

# 看应用日志（只看对账）
docker compose -f docker-compose.prod.yml logs app | grep 对账

# 重启应用
docker compose -f docker-compose.prod.yml restart app

# 更新代码后重新部署
tar xzf /root/seckill-deploy.tar.gz -C /opt/seckill
docker compose -f docker-compose.prod.yml up -d --build app

# 彻底重来（连数据库一起清空，慎用）
docker compose -f docker-compose.prod.yml down -v
```

---

## 6. 排错对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| 浏览器打不开，curl 也超时 | 安全组没开 80 | 控制台放行 80 端口 |
| 页面能开，接口 502 | app 没起来 | `logs app` 看异常，多半是 jar 没上传或 MySQL 没连上 |
| 接口报 `Unable to connect to Redis 127.0.0.1:6380` | profile 没切到 cloud | 确认 Dockerfile 没有硬编码 `--spring.profiles.active`，compose 里 `SPRING_PROFILES_ACTIVE=cloud` |
| 点抢购没反应，控制台 connection refused | 前端打的是 localhost | 确认 `frontend/src/api.js` 的 `BASE = '/api/seckill'`，且已重新 `npm run build` |
| 刷新 `/goods/1` 报 404 | nginx 缺 try_files | 检查 `nginx.conf` 的 `try_files $uri $uri/ /index.html` |
| app 容器反复重启 | 内存不够，被 OOM Killer 杀 | `dmesg \| tail` 确认；调小 `JAVA_TOOL_OPTIONS` 的 `-Xmx` |
| MySQL 一直 unhealthy | 数据卷初始化失败 | `logs mysql` 查看；首次建卷才会执行 `sql/init.sql` |

---

## 7. 关键配置说明（面试会问）

**为什么 MySQL/Redis/RabbitMQ 不映射端口？**
它们只在 compose 自定义网络内互通，用「服务名」互相访问（`mysql:3306`）。映射到宿主机等于把它们暴露在公网，是不必要的攻击面。

**为什么 app 只绑 `127.0.0.1:8081`？**
所有外部流量都走 nginx(80)，app 端口不对外。以后要上 HTTPS，只需要nginx 加证书，app 完全不用动。

**为什么 profile 用环境变量而不是命令行参数？**
Spring Boot 配置优先级：命令行参数 > 环境变量 > application.yml。写死在 ENTRYPOINT 里会覆盖 compose 的设置，导致容器永远连本地开发环境的地址。

**为什么 JVM 要限制 `-Xmx320m`？**
容器不设置堆上限时，JVM 会按宿主机内存估算（2G 机器可能取 512MB+），一旦超过 cgroup 限制就会被 OOM Killer 直接杀掉，表现为容器反复重启。

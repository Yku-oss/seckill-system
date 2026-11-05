package com.example.seckill.common;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端（分布式锁用）。
 *
 * ⚠️ 地址必须从 spring.data.redis.* 读取，不能写死。
 *    写死 127.0.0.1:6380 在本机能跑，但一进容器就会连到「容器自己」的 6380，
 *    那里什么都没有 → 启动直接失败：Unable to connect to Redis server: 127.0.0.1/127.0.0.1:6380
 *
 *    改从配置读之后：
 *      本地     application.yml          → localhost:6380（宿主机映射端口）
 *      云服务器  application-cloud.yml     → redis:6379（compose 服务名 + 容器内端口）
 */
@Configuration
public class Redissonconfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                // 2 核 2G 小机上默认连接池（64/24）太浪费，收小一些
                .setConnectionPoolSize(16)
                .setConnectionMinimumIdleSize(4)
                .setConnectTimeout(5000)
                .setTimeout(3000);

        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }

        return Redisson.create(config);
    }
}

package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.service.SeckillService;
import jakarta.annotation.Resource;
import jakarta.annotation.Resources;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/mylock")
public class MyLockController {

    @Autowired
    private StringRedisTemplate redis;  // ② 注入Redis

    private final String FIX_LOCK = "seckill:lock:";
    private final long hoding_time = 3;
    private final long bussyness_time = 5000;

    // ③ 定义常量：锁的key（字符串）、过期时间（3秒）、业务时间（5000毫秒）
    //    你自己写三个 private static final
    private AtomicInteger chiyousuo =  new AtomicInteger(0);

    // ④ 定义Lua解锁脚本（DefaultRedisScript）
    //    提示：看 Redis知识总结.md 分布式锁部分，Lua脚本就那两行
    private final DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"
            ,Long.class
    );

    @GetMapping("/fix")
    public String fix() throws InterruptedException {
        // ⑤ 生成 uuid
        //    提示：UUID.randomUUID().toString()
        String uuid = UUID.randomUUID().toString();
        // ⑥ 加锁：setIfAbsent(key, uuid, 过期时间, TimeUnit.SECONDS)
        //    返回 Boolean，false 说明没抢到 → return "抢锁失败"
        boolean lock = redis.opsForValue().setIfAbsent(FIX_LOCK, uuid,hoding_time,TimeUnit.SECONDS);
        if( !lock){
            return  "抢锁失败";
        }

       try {
           Thread.sleep(bussyness_time);
       }finally {
            redis.execute(redisScript,Collections.singletonList(FIX_LOCK),uuid);
       }

        // ⑨ 返回结果字符串
        return "完成";
    }

    @Resource
    private RedissonClient redissonClient;

    @GetMapping("/redisson")
    public  String redisson() throws InterruptedException {
        RLock lock = redissonClient.getLock(FIX_LOCK+"redisson");
        boolean tryLock = lock.tryLock();
        if(!tryLock){
            return "获取提升锁失败";
        }
        try {
            Thread.sleep(bussyness_time);
        }finally {
            lock.unlock();
        }
        return "完成";
    }



}
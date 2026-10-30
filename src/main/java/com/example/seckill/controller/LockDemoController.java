package com.example.seckill.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式锁误删复现实验
 *
 * bug 版：解锁直接 DEL，不校验是不是自己的锁 → 会误删别人的锁
 * fix 版：解锁用 Lua 校验 uuid → 不会误删
 *
 * 锁过期 3 秒，业务执行 5 秒（故意超过过期时间）
 */
@RestController
@RequestMapping("/api/lock")
public class LockDemoController {

    @Autowired
    private StringRedisTemplate redis;

    private static final long LOCK_EXPIRE = 3;
    private static final long BUSINESS_TIME = 5000;

    // ===== BUG 版 =====
    private static final String BUG_LOCK_KEY = "demo:lock:bug";
    private final AtomicInteger bugHolding = new AtomicInteger(0);
    private final AtomicInteger bugViolation = new AtomicInteger(0);


    @GetMapping("/bug")
    public String bug() throws InterruptedException {
        Boolean locked = redis.opsForValue()
                .setIfAbsent(BUG_LOCK_KEY, "1", LOCK_EXPIRE, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            return "抢锁失败";
        }

        int holding = bugHolding.incrementAndGet();
        if (holding > 1) {
            bugViolation.incrementAndGet();
        }

        Thread.sleep(BUSINESS_TIME);

        bugHolding.decrementAndGet();

        redis.delete(BUG_LOCK_KEY);

        return String.format("BUG版完成 | 同时持有=%d | 互斥破坏次数=%d", holding, bugViolation.get());
    }

    // ===== FIX 版 =====
    private static final String FIX_LOCK_KEY = "demo:lock:fix";
    private final AtomicInteger fixHolding = new AtomicInteger(0);
    private final AtomicInteger fixViolation = new AtomicInteger(0);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    @GetMapping("/fix")
    public String fix() throws InterruptedException {
        String uuid = UUID.randomUUID().toString();

        Boolean locked = redis.opsForValue()
                .setIfAbsent(FIX_LOCK_KEY, uuid, LOCK_EXPIRE, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            return "抢锁失败";
        }

        int holding = fixHolding.incrementAndGet();
        if (holding > 1) {
            fixViolation.incrementAndGet();
        }

        Thread.sleep(BUSINESS_TIME);

        fixHolding.decrementAndGet();

        Long result = redis.execute(UNLOCK_SCRIPT, Collections.singletonList(FIX_LOCK_KEY), uuid);

        return String.format("FIX版完成 | 同时持有=%d | 互斥破坏次数=%d | 解锁结果=%d(1=删了自己的 0=锁不是自己的没删)",
                holding, fixViolation.get(), result);
    }

    @GetMapping("/reset")
    public String reset() {
        bugHolding.set(0);
        bugViolation.set(0);
        fixHolding.set(0);
        fixViolation.set(0);
        redis.delete(BUG_LOCK_KEY);
        redis.delete(FIX_LOCK_KEY);
        return "已重置";
    }
}
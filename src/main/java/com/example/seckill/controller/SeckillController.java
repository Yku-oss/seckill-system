package com.example.seckill.controller;

import org.springframework.web.bind.annotation.*;

import com.example.seckill.common.Result;
import com.example.seckill.service.SeckillService;


/**
 * 秒杀接口 — 模块1
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /**
     * 秒杀下单
     * POST /api/seckill/do?userId=1&goodsId=1
     */
    @PostMapping("/do")
    public Result<?> doSeckill(@RequestParam Long userId, @RequestParam Long goodsId) {
        return seckillService.doSeckill(userId, goodsId);
    }

    @PostMapping("/pay")
    public Result<?> paySeckill(@RequestParam String orderNo) {
        return seckillService.payOrder(orderNo);
    }

    @PostMapping("/redis")
    public Result<?> doSeckillByRedis(@RequestParam Long userId, @RequestParam Long goodsId) {
        return seckillService.doSeckillByRedis(userId, goodsId);
    }

    /**
     * 【对照实验】去掉 @Transactional 的版本
     * POST /api/seckill/redis/v2?userId=1&goodsId=1
     * 和 /redis 跑同样的逻辑，只差「事务范围」这一处，用来压测对比
     */
    @PostMapping("/redis/v2")
    public Result<?> doSeckillByRedisV2(@RequestParam Long userId, @RequestParam Long goodsId) {
        return seckillService.doSeckillByRedisV2(userId, goodsId);
    }

    // MQ 测试接口：发一条消息到队列，验证消费者是否收到
    @PostMapping("/mq/test")
    public Result<?> mqTest(@RequestParam String msg) {
        seckillService.sendOrderMessage(msg);
        return Result.success("消息已发送: " + msg);
    }

    @GetMapping("/goods")
    public Result<?> listGoods(){
        return seckillService.selectAllGoods();
    }

    @GetMapping("/order")
    public Result<?> queryOrder(@RequestParam String orderNo){
        return seckillService.queryOrderByNo(orderNo);
    }
    @GetMapping("/goods/{id}")                      // 填路径（带 {id}）
    public Result<?> getGoodsById(@PathVariable("id") Long id) {   // 填注解
        return seckillService.selectGoodsById(id);   // 调 Service
    }
}

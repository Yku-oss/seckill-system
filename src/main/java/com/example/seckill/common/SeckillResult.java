package com.example.seckill.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 秒杀下单返回体
 *
 * 为什么不用"拼接字符串"？
 *   ❌ return Result.success("抢购成功，订单号：" + orderNo);
 *      前端只能靠正则从字符串里抠订单号，改一个标点就崩。
 *   ✅ 返回结构化对象，字段分开，前端直接取 data.orderNo。
 */
@Data
@AllArgsConstructor
public class SeckillResult {
    /** 订单号 */
    private String orderNo;
    /** 提示信息 */
    private String message;
}

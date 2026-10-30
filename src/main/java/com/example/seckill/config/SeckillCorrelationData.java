package com.example.seckill.config;

import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * 带"完整面单"的 CorrelationData
 *
 * ========== 为什么需要它 ==========
 * confirm 回调（broker 的送达回执）只还给你三样东西：
 *     ack      —— 收到没有
 *     cause    —— 没收到的话为什么
 *     CorrelationData —— 你发送时自己塞的那张便条
 *
 * 而 CorrelationData 默认【只有一个 id 字段】，没有 body、没有 exchange、没有 routingKey。
 * 这不是 Spring 偷懒 —— AMQP 协议里 publish 没有同步应答，
 * 确认帧(basic.ack)里本来就不带这些信息。
 *
 * 后果：broker 回 nack 时，你在回调里【无法重建这条消息】，
 *      既不知道该往哪重投（缺 routingKey），也没有内容可重投（缺 body），
 *      消息已经不在队列里了 → 只能人工追溯 → 等于丢单。
 *
 * ========== 解决思路 ==========
 * 回调里拿不到的东西，必须在【发送时】就塞进便条。
 * 继承 CorrelationData，把这三样带上：
 *     payload    —— 消息本体，如 "8888:1:abc123"
 *     exchange   —— 发往哪个交换机（"" 表示默认交换机）
 *     routingKey —— 走哪条路由（本项目里等于队列名）
 *
 * 这样 MqConfirmCallback 拿到 nack 时，可以直接把这条记录写进 mq_fail_log，
 * 由 MqFailRetryTask 原样重投回 MQ —— 闭环成立。
 */
public class SeckillCorrelationData extends CorrelationData {

    /** 消息本体（原始字符串），重投时直接拿它当消息发出去 */
    private final String payload;

    /** 发往的交换机；空字符串 = 默认交换机 */
    private final String exchange;

    /** 路由键；默认交换机下等于队列名 */
    private final String routingKey;

    public SeckillCorrelationData(String id, String payload, String exchange, String routingKey) {
        super(id);   // id 仍然保留：日志和 mq_fail_log.correlation_id 靠它定位
        this.payload = payload;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}

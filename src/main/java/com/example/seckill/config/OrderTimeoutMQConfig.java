package com.example.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单超时自动取消 —— MQ 配置（延迟队列方案）
 *
 * ========== 这个类是干啥的 ==========
 *   用户下单后 30 分钟未支付 → 自动取消订单 + 回补库存
 *
 * ========== 核心思路：TTL + 死信队列 = 延迟队列 ==========
 *   消息不是"没消费者就一直留着"吗？对，我们就利用这一点：
 *   让它在一个【没有消费者】的队列里躺 30 分钟，躺到 TTL 过期，
 *   自动变成死信，被路由到真正干活的队列。
 *
 * ========== 消息流转 ==========
 *
 *   生产者（下单成功时）
 *     → 发到 seckill.delay.exchange，routingKey = delay.routingkey
 *   seckill.order.delay.queue（延迟队列，TTL=30min，【无消费者】）
 *     → 躺满 30 分钟，消息过期 → 变死信
 *   seckill.dlx（死信交换机，【复用 RabbitMQConfig 里的那个】）
 *     → routingKey = timeout.routingkey
 *   seckill.order.timeout.queue（处理队列，【有消费者】）
 *     →
 *   OrderTimeoutConsumer：查订单 → 未支付则取消 + 回补库存
 *
 * ========== 复用规则（关键） ==========
 *   ✅ seckill.dlx 复用 RabbitMQConfig 的：交换机是"分拣中心"，天生公用
 *   ❌ 队列不复用：每个队列是一个终点，用途不同必须分开
 *      · seckill.order.dlq           = 消费失败的消息（RabbitMQConfig）
 *      · seckill.order.timeout.queue = 超时过期的消息（本类）
 *
 * ========== 为什么单独一个类？ ==========
 *   "订单削峰 + 失败死信"（RabbitMQConfig）和"超时自动取消"（本类）
 *   是两条独立的业务链路，分开更清晰，避免一个配置文件越堆越乱。
 */
@Configuration
public class OrderTimeoutMQConfig {

    // ==================== 队列名 ====================

    /** 延迟队列：消息在这里躺 30 分钟等过期，【没有消费者】 */
    public static final String SECKILL_ORDER_DELAY_QUEUE = "seckill.order.delay.queue";

    /** 超时处理队列：收到过期消息，【有消费者】负责取消订单 */
    public static final String SECKILL_ORDER_TIMEOUT_QUEUE = "seckill.order.timeout.queue";

    // ==================== 交换机名 ====================

    /**
     * 延迟消息入口交换机
     * 为什么不能复用 seckill.dlx？
     *   因为入口不同：生产者发延迟消息时，不能发到"死信交换机"（语义错乱）
     */
    public static final String SECKILL_DELAY_EXCHANGE = "seckill.delay.exchange";

    // ==================== routingKey ====================

    /** 延迟队列入口的路由键（生产者用） */
    public static final String SECKILL_ORDER_DELAY_ROUTING_KEY = "seckill.order.delay.routingkey";

    /** 超时死信的路由键（延迟队列过期后，路由到处理队列用） */
    public static final String SECKILL_ORDER_TIMEOUT_ROUTING_KEY = "seckill.order.timeout.routingkey";

    /** 订单超时时间：30 分钟（毫秒） */
    public static final int ORDER_TIMEOUT_MS = 30 * 60 * 1000;

    // ==================== 交换机 ====================

    /**
     * 延迟消息入口交换机（DirectExchange）
     * 生产者把延迟消息发到这里，routingKey = delay.routingkey
     */
    @Bean
    public DirectExchange seckillDelayExchange() {
        return new DirectExchange(SECKILL_DELAY_EXCHANGE, true, false);
    }

    // ==================== 队列 ====================

    /**
     * ⭐ 延迟队列（定时队列）—— 本方案的核心
     *
     * 三个关键参数：
     *   1. x-message-ttl             = 1800000ms（30分钟），消息躺多久过期
     *   2. x-dead-letter-exchange    = seckill.dlx（过期后去死信交换机，【复用】）
     *   3. x-dead-letter-routing-key = timeout.routingkey（用这个地址路由到处理队列）
     *
     * ⚠️ 这个队列【没有消费者】！它就是用来"搁置消息 30 分钟"的
     */
    @Bean
    public Queue seckillOrderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", ORDER_TIMEOUT_MS);
        args.put("x-dead-letter-exchange", RabbitMQConfig.SECKILL_DLX);
        args.put("x-dead-letter-routing-key", SECKILL_ORDER_TIMEOUT_ROUTING_KEY);
        return new Queue(SECKILL_ORDER_DELAY_QUEUE, true, false, false, args);
    }

    /**
     * 超时处理队列 —— 过期消息的终点，【有消费者】
     * 普通队列，不需要死信参数（它不做定时，只负责处理）
     */
    @Bean
    public Queue seckillOrderTimeoutQueue() {
        return new Queue(SECKILL_ORDER_TIMEOUT_QUEUE, true);
    }

    // ==================== 绑定 ====================
    // 绑定 = "交换机收到 routingKey 为 X 的消息，就投给哪个队列"

    /**
     * ⭐ 延迟队列 → 延迟交换机
     * 生产者发消息到 seckill.delay.exchange，routingKey = delay.routingkey
     * → 路由进 seckill.order.delay.queue
     */
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(seckillOrderDelayQueue())
                .to(seckillDelayExchange())
                .with(SECKILL_ORDER_DELAY_ROUTING_KEY);
    }

    /**
     * ⭐ 超时处理队列 → seckill.dlx（【复用】RabbitMQConfig 里的死信交换机）
     *
     * 延迟队列过期后 → seckill.dlx，routingKey = timeout.routingkey
     * → 路由进 seckill.order.timeout.queue
     *
     * 对比 RabbitMQConfig.dlqBinding：同样是 seckill.dlx，但因为 routingKey 不同，
     * 消息被分发到不同队列 —— 这就是"交换机一对多"的实现方式
     *
     * 注意：seckillDlx 作为【方法参数】由 Spring 注入（容器里已有一个），
     *       不能在本类再 new 一个 —— 否则同一个交换机被声明两次会冲突
     */
    @Bean
    public Binding timeoutBinding(DirectExchange seckillDlx) {
        return BindingBuilder.bind(seckillOrderTimeoutQueue())
                .to(seckillDlx)
                .with(SECKILL_ORDER_TIMEOUT_ROUTING_KEY);
    }
}

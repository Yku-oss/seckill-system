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
 * RabbitMQ 配置类 —— 订单削峰 + 消费失败兜底
 *
 * ========== 本类负责的链路 ==========
 *
 *   生产者（秒杀下单成功）
 *     ↓ 直接发到队列 seckill.order.queue
 *   seckill.order.queue（业务队列，带死信参数）
 *     ├─ 消费成功 → 结束
 *     └─ 消费失败 → 重试 3 次（见 application.yml）→ 仍失败
 *          → seckill.dlx（死信交换机，【本类声明，被多个类复用】）
 *          → routingKey = dlq.routingkey
 *          → seckill.order.dlq（失败死信队列，收尸）
 *
 * ========== 另一个类负责的链路 ==========
 *   订单超时自动取消（延迟队列方案）→ 见 {@link OrderTimeoutMQConfig}
 *     · 它也复用本类的 seckill.dlx，但用不同的 routingKey
 *       → routingKey = timeout.routingkey
 *       → seckill.order.timeout.queue
 *
 * ========== 关键词 ==========
 *   DLX = Dead Letter Exchange（死信交换机）
 *   DLQ = Dead Letter Queue（死信队列）
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 队列名 ====================

    /** 业务队列：正常下单（消费者异步落单） */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    /** 失败死信队列：消费失败的消息收尸 */
    public static final String SECKILL_DLQ = "seckill.order.dlq";

    // ==================== 交换机名 ====================

    /** 死信交换机（⚠️ 被 OrderTimeoutMQConfig 复用，是全局唯一的死信汇聚点） */
    public static final String SECKILL_DLX = "seckill.dlx";

    // ==================== routingKey ====================

    /** 失败死信的路由键（消费失败 → 死信队列） */
    public static final String SECKILL_DLQ_ROUTING_KEY = "seckill.order.dlq.routingkey";

    // ==================== 交换机 ====================

    /**
     * 死信交换机（DLX）—— 全局复用
     * DirectExchange：按 routingKey 精确匹配路由
     *
     * 两条链路都往这里送死信：
     *   · 消费失败  → routingKey = dlq.routingkey     → seckill.order.dlq
     *   · 超时过期  → routingKey = timeout.routingkey → seckill.order.timeout.queue
     */
    @Bean
    public DirectExchange seckillDlx() {
        return new DirectExchange(SECKILL_DLX, true, false);
    }

    // ==================== 队列 ====================

    /**
     * 失败死信队列（DLQ）—— 收尸的地方，普通队列无参数
     */
    @Bean
    public Queue seckillDlq() {
        return new Queue(SECKILL_DLQ, true);
    }

    /**
     * 业务队列（正常下单）—— 带死信参数
     *
     * x-dead-letter-exchange：消息变成死信后，投递到哪个交换机
     * x-dead-letter-routing-key：投递时用的 routingKey
     *
     * 当消费者重试耗尽、消息被 reject 时，RabbitMQ 自动把它
     * 转到 seckill.dlx，再路由到 seckill.order.dlq
     */
    @Bean
    public Queue seckillOrderQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", SECKILL_DLX);
        args.put("x-dead-letter-routing-key", SECKILL_DLQ_ROUTING_KEY);
        // 持久化（true）= RabbitMQ 重启后队列还在
        return new Queue(SECKILL_ORDER_QUEUE, true, false, false, args);
    }

    // ==================== 绑定 ====================
    // 绑定 = "交换机收到 routingKey 为 X 的消息，就投给哪个队列"

    /** 失败死信队列 → seckill.dlx */
    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(seckillDlq())
                .to(seckillDlx())
                .with(SECKILL_DLQ_ROUTING_KEY);
    }
}

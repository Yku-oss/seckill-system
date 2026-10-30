package com.example.seckill.config;

import com.example.seckill.entity.MqFailLog;
import com.example.seckill.mapper.MqFailLogMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 消息"退回"回调 —— 生产者可靠性第二层
 *
 * ========== 它和 MqConfirmCallback 的分工 ==========
 *
 *   MqConfirmCallback  → 管"消息到 broker 了吗"      （生产者 → Broker）
 *   MqReturnsCallback  → 管"消息路由到队列了吗"      （Broker → 队列）
 *
 *   ⚠️ 关键认知：confirm=true 只代表 "broker 收到了"，
 *      不代表 "消息进了队列"！
 *      如果交换机没找到匹配的队列（routingKey 写错 / 队列没绑定），
 *      confirm 依然回 true，只有 setReturnsCallback 才能感知到。
 *
 *   ★ 这就是两个回调【必须同时配】的原因：
 *     它们的失败场景【可能同时发生】，而且 confirm 在这种情况下是"瞎报喜"的。
 *
 * ========== 触发条件（三者之一） ==========
 *   ① 消息路由到【不存在的队列】（routingKey 无绑定）
 *   ② 队列不存在
 *   ③ 队列满了 / 被拒收
 *
 * ========== 前提：application.yml 必须开 ==========
 *   spring.rabbitmq.publisher-returns: true
 *   （配了它才生效；不配这个，回调永远不来）
 *
 * ========== 失败后的后果 ==========
 *   消息被 broker 退回，【没进任何队列】：
 *     · 队列里没有它     → 没有
 *     · 死信队列里也没有它 → 没有（DLQ 只管"已进队列但消费失败"的消息）
 *   同样只能靠 mq_fail_log 表兜底重投。
 */
@Component
public class MqReturnsCallback implements RabbitTemplate.ReturnsCallback {

    private final RabbitTemplate rabbitTemplate;
    private final MqFailLogMapper failLogMapper;

    public MqReturnsCallback(RabbitTemplate rabbitTemplate, MqFailLogMapper failLogMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.failLogMapper = failLogMapper;
    }

    /** 同 MqConfirmCallback：用 @PostConstruct 保证登记时序确定 */
    @PostConstruct
    public void register() {
        rabbitTemplate.setReturnsCallback(this);
    }

    /**
     * ⚠️ 注意：ReturnsCallback 和 ConfirmCallback 的"方法签名不一样"！
     *   confirm   → confirm(CorrelationData, boolean ack, String cause)  3 个参数
     *   returns   → returnedMessage(ReturnedMessage returned)            1 个参数
     *
     * ReturnedMessage 里有 4 样东西：
     *   getExchange()     消息发去的交换机
     *   getRoutingKey()   消息用的 routingKey
     *   getReplyCode()    失败码，如 312 = NO_ROUTE
     *   getReplyText()    失败原因文字，如 "NO_ROUTE"
     *   getMessage()      消息本体（Message 对象，取 body 要 getBody()）
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        String body = new String(returned.getMessage().getBody());

        System.out.println("【returns】❌ 消息路由失败，没能进队列！");
        System.out.println("          exchange   = " + returned.getExchange());
        System.out.println("          routingKey = " + returned.getRoutingKey());
        System.out.println("          replyCode  = " + returned.getReplyCode());
        System.out.println("          replyText  = " + returned.getReplyText());
        System.out.println("          body       = " + body);

        // ========== 落库：消息已不在 RabbitMQ 里，必须自己存下来重投 ==========
        // 注意：returns 回调【不带 CorrelationData】，
        //       所以 correlationId 只能留空（这是 RabbitMQ 协议层面的限制）
        MqFailLog failLog = new MqFailLog();
        failLog.setExchangeName(returned.getExchange());
        failLog.setRoutingKey(returned.getRoutingKey());
        failLog.setMessageBody(body);
        failLog.setFailType(MqFailLog.TYPE_RETURN);
        failLog.setFailReason(returned.getReplyCode() + " " + returned.getReplyText());
        failLog.setRetryCount(0);
        failLog.setStatus(MqFailLog.STATUS_PENDING);

        try {
            failLogMapper.insert(failLog);
            System.out.println("【returns】已写入 mq_fail_log（id=" + failLog.getId() + "），待重投");
        } catch (Exception e) {
            // 运行在 AMQP 线程上，绝不能让它抛出去
            System.out.println("【returns】❌❌ 失败消息落库也失败了！需人工介入！" +
                    " routingKey=" + returned.getRoutingKey() + ", body=" + body +
                    ", 原因=" + e.getMessage());
        }

        // ⚠️ 生产环境还应该在这里做：
        //   ① 打 ERROR 日志 + 接告警（钉钉/邮件/Sentry）
        //   ② 定时任务扫描 mq_fail_log 表，自动重投
        //   ③ 重投仍失败 → 人工确认后处理
    }
}

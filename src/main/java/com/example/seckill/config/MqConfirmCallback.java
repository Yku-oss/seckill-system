package com.example.seckill.config;

import com.example.seckill.entity.MqFailLog;
import com.example.seckill.mapper.MqFailLogMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 生产者可靠性第一层 —— "消息到 broker 了吗"
 *
 * ========== 它负责什么 ==========
 *   ack = true  → broker 收到了（持久化消息还意味着已落盘）
 *   ack = false → broker 根本没收到（broker 宕机 / 连接断 / 内存告警）
 *
 * ========== ⚠️ 关键认知：ack=true 不代表消息进了队列！ ==========
 *   如果 routingKey 写错 / 队列没绑定，broker 依然会回 ack=true，
 *   但消息其实无处可去 —— 那种情况由 MqReturnsCallback 负责感知。
 *   所以这两个回调【不是二选一，要同时配】，它们覆盖不同的失败位置。
 *
 * ========== 失败后的后果（和 DLQ 的本质区别） ==========
 *   confirm=false 时，消息【压根没进 broker】：
 *     · 队列里没有它 → 没有
 *     · 死信队列里也没有它 → 没有
 *   死信队列(DLQ)完全救不了这种情况，只能自己写进 mq_fail_log 表再重投。
 *   这就是本类从"只打日志"升级成"落库"的原因。
 */
@Component
public class MqConfirmCallback implements RabbitTemplate.ConfirmCallback {

    private final RabbitTemplate rabbitTemplate;
    private final MqFailLogMapper failLogMapper;

    public MqConfirmCallback(RabbitTemplate rabbitTemplate, MqFailLogMapper failLogMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.failLogMapper = failLogMapper;
    }

    /**
     * 用 @PostConstruct 而不是在构造器里 setConfirmCallback：
     *
     * 原因：RabbitTemplate 是【单例】，谁先拿到它谁就决定了回调有没有装上。
     *       如果在构造器里设置，就依赖"Spring 恰好先创建本类"这个偶然顺序 ——
     *       万一别的 Bean 抢先注入了 RabbitTemplate 并发了消息，回调就漏装了。
     *       @PostConstruct 在所有依赖注入完成后执行，时序是确定的。
     */
    @PostConstruct
    public void register() {
        rabbitTemplate.setConfirmCallback(this);
    }

    @Override
    public void confirm(@Nullable CorrelationData correlationData, boolean ack, @Nullable String cause) {
        // ⚠️ correlationData 可能为 null（框架内部消息不带），必须判空
        String id = correlationData != null ? correlationData.getId() : "无ID";

        if (ack) {
            System.out.println("【confirm】broker 已收到，id=" + id);
            return;
        }

        // ========== broker 没收到 → 消息丢了，必须自己兜底 ==========
        System.out.println("【confirm】❌ broker 未收到！id=" + id + "，原因=" + cause);

        // ========== 取回"面单"：body / exchange / routingKey ==========
        // CorrelationData 默认只带 id，拿不到消息内容 —— confirm 帧里只有 ack 和 cause。
        // 所以发送方改用 SeckillCorrelationData，把这三样一起带上；
        // 万一拿到的是裸 CorrelationData（旧代码或框架内部消息），只能记 id 靠人工追溯。
        String body;
        String exchange = null;
        String routingKey = null;

        if (correlationData instanceof SeckillCorrelationData scd) {
            // ✅ 正常路径：发送时就把面单塞进 CorrelationData 了
            body = scd.getPayload();
            exchange = scd.getExchange();
            routingKey = scd.getRoutingKey();
        } else if (correlationData != null && correlationData.getReturned() != null) {
            // 特殊情况：这条消息同时被 return 过（先 return 后 confirm），
            // Spring 会把退回的消息挂到 CorrelationData 上，这时能拿到真实内容
            ReturnedMessage returned = correlationData.getReturned();
            body = new String(returned.getMessage().getBody());
            exchange = returned.getExchange();
            routingKey = returned.getRoutingKey();
        } else {
            // 裸 CorrelationData（或为 null）：协议层不提供任何可重建的信息。
            // 这种记录就算写进表里也重投不了，只能靠 id 人工追溯 —— 属于设计缺陷，应当避免。
            body = "【消息本体不可得，仅记录 correlationId】" + id;
            System.out.println("【confirm】❌❌ 消息无法重建（发送时未携带面单），只能人工追溯，id=" + id);
        }

        MqFailLog failLog = new MqFailLog();
        failLog.setCorrelationId(id);
        failLog.setExchangeName(exchange);
        failLog.setRoutingKey(routingKey);
        failLog.setMessageBody(body);
        failLog.setFailType(MqFailLog.TYPE_CONFIRM_NACK);
        failLog.setFailReason(cause);
        failLog.setRetryCount(0);
        failLog.setStatus(MqFailLog.STATUS_PENDING);

        saveFailLog(failLog, id);
    }

    /**
     * 落库保存失败消息。
     *
     * ⚠️ 必须 try-catch！
     *    这个方法运行在 AMQP 的【连接线程】上，抛异常会污染连接、甚至导致
     *    回调链断裂。而且此时 DB 也可能正好是挂的那个 —— 双重故障时不
     *    能让回调自己再炸一次。
     *    降级策略：落库失败 → 至少把完整信息打到日志（ERROR 级别），人工可捞。
     */
    private void saveFailLog(MqFailLog failLog, String id) {
        try {
            failLogMapper.insert(failLog);
            System.out.println("【confirm】已写入 mq_fail_log，待重投，id=" + id);
        } catch (Exception e) {
            // 双重故障：broker 没收到的同时 DB 也写不进去
            System.out.println("【confirm】❌❌ 失败消息落库也失败了！需人工介入！" +
                    " id=" + id + ", body=" + failLog.getMessageBody() + ", 原因=" + e
                    .getMessage());
        }
    }
}

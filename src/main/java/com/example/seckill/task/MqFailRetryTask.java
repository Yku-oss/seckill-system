package com.example.seckill.task;

import com.example.seckill.config.SeckillCorrelationData;
import com.example.seckill.entity.MqFailLog;
import com.example.seckill.mapper.MqFailLogMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MQ 失败消息重投任务 —— 补上「mq_fail_log 表」的最后一环
 *
 * ========== 为什么需要它？ ==========
 *   MqConfirmCallback / MqReturnsCallback 只做了一半：
 *     ✅ 把失败消息【写进 mq_fail_log 表】
 *     ❌ 但没人【重新投递】→ 消息永远躺在表里 → 可靠性链路断在这里
 *
 *   本类就是那个"翻笔记本重投"的人。
 *
 * ========== 它在四道传送带里的位置 ==========
 *   ① 生产者 → Exchange  ─┐
 *   ② Exchange → Queue   ─┤ 掉货 → 存 mq_fail_log → 【本类重投】→ 回到 ①
 *   ③ Queue → 磁盘        │ 掉货 → 框架自己兜（durable）
 *   ④ Queue → 消费者      ┘ 掉货 → 框架自己兜（重试 + DLQ）
 *
 * ========== 安全性设计 ==========
 *   1. 单条失败不影响其他消息（try-catch 包住每条）
 *   2. 整个任务抛异常不能让调度线程挂掉（最外层再包一层）
 *   3. 有最大重投次数，防止"永远投不成功"的消息无限循环（毒消息）
 *   4. 单批限量，避免一次捞出几万条把内存/连接打爆
 */
@Component
public class MqFailRetryTask {

    private final MqFailLogMapper failLogMapper;
    private final RabbitTemplate rabbitTemplate;

    /** 最多重投几次，超过就置为"放弃"（status=2），等人工处理 */
    private static final int MAX_RETRY = 3;

    /** 一次最多捞出多少条重投（防止一次拉爆） */
    private static final int BATCH_SIZE = 100;

    public MqFailRetryTask(MqFailLogMapper failLogMapper, RabbitTemplate rabbitTemplate) {
        this.failLogMapper = failLogMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 每 60 秒扫一次 mq_fail_log，把 status=0（待重投）的消息重新发出去。
     *
     * fixedDelay：上一次【执行完】之后，再等 60 秒才执行下一次。
     *   —— 用 fixedDelay 而不是 fixedRate，避免上一轮没跑完下一轮又压上来。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retryFailedMessages() {
        List<MqFailLog> pendingList;
        try {
            pendingList = failLogMapper.selectPending(MAX_RETRY, BATCH_SIZE);
        } catch (Exception e) {
            // DB 挂了：本轮放弃，等下一轮。绝不能让异常把调度线程干掉
            System.out.println("【重投任务】查询待重投消息失败：" + e.getMessage());
            return;
        }

        if (pendingList == null || pendingList.isEmpty()) {
            return;   // 没有待重投的，静默返回（不要刷日志）
        }

        System.out.println("【重投任务】本轮待重投 " + pendingList.size() + " 条");

        for (MqFailLog log : pendingList) {
            try {
                doRetry(log);
            } catch (Exception e) {
                // 单条重投失败：记录，继续处理下一条
                System.out.println("【重投任务】重投失败，id=" + log.getId() + "，原因=" + e.getMessage());
                safeMarkRetryFailed(log.getId());
            }
        }
    }

    /**
     * 重投单条消息。
     *
     * 面单三要素：exchange + routingKey + body —— 缺 routingKey 就无法定位队列，只能转人工。
     *
     * 注意：convertAndSend 返回 void，返回时只代表"字节写进了 socket"。
     *   所以这里的"成功"准确说是"已重投"，broker 是否收下要看下一次 confirm 回调。
     *   后续消费失败会走原有的重试 + 死信链路，那时归 DLQ 管。
     */
    private void doRetry(MqFailLog log) {
        // 逻辑是 :  先进行判断路由是否为空，因为在丢掉的过程中信息遗失
        // 如果 为空 就得进行人工补偿，记录id，然后将其放入表中进行持久化，不为空就进行下一部分
        // 依旧是将其拆分，进行重新发送,发送完后再进行回馈，方便判断
        String routingKey = log.getRoutingKey();
        Object messageBody = log.getMessageBody();
        String exchangeName = log.getExchangeName();
        // 创建一个对象，一起发送，防止第二次重试的时候丢掉信息
        SeckillCorrelationData cd = new SeckillCorrelationData(
                log.getCorrelationId(), log.getMessageBody(), exchangeName, routingKey);


        if (routingKey == null || routingKey.isBlank()) {
            System.out.println("【重投任务】id=" + log.getId() + " 缺 routingKey，无法重投，转人工");
            safeMarkRetryFailed(log.getId());
            return;
        }
        // 注意的是交换机是空的或者说是无字符的都是为默认
        if (exchangeName == null || exchangeName.isBlank()) {
            // 默认交换机：routingKey 就是队列名
            rabbitTemplate.convertAndSend(routingKey, messageBody,cd);
        } else {
            // 指定交换机（如延迟交换机）：必须带上 exchange，
            // 否则默认交换机上没有那条 routingKey 对应的队列，重投必然路由不到
            rabbitTemplate.convertAndSend(exchangeName, routingKey, messageBody,cd);
        }

        failLogMapper.markSuccess(log.getId());

        System.out.println("【重投任务】✅ 已重投，id=" + log.getId()
                + "，交换机=" + (exchangeName == null || exchangeName.isBlank() ? "默认" : exchangeName)
                + "，路由=" + routingKey + "，信息=" + messageBody);
    }

    /** 标记重投失败（再包一层 try-catch，防止 DB 又挂了把循环打断） */
    private void safeMarkRetryFailed(Long id) {
        try {
            failLogMapper.markRetryFailed(id, MqFailRetryTask.MAX_RETRY);
        } catch (Exception e) {
            System.out.println("【重投任务】标记失败状态也失败了，id=" + id + "，原因=" + e.getMessage());
        }
    }
}

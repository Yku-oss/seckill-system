package com.example.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ 失败消息记录 —— 生产者侧兜底
 *
 * ========== 为什么需要这张表？ ==========
 * 前面讲过：MQ 的失败有四个位置，RabbitMQ 只兜住了其中一个。
 *
 *   · 消息已进队列但消费失败 → broker 自动进死信队列(DLQ)，✅ 框架兜了
 *   · 消息没进队列（routingKey 错 / 队列没绑定 / broker 没收到）
 *     → 消息【既不在队列里、也不在 DLQ 里】，框架完全管不到
 *     → 只能自己存进 MySQL 这张表，再想办法重投  ← 本表存在的唯一理由
 *
 * ========== 状态流转 ==========
 *   status = 0 待重投  → 定时任务扫描 → 重新 convertAndSend
 *   status = 1 重投成功
 *   status = 2 放弃    → retry_count 超过上限，需要人工介入 / 告警
 */
@Data
public class MqFailLog {

    /** 主键 */
    private Long id;

    /** 消息身份证：CorrelationData.getId()，用来定位是哪条消息 */
    private String correlationId;

    /** 消息发往的交换机（returns 回调才有值；confirm 失败时可能为 null） */
    private String exchangeName;

    /** 消息用的 routingKey（在我们的代码里就等于队列名） */
    private String routingKey;

    /** 消息本体（原始字符串，如 "8888:1:abc123"） */
    private String messageBody;

    /** 失败类型：CONFIRM_NACK（broker 没收到） / RETURN（没路由进队列） */
    private String failType;

    /** 失败原因：confirm 的 cause / returns 的 replyText */
    private String failReason;

    /** 已重投次数，超过上限就置为放弃 */
    private Integer retryCount;

    /** 0待重投 1重投成功 2放弃 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ==================== 状态常量（避免魔法数字） ====================

    /** 待重投 */
    public static final int STATUS_PENDING = 0;
    /** 重投成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 放弃（超过重投上限，需人工处理） */
    public static final int STATUS_GIVE_UP = 2;

    // ==================== 失败类型常量 ====================

    /** broker 没收到（confirm 回 ack=false） */
    public static final String TYPE_CONFIRM_NACK = "CONFIRM_NACK";
    /** 进了 broker 但没路由到队列（returns 触发） */
    public static final String TYPE_RETURN = "RETURN";
}

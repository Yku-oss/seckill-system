package com.example.seckill.consumer;

import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillOrderMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单消费者 —— MQ 削峰的核心
 *
 * 主流程（doSeckillByRedis）扣完库存后只发一条消息就返回，
 * 真正"落单"由这里异步完成：MQ 把洪峰排队，消费者按自己节奏慢慢落库，DB 不被瞬时打爆。
 */
@Component
public class OrderConsumer {

    private final SeckillOrderMapper orderMapper;

    @Value("${seckill.mq.fail-test:false}")
    private boolean failTest;


    public OrderConsumer(SeckillOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void consume(String message) {
        // 消息格式：userId:goodsId:orderNo
        String[] parts = message.split(":");
        Long userId = Long.valueOf(parts[0]);
        Long goodsId = Long.valueOf(parts[1]);
        String orderNo = parts[2];

        // 测试开关：故意失败，用于验证「重试 → 死信队列」链路
        // 必须放在落库【之前】，否则数据会脏（重试会重复插入）
        if (failTest) {
            throw new RuntimeException("【测试】故意失败，验证死信链路 " + orderNo);
        }

        // 重建订单并落库（异步）
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        order.setStatus(0);

        // ========== 幂等：重复投递必须"当成功"，不能当失败 ==========
        // 什么情况会重复投递？
        //   ① 消费者 insert 成功后、ack 之前进程被杀/网络断
        //      → broker 认为没消费成功 → 消息重新入队 → 又被消费一次
        //   ② 网络抖动导致 ack 丢失
        //
        // ⚠️ 不处理会怎样？
        //   insert 撞上 uk_order_no 唯一键 → 抛 DuplicateKeyException
        //   → 被 Spring AMQP 当成"消费失败" → 内存重试 3 次 → 3 次都撞唯一键
        //   → 消息进死信队列 seckill.order.dlq
        //   数据没错，但一条本该"静默跳过"的消息变成了死信告警，污染 DLQ。
        //
        // ✅ 正确处理：唯一键冲突 = "这条订单已经落过库了" = 本次消费【成功】
        //    直接 return → Spring 替你 basicAck → 消息正常删除，不进 DLQ
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 唯一键兜底挡住了重复插入 —— 这正是 uk_order_no 存在的意义
            System.out.println("【MQ异步落单】订单已存在，跳过（幂等）：orderNo=" + orderNo);
            return;   // ← 关键：return 而不是 throw，让框架 ack 掉这条消息
        }

        System.out.println("【MQ异步落单】orderNo=" + orderNo + ", userId=" + userId + ", goodsId=" + goodsId);
    }

}

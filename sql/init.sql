-- 秒杀系统初始化脚本
-- 模块1：库存 / 订单两张核心表

-- ⚠️ 强制客户端/连接使用 utf8mb4，避免中文乱码
-- （2026-09-29 踩坑：用 PowerShell 的 Get-Content | mysql 导入会把中文转成乱码）
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS seckill DEFAULT CHARACTER SET utf8mb4;
USE seckill;

-- ============================================================
-- 秒杀商品表
-- 设计决策（面试要讲得出"为什么"）：
--   1. version 字段 = 乐观锁预留：DB 兜底防超卖的第二层（条件更新 + 版本校验）
--   2. 库存用 INT，扣减用"条件更新"（stock > 0），见 SeckillGoodsMapper.deductStock
--   3. initial_stock = 期初库存，对账基准（不可省）
--      对账等式：initial_stock == stock + 已占用订单数(status in 0,1)
--      为什么必须持久化：stock 只会越扣越少，事后无法反推"原本是多少"；
--      也不能只存 Redis —— 缓存一丢基准就没了，对账永远算不准。
-- ============================================================
CREATE TABLE IF NOT EXISTS seckill_goods (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    goods_name    VARCHAR(64)  NOT NULL COMMENT '商品名称',
    stock         INT          NOT NULL DEFAULT 0 COMMENT '当前库存',
    initial_stock INT          NOT NULL DEFAULT 0 COMMENT '期初库存（对账基准，初始时等于 stock）',
    version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    start_time  DATETIME     NOT NULL COMMENT '秒杀开始时间',
    end_time    DATETIME     NOT NULL COMMENT '秒杀结束时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_seckill_time (start_time, end_time)
) ENGINE = InnoDB COMMENT = '秒杀商品表';

-- ============================================================
-- 秒杀订单表
-- 设计决策：
--   1. uk_user_goods (user_id, goods_id) 唯一键 = 数据库层面"一人一单"兜底
--      （即使业务层 Redis 预减/分布式锁全失效，DB 唯一键也能挡住重复下单）
--   2. order_no 唯一键 = 幂等（消息重试不会产生重复订单）
--   3. status：0待支付 1已支付 2已取消 3已关闭（超时自动关闭用）
-- ============================================================
CREATE TABLE IF NOT EXISTS seckill_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id     BIGINT      NOT NULL COMMENT '用户ID',
    goods_id    BIGINT      NOT NULL COMMENT '商品ID',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消 3已关闭',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time    DATETIME    DEFAULT NULL,
    close_time  DATETIME    DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_goods (user_id, goods_id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB COMMENT = '秒杀订单表';

-- ============================================================
-- MQ 失败消息表（生产者侧兜底）
-- 解决什么：confirm=false / returns 触发时，消息【已经不在队列里了】，
--           RabbitMQ 管不到，只能自己存下来再重投。
--
-- 三条链路写入本表：
--   1. confirm ack=false  → broker 没收到（broker 宕机 / 网络断）
--   2. returns 触发       → broker 收到了但没路由进队列（routingKey 错 / 队列没绑定）
--   3. （预留）消费重试耗尽 → 也可从 DLQ 转入本表统一重投
--
-- 与死信队列(DLQ)的区别：
--   DLQ   = 消息还在 RabbitMQ 里，broker 替你保管
--   本表  = 消息已经不在 RabbitMQ 里了，只能靠数据库保管（生产者侧兜底）
--
-- 状态流转：0待重投 → 1重投成功 → 2放弃（超过最大重投次数，需人工处理）
-- ============================================================
CREATE TABLE IF NOT EXISTS mq_fail_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    correlation_id VARCHAR(128) DEFAULT NULL COMMENT '消息身份证（CorrelationData.id），用于定位',
    exchange_name VARCHAR(128) DEFAULT NULL COMMENT '消息发往的交换机（returns 才有）',
    routing_key  VARCHAR(255) DEFAULT NULL COMMENT '消息用的 routingKey / 目标队列',
    message_body TEXT         DEFAULT NULL COMMENT '消息本体（原始字符串）',
    fail_type    VARCHAR(32)  NOT NULL COMMENT '失败类型：CONFIRM_NACK / RETURN',
    fail_reason  VARCHAR(512) DEFAULT NULL COMMENT '失败原因（cause / replyText）',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '已重投次数',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待重投 1重投成功 2放弃',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_retry (status, retry_count),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = 'MQ失败消息表（生产者侧兜底）';

-- 测试数据（秒杀窗口设为长期有效，方便开发调试）
-- initial_stock 与 stock 同值：这是"从未卖过"的起点
INSERT INTO seckill_goods (goods_name, stock, initial_stock, start_time, end_time)
VALUES ('测试商品A', 100, 100, '2026-08-02 00:00:00', '2026-12-31 23:59:59');





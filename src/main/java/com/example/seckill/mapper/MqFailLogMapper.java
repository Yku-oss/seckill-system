package com.example.seckill.mapper;

import com.example.seckill.entity.MqFailLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MQ 失败消息 Mapper —— 生产者侧兜底的读写入口
 *
 * 写入方：MqConfirmCallback（broker 没收到）、MqReturnsCallback（没路由进队列）
 * 读取方：重投任务（扫描 status=0 的记录，重新发一次）
 */
public interface MqFailLogMapper {

    /**
     * 记录一条失败消息。
     *
     * ⚠️ 这个 insert 自己也可能失败（比如 DB 挂了）。
     *    调用方必须 try-catch 兜住 —— 否则回调里抛异常会污染 AMQP 连接线程。
     *
     * useGeneratedKeys：把自增主键回填到实体，方便调用方打日志
     */
    @Insert("INSERT INTO mq_fail_log(correlation_id, exchange_name, routing_key, message_body, " +
            "fail_type, fail_reason, retry_count, status) " +
            "VALUES(#{correlationId}, #{exchangeName}, #{routingKey}, #{messageBody}, " +
            "#{failType}, #{failReason}, #{retryCount}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MqFailLog log);

    /**
     * 查出待重投的消息（status=0 且没超过重投上限）。
     * 按时间正序：先失败的先重投，保证消息顺序。
     */
    @Select("SELECT * FROM mq_fail_log WHERE status = 0 AND retry_count < #{maxRetry} " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<MqFailLog> selectPending(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /** 重投成功 → 状态置 1 */
    @Update("UPDATE mq_fail_log SET status = 1, retry_count = retry_count + 1 WHERE id = #{id}")
    int markSuccess(@Param("id") Long id);

    /**
     * 重投失败 → retry_count + 1；若已达上限则直接置为"放弃"(2)。
     * 用一条 SQL 完成，避免并发下重投次数算错。
     */
    @Update("UPDATE mq_fail_log SET retry_count = retry_count + 1, " +
            "status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 2 ELSE 0 END " +
            "WHERE id = #{id}")
    int markRetryFailed(@Param("id") Long id, @Param("maxRetry") int maxRetry);

    /** 统计待重投条数 —— 可接到监控告警上（数量持续上涨说明链路有问题） */
    @Select("SELECT COUNT(*) FROM mq_fail_log WHERE status = 0")
    int countPending();

    /** 按订单号/内容查失败记录，排查问题时用 */
    @Select("SELECT * FROM mq_fail_log WHERE message_body = #{messageBody} ORDER BY create_time DESC")
    List<MqFailLog> selectByMessageBody(@Param("messageBody") String messageBody);
}

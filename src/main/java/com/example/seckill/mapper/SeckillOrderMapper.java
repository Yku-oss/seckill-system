package com.example.seckill.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.example.seckill.entity.SeckillOrder;

/**
 * 秒杀订单 Mapper
 *
 * 设计要点：
 *   1. insert 依赖表上的 uk_user_goods 唯一键 —— 一人一单的数据库兜底
 *      （重复下单会抛 DuplicateKeyException，业务层捕获后返回"已抢过"）
 *   2. order_no 唯一键保证幂等 —— MQ 消息重试不会产生重复订单（模块3）
 */
public interface SeckillOrderMapper {

    @Insert("INSERT INTO seckill_order(order_no, user_id, goods_id, status) " +
            "VALUES(#{orderNo}, #{userId}, #{goodsId}, #{status})")
    int insert(SeckillOrder order);

    @Select("SELECT * FROM seckill_order WHERE user_id = #{userId} AND goods_id = #{goodsId}")
    SeckillOrder selectByUserAndGoods(@Param("userId") Long userId, @Param("goodsId") Long goodsId);

    @Update("UPDATE seckill_order SET status = 1 WHERE order_no = #{orderNo} AND status = 0")
    int makePaid(@Param("orderNo") String orderNo);

    @Update("UPDATE seckill_order SET status = 2 WHERE order_no = #{orderNo} AND status = 0")
    int cancel(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM seckill_order WHERE order_no = #{orderNo}")
    SeckillOrder selectByOrderNo(@Param("orderNo") String orderNo);

}

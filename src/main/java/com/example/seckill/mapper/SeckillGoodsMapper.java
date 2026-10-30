package com.example.seckill.mapper;

import com.example.seckill.entity.SeckillGoods;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import java.util.List;

/**
 * 秒杀商品 Mapper
 *
 * 模块1 核心：超卖问题（Oversell）
 *   经典 bug（check-then-act，先查后改）：
 *     SELECT stock ... → 判断 stock > 0 → UPDATE stock = stock - 1
 *     两个并发请求都读到 stock = 1，都通过判断，都 UPDATE → 库存变成 -1（超卖）
 *   解决（今天先写 DB 层兜底）：
 *     条件更新：UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0
 *     MySQL 行锁保证这条语句原子，天然防超卖 —— 这是最后一道防线
 *   （前面的防线：Redis 预减库存 + Lua 原子扣减，模块2 再做）
 */
public interface SeckillGoodsMapper {

    @Select("SELECT * FROM seckill_goods WHERE id = #{id}")
    SeckillGoods selectById(@Param("id") Long id);

    /**
     * DB 层兜底防超卖：库存足够才扣减。
     * 受影响行数为 0 → 库存不足，下单失败。
     *
     * 【方案说明】这是【悲观锁 / 原子条件更新】，不是乐观锁：
     *   UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0
     *   MySQL 对该行加写锁，并发的第二条 UPDATE 会阻塞等待，
     *   等第一条提交后再执行，此时 stock 已为 0 → WHERE 不匹配 → 返回 0。
     *   所以不需要 version 判断，也不会超卖。
     */
    @Update("UPDATE seckill_goods SET stock = stock - 1, version = version + 1 " +
            "WHERE id = #{id} AND stock > 0")
    int deductStock(@Param("id") Long id);

    /**
     * 乐观锁版本扣减（带版本校验，防 ABA 场景下的覆盖）：
     * 版本不匹配 → 返回 0，重试或失败。
     */
    @Update("UPDATE seckill_goods SET stock = stock - 1, version = version + 1 " +
            "WHERE id = #{id} AND stock > 0 AND version = #{version}")
    int deductStockByVersion(@Param("id") Long id, @Param("version") Integer version);

    /**
     * 【反面教材 / 保留用于演示超卖 bug】
     * 少了 AND stock > 0 兜底 → 并发下会扣成负数（超卖）。
     * 仅用于对比演示，业务代码禁止调用。
     */
    @Update("UPDATE seckill_goods SET stock = stock - 1, version = version + 1 " +
            "WHERE id = #{id}")
    int deductStockBug(@Param("id") Long id);

    @Update("UPDATE seckill_goods SET stock = stock + 1 WHERE id = #{id}")
    int addStock(@Param("id") Long id);

    @Select("select * from seckill_goods")
    List<SeckillGoods> selectAll();

}


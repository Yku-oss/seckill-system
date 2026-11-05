package com.example.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀商品
 */
@Data
public class SeckillGoods {

    private Long id; // 商品ID

    private String goodsName; // 商品名称

    /** 库存数量 */
    private Integer stock; 

    /**
     * 期初库存 —— 对账基准（不可省）
     *
     * 为什么必须持久化：stock 只会越扣越少，事后无法反推"原本是多少"；
     * 也不能只存 Redis —— 缓存一丢基准就没了，对账永远算不准。
     * 对账等式：initial_stock == stock + 已占用订单数(status in 0,1)
     */
    private Integer initialStock;

    /** 乐观锁版本号（DB 兜底防超卖第二层） */
    private Integer version; // 版本号

    private LocalDateTime startTime; // 开始时间

    private LocalDateTime endTime; // 结束时间

    private LocalDateTime createTime; // 创建时间

    private LocalDateTime updateTime;// 更新时间
}

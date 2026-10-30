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

    /** 乐观锁版本号（DB 兜底防超卖第二层） */
    private Integer version; // 版本号

    private LocalDateTime startTime; // 开始时间

    private LocalDateTime endTime; // 结束时间

    private LocalDateTime createTime; // 创建时间

    private LocalDateTime updateTime;// 更新时间
}

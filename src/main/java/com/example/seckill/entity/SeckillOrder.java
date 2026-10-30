package com.example.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀订单
 */
@Data
public class SeckillOrder {

    private Long id; // 订单编号

    /** 订单号（唯一，幂等键） */
    private String orderNo;

    private Long userId;

    private Long goodsId;

    /** 0待支付 1已支付 2已取消 3已关闭 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime closeTime;
}

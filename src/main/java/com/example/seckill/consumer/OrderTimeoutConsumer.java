package com.example.seckill.consumer;

import com.example.seckill.config.OrderTimeoutMQConfig;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.SeckillService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时消费者 —— 30 分钟后检查订单是否已支付
 *
 * ========== 它监听谁 ==========
 *   监听 seckill.order.timeout.queue（超时处理队列）
 *   消息能到这，说明已经在延迟队列里躺够 30 分钟，TTL 过期了
 *
 * ========== 它干什么 ==========
 *   收到消息后，查订单当前状态：
 *     · status = 0（待支付）→ 说明用户确实没付款 → 取消订单 + 回补库存
 *     · status = 1（已支付）→ 用户已经付了 → 什么都不做（千万不能取消！）
 *     · status = 2（已取消）→ 已经处理过了（幂等）→ 什么都不做
 *
 * ========== 为什么必须再查一次订单状态？ ==========
 *   因为消息是 30 分钟前发的，这 30 分钟里用户可能已经付款了！
 *   不能"消息一到就取消" —— 那会把已付款的订单也取消掉。
 *
 * ========== 幂等性 ==========
 *   MQ 可能重复投递（网络抖动、重试），所以：
 *   · cancel() 带 status=0 条件（已支付的改不了，天然幂等）
 *   · 只有 cancel 影响行数 > 0 时才回补库存（避免重复加库存）
 */
@Component
public class OrderTimeoutConsumer {
    // MQ的思路就是 ： 消息进来 ->  查询订单 -> 判断状态 -> 取消 -> 还库存

    private final SeckillOrderMapper seckillOrderMapper ;
    private final SeckillGoodsMapper seckillGoodsMapper ;
    private final SeckillService seckillService ;


    public OrderTimeoutConsumer(SeckillGoodsMapper seckillGoodsMapper, SeckillOrderMapper seckillOrderMapper,
                                SeckillService seckillService) {
        this.seckillGoodsMapper = seckillGoodsMapper;
        this.seckillOrderMapper = seckillOrderMapper;
        this.seckillService = seckillService;
    }

    @RabbitListener(queues = OrderTimeoutMQConfig.SECKILL_ORDER_TIMEOUT_QUEUE)
    public void consume(String message) {

        // ========== 第1步：解析消息 ==========
        // 消息格式："userId:goodsId:orderNo"，比如 "8888:1:abc123"
        // split(":") 按冒号切开 → ["8888", "1", "abc123"]
        String[] pair = message.split(":");

        // 校验：必须正好 3 段，否则说明消息格式不对（脏数据），直接丢弃
        if (pair.length != 3) {
            System.out.println("消息格式错误，丢弃：" + message);
            return;   // ← 格式错了没法处理，直接结束
        }
        // orderNo = 第 3 段（下标从 0 开始，所以是 pair[2]）
        String orderNo = pair[2];

        // ========== 第2步：查订单当前状态 ==========
        // ⚠️ 为什么要查？因为消息是 30 分钟前发的，这期间用户可能已经付款了！
        //    不能"消息一到就取消"，必须先看订单【现在】是什么状态
        SeckillOrder order = seckillOrderMapper.selectByOrderNo(orderNo);

        // 订单查不到 → 可能被清理了，没法处理，结束
        if (order == null) {
            System.out.println("订单不存在：" + orderNo);
            return;
        }

        // ========== 第3步：判断状态，决定"跳过"还是"继续" ==========
        // 订单状态：0=待支付  1=已支付  2=已取消
        // 思考方式：只有【待支付】才需要取消，其他都要跳过
        int status = order.getStatus();

        // 已支付 → 用户付过钱了，绝对不能取消！
        // 这叫"要跳过"→ 用 return 提前退出
        if (status == 1) {
            System.out.println("订单已支付，跳过：" + orderNo);
            return;
        }

        // 已取消 → 说明之前处理过了（消息重复投递），跳过保证幂等
        // 也是"要跳过"→ return
        if (status == 2) {
            System.out.println("订单已取消，跳过（幂等）：" + orderNo);
            return;
        }

        // ========== 第4步：取消订单 ==========
        // 能走到这里，说明上面两个 if 都没命中 → status 只能是 0（待支付）
        // 这就是"要做事"的分支，所以【不 return】，继续往下执行
        //
        // 【可靠性设计】用 try-catch 区分两种失败：
        //   ① 业务失败（rows == 0）→ 说明状态已变（用户付款了/幂等重复）→ 【正常】，不重试
        //   ② 系统失败（抛异常）  → DB 挂了/超时 → 【真失败】，抛出 → MQ 重试 → 仍失败进死信队列
        try {
            // cancel() 内部 SQL 带条件：WHERE order_no=? AND status=0
            //   返回 1 → 取消成功
            //   返回 0 → 说明这期间状态变了（比如刚支付），取消失败
            int rows = seckillOrderMapper.cancel(orderNo);

            if (rows == 0) {
                // 影响行数为 0 → 没取消成功 → 【不能回补库存】（否则库存会多出来）
                // 这是【业务上的正常情况】，不是错误 → 不抛异常，消费成功结束
                System.out.println("无需取消（状态已变更，如用户已付款）：" + orderNo);
                return;
            }

            // ========== 第5步：回补库存 ==========
            // 只有取消成功（rows > 0）才走到这里
            // 注意：库存是商品表的一个【字段】，不是一条记录
            //       所以用 addStock()（SET stock = stock + 1），不是 insert
            //       而且用"原子更新"，避免"先查后改"的并发问题
            seckillGoodsMapper.addStock(order.getGoodsId());

            // ⚠️ 【必须同时回补 Redis】
            // 下单时 Redis 先预减了库存，只回补 DB 会导致：
            //   Redis = 0（剩售罄）但 DB = 1 → 后续请求全被 Lua 拦下 → 少卖
            // 这也是"Redis 和 DB 双写一致"在取消路径上的体现
            seckillService.addStockToRedis(order.getGoodsId());

            System.out.println("✅ 订单超时已取消，库存已回补（DB + Redis）：orderNo=" + orderNo
                    + ", goodsId=" + order.getGoodsId());

        } catch (Exception e) {
            // 因为框架包含void，不能直接返回，所以我们需要将此异常包装成非受检异常
            // 【系统失败】：数据库连接失败、超时、SQL 报错等
            // → 抛出 RuntimeException，交给 Spring AMQP 的重试机制处理：
            //    重试 3 次（1s/2s/4s 指数退避）→ 仍失败 → 消息进死信队列 seckill.order.dlq
            //    这样"真失败"的消息不会丢，可人工/定时补偿
            throw new RuntimeException("取消订单系统异常，将触发 MQ 重试：orderNo=" + orderNo, e);
        }
    }


}

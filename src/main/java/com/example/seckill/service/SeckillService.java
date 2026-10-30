package com.example.seckill.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.example.seckill.config.OrderTimeoutMQConfig;
import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.config.SeckillCorrelationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seckill.common.Result;
import com.example.seckill.common.SeckillResult;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.mapper.SeckillOrderMapper;


/**
 * 秒杀服务 — 模块1（DB 层兜底版）
 *
 * 核心：超卖问题（Oversell）
 *   bug 版（check-then-act 先查后改）：SELECT stock → 判断 >0 → UPDATE
 *   两个并发请求都读到 stock=1、都通过判断、都 UPDATE → 库存变 -1（超卖）
 *
 *   本版（正确版）：UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0
 *   MySQL 行锁保证这条语句原子，天然防超卖 —— 这是最后一道防线
 *   （前面的防线：Redis 预减库存 + Lua，模块2 再做）
 */
@Service
@Slf4j
public class SeckillService {
    //声明类，声明引用的类与接口
    private final RabbitTemplate rabbitTemplate;
    private final SeckillGoodsMapper goodsMapper;
    private final SeckillOrderMapper orderMapper;
    private final StringRedisTemplate stringRedisTemplate;


    // ===== 限流参数（从 application.yml 读，压测时可临时调大）=====
    /** 令牌桶容量：桶里最多存多少个令牌 */
    @Value("${seckill.limit.capacity:10}")
    private long limitCapacity;

    /** 令牌补充速率：每秒补充多少个 */
    @Value("${seckill.limit.rate:5}")
    private long limitRate;

    //构造器，也就是将所引入的类或接口进行实例化
        public SeckillService(SeckillGoodsMapper goodsMapper, SeckillOrderMapper orderMapper,
                      StringRedisTemplate stringRedisTemplate, RabbitTemplate rabbitTemplate) {
    this.goodsMapper = goodsMapper;
    this.orderMapper = orderMapper;
    this.stringRedisTemplate = stringRedisTemplate;
    this.rabbitTemplate = rabbitTemplate;// ← 新加这行
}
    @Transactional
    public Result<?> doSeckill(Long userId, Long goodsId) {
        // 1. 查商品 + 校验秒杀时间窗
        SeckillGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            // 先查看库存是否存在，不存在就返回404，404在网络中是搜索不到，不存在。
            return Result.error(404, "商品不存在");
        }
        // LocalDateTime 是时间方法类，是java自带的方法包，记录当前时间
        LocalDateTime now = LocalDateTime.now();
        //查看当前下单的时候是否在秒杀时间内，如果不在就返回400，400是请求出错
        if (now.isBefore(goods.getStartTime()) || now.isAfter(goods.getEndTime())) {
            return Result.error(400, "不在秒杀时间窗口内");
        }

        // 2. 一人一单（业务层预检；DB 唯一键 uk_user_goods 是并发下的最终兜底）
        if (orderMapper.selectByUserAndGoods(userId, goodsId) != null) {
            return Result.error(400, "每人限购一件");
        }

        // 3. DB 条件扣减：库存足够才扣，行锁保证原子（防超卖核心）
        int rows = goodsMapper.deductStock(goodsId);
        if (rows == 0) {
            return Result.error(400, "库存不足");
        }

        // 4. 落订单（order_no 唯一键兜底幂等；并发重复下单会抛 DuplicateKeyException
        //    → 全局异常处理器兜底 → 事务回滚，包括第3步的扣库存）
        // 实现了MQ异步削峰，异步落单，先预减扣除DB，再进行落单
        // 但是MQ不能直接发送java对象，是生成了以一个UUID转变成String形式后，再发送给后面新new的订单对象中
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        String body = userId + ":" + goodsId + ":" + orderNo;
        // 带上"面单"：nack 时 confirm 回调靠它重建消息（body + 目标队列），否则只能转人工
        rabbitTemplate.convertAndSend(
                "",
                RabbitMQConfig.SECKILL_ORDER_QUEUE,
                body,
                new SeckillCorrelationData(orderNo + ":order", body,
                        "", RabbitMQConfig.SECKILL_ORDER_QUEUE));
        // 落单交给 MQ 消费者异步完成，这里不再同步 insert（否则会重复落单、订单号还不一致）

        return Result.success(new SeckillResult(orderNo, "抢购成功，订单处理中"));

    }

        // ① Lua 脚本（常量，放字段下面）
    //作用是先查看当前商品的库存，然后通过判断库存的多少来返回订单状态，比如库存不足 0，已支付 1，商品不存在-1
    // 这段原子代码的主要作用是档流量的
private static final String STOCK_LUA =
    "local stock = tonumber(redis.call('GET', KEYS[1])) " +
    "if stock == nil then return -1 end " +
    "if stock < tonumber(ARGV[1]) then return 0 end " +
    "redis.call('DECRBY', KEYS[1], ARGV[1]) " +
    "return 1";

    // ④ 库存回补 Lua 脚本（取消/超时订单时用）
    // 逻辑：key 存在 → 库存 +1；key 不存在 → 什么都不做（返回 -1）
    // 为什么加 EXISTS 判断？防止给「未预热 / key 已过期」的商品凭空造出库存
    private static final String ADD_STOCK_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end " +
            "return redis.call('INCR', KEYS[1])";

    /* ============================================================
     * ③ 令牌桶限流 Lua 脚本
     * ============================================================
     *
     * 【它是干什么的】
     *   挡住瞬间涌入的请求，保护后面的 Redis 库存和 MySQL 不被流量打爆。
     *   它是整个秒杀链路【最外层】的闸门。
     *
     * 【一句话原理】
     *   桶里装令牌，每个请求要拿走 1 个令牌才能过关；
     *   桶空了就返回 0（拒绝）；令牌按固定速率往回补。
     *
     * 【为什么用 Lua 而不用 Java 代码】
     *   因为"取令牌"这件事是【读-算-写】三步：
     *     读 tokens → 算补多少 → 写回 tokens
     *   如果用 Java 写，两个线程可能同时读到 tokens=1，
     *   都判断"够用"，都减 1 → 变成 -1（和超卖一模一样的并发 bug）。
     *   Redis 执行 Lua 是【单线程原子】的，中途不会被打断，天然解决这个问题。
     *
     * 【变量速查】（面试问"每个变量是什么"时直接背这段）
     *   key       = KEYS[1]                → Redis Hash 的键名，如 "seckill:limit:all"
     *   capacity  = ARGV[1]                → 桶的最大容量（最多攒几个令牌），如 10
     *   rate      = ARGV[2]                → 每秒补几个令牌，如 5
     *   time      = redis.call('TIME')     → Redis 服务器当前时间，返回 {秒, 微秒}
     *   now       = time[1]                → 只取"秒"（精度到秒，够用）
     *   bucket    = HMGET 的结果          → {tokens的值, last_refill的值}
     *   tokens    = bucket[1]              → 桶里现在有几个令牌
     *   last      = bucket[2]              → 上次补充令牌的时间（秒）
     *   elapsed   = now - last             → 距上次补充过了多少秒
     *
     * 【Redis 里存的数据长什么样】
     *   HSET seckill:limit:all tokens 7 last_refill 1760668800
     *   → 用 Hash 一次取两个值，少一次网络往返
     *
     * 【返回值】
     *   1 = 拿到令牌，放行
     *   0 = 桶空了，拒绝（Controller 转成 429）
     * ============================================================ */
    private static final String TOKEN_BUCKET_LUA =
            // ---------- 第 1 步：取参数 ----------
            // 这三个都是 Java 侧 tryTokenBucket(key, capacity, rate) 传进来的
            "local key = KEYS[1] " +                        // Hash 键名，如 "seckill:limit:all"
                    "local capacity = tonumber(ARGV[1]) " +   // 桶容量：最多攒几个令牌（如 10）
                    "local rate = tonumber(ARGV[2]) " +       // 补充速率：每秒补几个（如 5）

                    // ---------- 第 2 步：取当前时间 ----------
                    // 为什么用 Redis 的 TIME 而不是 Lua 的 os.time()？
                    //   因为 Redis 是有状态的，用 Redis 服务器的时间才能保证
                    //   多个节点/多次调用之间"补令牌"的基准一致
                    "local time = redis.call('TIME') " +       // 返回 {秒, 微秒}，如 {"1760668800", "123456"}
                    "local now = tonumber(time[1]) " +          // 只取秒数：1760668800

                    // ---------- 第 3 步：从 Hash 里取出桶的当前状态 ----------
                    // HMGET key 'tokens' 'last_refill' → 一次取两个字段，返回数组
                    "local bucket = redis.call('HMGET', key, 'tokens', 'last_refill') " +
                    "local tokens = tonumber(bucket[1]) " +     // 桶里现有令牌数（首次为 nil）
                    "local last = tonumber(bucket[2]) " +       // 上次补充时间（首次为 nil）

                    // ---------- 第 4 步：处理"第一次访问"（懒初始化）----------
                    // 第一次请求时 Redis 里还没有这个 Hash，HMGET 返回 {nil, nil}
                    // 不判空的话：elapsed = now - nil 直接报错
                    "if tokens == nil then tokens = capacity end " +   // 首次：桶是满的（capacity 个）
                    "if last == nil then last = now end " +            // 首次：上次补充时间 = 现在

                    // ---------- 第 5 步：按流逝的时间补令牌 ----------
                    // 例：capacity=10, rate=5, 过了 2 秒, 原来有 3 个
                    //     tokens = min(10, 3 + 2*5) = min(10, 13) = 10（封顶）
                    // math.min(capacity, ...) 是"封顶"：袋子最多装 capacity 个，
                    // 不会因为闲置很久就攒出 100 个（这就是"桶容量"的意义：限制突发上限）
                    "local elapsed = now - last " +                                   // 距上次补充过了几秒
                    "tokens = math.min(capacity, tokens + (elapsed * rate)) " +        // 补令牌，但不超过容量

                    // ---------- 第 6 步：尝试取 1 个令牌 ----------
                    "if tokens >= 1 then " +
                    "  tokens = tokens - 1 " +                                        // 拿走 1 个
                    // ⚠️ 注意：last_refill 必须更新成 now（不是 last！）
                    //   因为刚才已经按 elapsed 补过令牌了，下次要【从现在开始】重新算，
                    //   否则会把同一段时间重复补两次
                    "  redis.call('HSET', key, 'tokens', tokens, 'last_refill', now) " +  // 写回桶的新状态
                    "  return 1 " +                                                   // 1 = 放行
                    "else " +
                    "  return 0 " +                                                   // 0 = 桶空了，拒绝
                    "end";

    /**
     * 从令牌桶里取一个令牌（限流检查）。
     *
     * 【三个参数的对应关系】—— 这就是 KEYS/ARGV 的"数据源"
     *   第 2 个参数 List.of(key)                      → 变成 Lua 里的 KEYS[1]
     *   第 3 个参数开始 String.valueOf(capacity), ...  → 依次变成 ARGV[1], ARGV[2]
     *
     * ⚠️ 为什么 key 必须单独放 List，不能塞进 ARGV？
     *   Redis 集群模式下，key 分散在不同节点。脚本要操作哪些 key 必须【提前声明】，
     *   集群才能把脚本路由到正确的节点。塞进 ARGV 语法能过，但集群下会报错。
     *
     * @return 1 = 放行；0 = 被限流；null = 脚本执行异常
     */
    public Long tryTokenBucket(String key, long capacity, long rate) {
        return stringRedisTemplate.execute(
                new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class),  // 脚本本体 + 返回值类型
                List.of(key),                                            // → KEYS[1]
                String.valueOf(capacity), String.valueOf(rate)           // → ARGV[1], ARGV[2]
        );
    }


    // ② 方法（放在 testIncrement 下面）
/* ============================================================
 * 【对照实验版】doSeckillByRedisV2
 * ============================================================
 * 和 doSeckillByRedis 唯一的区别：这个方法【没有 @Transactional】。
 *
 * 【为什么这一行注解就是瓶颈】
 *   原版：@Transactional 包住整个方法，事务从进方法就开启，直到方法返回才提交。
 *         中间夹着两次 rabbitTemplate.convertAndSend，每次都要网络往返 + 等 confirm。
 *         于是「seckill_goods 那一行的行锁」被持有到 MQ 发完才释放。
 *         并发 N 个请求 → 第 N 个要排队等 (N-1) × (DB + 2×MQ) 的时间。
 *         压测时那条 UPDATE 平均 113ms、最大 317ms 就是这么来的，不是 SQL 慢。
 *
 *   V2：  没有外层事务，deductStock 单条 UPDATE 本身就是原子的（autocommit），
 *         执行完立刻提交、立刻放锁，MQ 在锁外发。
 *         行锁持有时间从「DB + 2×MQ」降到「DB」。
 *
 * 【代价】MQ 发送失败时 DB 已提交，需要手动回补（下面 try-catch 里做了）。
 *         原版是事务回滚，但 Redis 已经扣了，同样不一致，也要靠超时任务兜底。
 * ============================================================ */
public Result<?> doSeckillByRedisV2(Long userId, Long goodsId) {
    Long allowed = tryTokenBucket("seckill:limit:all", limitCapacity, limitRate);
    if (allowed == null || allowed == 0) {
        return Result.error(429, "请求过于频繁，请稍后重试");
    }

    // 一人一单预检
    if (orderMapper.selectByUserAndGoods(userId, goodsId) != null) {
        return Result.error(400, "每人限购一件");
    }

    long t1 = System.currentTimeMillis();
    Long result = stringRedisTemplate.execute(
            new DefaultRedisScript<>(STOCK_LUA, Long.class),
            List.of("seckill:stock:" + goodsId),
            "1");
    long t2 = System.currentTimeMillis();

    if (result == null) return Result.error(500, "系统繁忙");
    if (result == -1) return Result.error(400, "商品不存在");
    if (result == 0)  return Result.error(400, "已售罄");

    // ★ 没有外层事务 → 这条 UPDATE 自己提交、自己释放行锁
    int rows = goodsMapper.deductStock(goodsId);
    long t3 = System.currentTimeMillis();
    if (rows == 0) {
        addStockToRedis(goodsId);           // DB 没扣成，把 Redis 预减的还回去
        return Result.error(400, "库存不足");
    }

    String orderNo = UUID.randomUUID().toString().replace("-", "");
    String body = userId + ":" + goodsId + ":" + orderNo;
    try {
        // MQ 在锁外发送（此时行锁已经释放，别的请求可以立刻进来扣库存）
        rabbitTemplate.convertAndSend(
                "",
                RabbitMQConfig.SECKILL_ORDER_QUEUE,
                body,
                new SeckillCorrelationData(orderNo + ":order", body,
                        "", RabbitMQConfig.SECKILL_ORDER_QUEUE));
        rabbitTemplate.convertAndSend(
                OrderTimeoutMQConfig.SECKILL_DELAY_EXCHANGE,
                OrderTimeoutMQConfig.SECKILL_ORDER_DELAY_ROUTING_KEY,
                body,
                new SeckillCorrelationData(orderNo + ":delay", body,
                        OrderTimeoutMQConfig.SECKILL_DELAY_EXCHANGE,
                        OrderTimeoutMQConfig.SECKILL_ORDER_DELAY_ROUTING_KEY));
    } catch (Exception e) {
        // 没有事务兜底了，发失败要自己把库存还回去
        log.error("V2 发 MQ 失败，回补库存 orderNo={}", orderNo, e);
        goodsMapper.addStock(goodsId);
        addStockToRedis(goodsId);
        return Result.error(500, "下单失败，请重试");
    }

    long t4 = System.currentTimeMillis();
    log.info("耗时拆解[V2无事务]: Redis={}ms, DB={}ms, MQ={}ms, 总={}ms",
            t2 - t1, t3 - t2, t4 - t3, t4 - t1);

    return Result.success(new SeckillResult(orderNo, "抢购成功，订单处理中"));
}

@Transactional
public Result<?> doSeckillByRedis(Long userId, Long goodsId) {
    // 全局限流窗口（容量、速率均从配置读，便于压测时调整）
    Long allowed = tryTokenBucket("seckill:limit:all", limitCapacity, limitRate);
    if(allowed == null || allowed == 0){
        return  Result.error(429, "请求过于频繁，请稍后重试");
    }
    
    
    // ===== 一人一单（预检，放在最前面省资源）=====
    // 这个用户已经抢过这个商品 → 直接拒绝，不用去扣 Redis 库存
    if (orderMapper.selectByUserAndGoods(userId, goodsId) != null) {
        return Result.error(400, "每人限购一件");
    }

    long t1 = System.currentTimeMillis();
    Long result = stringRedisTemplate.execute(
            new DefaultRedisScript<>(STOCK_LUA, Long.class),   // "让 Redis 执行这段 Lua，返回数字"
            List.of("seckill:stock:" + goodsId),               // "脚本里 KEYS[1] 用这个 key"
            "1");                                              // "脚本里 ARGV[1] 用 1"（直接传，别包 List）

    long t2 = System.currentTimeMillis();

    if (result == null) return Result.error(500, "系统繁忙");
    if (result == -1) return Result.error(400, "商品不存在");
    if (result == 0)  return Result.error(400, "已售罄");

    // DB兜底，从数据库中扣除
    int rows = goodsMapper.deductStock(goodsId);
    // t3 必须取在 DB 扣减【之后】，否则 t3-t2≈0，DB 耗时永远显示 0
    long t3 = System.currentTimeMillis();
    if (rows == 0) {
        return Result.error(400, "库存不足");
    }

    // 扣成功 → 发 MQ 消息（异步落单，削峰），不再同步 insert




    // 创建订单order，创建uuid并且字符串话，并且将uuid前面的 - 号去掉
    String orderNo = UUID.randomUUID().toString().replace("-", "");
    String body = userId + ":" + goodsId + ":" + orderNo;
    // 消息格式：userId:goodsId:orderNo（消费者靠这三样重建订单）
    //
    // 【数据流动】convertAndSend(交换机, routingKey, 消息, 关联数据)
    //   · 交换机 = ""（空字符串）= 默认交换机
    //   · routingKey = 队列名 → 默认交换机按这个名字把消息投进队列
    //   · CorrelationData = 给消息贴"身份证"，confirm 回调时靠它认出是哪条消息
    // 注意：加 CorrelationData 必须用 4 参版（显式写出交换机和 routingKey），
    //       不能用 convertAndSend(队列名, 消息) 这种 2 参简化写法（会重载歧义）
    rabbitTemplate.convertAndSend(
            "",                                        // 默认交换机
            RabbitMQConfig.SECKILL_ORDER_QUEUE,        // routingKey = 队列名
            body,
            new SeckillCorrelationData(orderNo + ":order", body,
                    "", RabbitMQConfig.SECKILL_ORDER_QUEUE));

    // 同时发一条【延迟消息】：30 分钟后若仍未支付，自动取消订单 + 回补库存
    // 注意：发到【延迟交换机】，不是队列！消息靠 routingKey 路由进延迟队列，
    //       在延迟队列里躺够 TTL(30分钟)，过期变死信，最终到达超时处理队列
    rabbitTemplate.convertAndSend(
            OrderTimeoutMQConfig.SECKILL_DELAY_EXCHANGE,
            OrderTimeoutMQConfig.SECKILL_ORDER_DELAY_ROUTING_KEY,
            body,
            new SeckillCorrelationData(orderNo + ":delay", body,
                    OrderTimeoutMQConfig.SECKILL_DELAY_EXCHANGE,
                    OrderTimeoutMQConfig.SECKILL_ORDER_DELAY_ROUTING_KEY));

    long t4 = System.currentTimeMillis();
    log.info("耗时拆解: Redis={}ms, DB={}ms, MQ={}ms, 总={}ms",
            t2 - t1, t3 - t2, t4 - t3, t4 - t1);

    return Result.success(new SeckillResult(orderNo, "抢购成功，订单处理中"));
}



    @Transactional
    public Result<?> payOrder(String orderNo) {
        int lows = orderMapper.makePaid(orderNo);
        if(lows == 0){
            return Result.error(400,"订单不存在");
        }
        return Result.success("下单成功");
    }

        @Transactional
    public Result<?> cancelOrder(String orderNo) {
        // 1. 查订单，拿到 goodsId（取消要还库存，得知道是哪个商品）
        SeckillOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return Result.error(404, "订单不存在");
        }
        // 2. 取消订单（只有 status=0 待支付的才能取消成 2）
        int rows = orderMapper.cancel(orderNo);
        if (rows == 0) {
            return Result.error(400, "订单不是待支付状态，无法取消");
        }
        // 3. 取消成功 → 把库存加回去（DB + Redis 两边都要补！）
        //    只补 DB 不补 Redis → Redis 仍显示售罄 → 商品再也卖不出去（少卖）
        goodsMapper.addStock(order.getGoodsId());
        addStockToRedis(order.getGoodsId());
        return Result.success("取消成功");
    }
        // 消息队列（保留给 /mq/test 接口做连通性测试）
        public void sendOrderMessage(String orderNo) {
            rabbitTemplate.convertAndSend(RabbitMQConfig.SECKILL_ORDER_QUEUE, orderNo);
        }

    /**
     * 【库存回补】取消 / 超时订单时，把 Redis 库存 +1。
     *
     * 为什么必须单独做这件事？
     *   预减库存是【Redis 先扣】的。如果取消时只加 DB 库存、不管 Redis，
     *   就会出现「Redis 显示 0（已售罄），DB 实际还有 1」—— 后续所有请求
     *   都在 Lua 第一步就被拦下，商品再也卖不出去（少卖 bug）。
     *
     * 为什么不用 increment() 而用 Lua？
     *   increment 无脑 +1，如果 key 已过期/未预热，就会【凭空造出库存】。
     *   Lua 里先 EXISTS 判断：key 不存在就不动，保证只回补真实预减过的商品。
     */
    public void addStockToRedis(Long goodsId) {
        stringRedisTemplate.execute(
                new DefaultRedisScript<>(ADD_STOCK_LUA, Long.class),
                List.of("seckill:stock:" + goodsId));
    }

    public Result<?> selectAllGoods() {
        List<SeckillGoods> goods = goodsMapper.selectAll();
        return Result.success(goods);
    }

    public Result<?> queryOrderByNo(String orderNo) {
        SeckillOrder order = orderMapper.selectByOrderNo(orderNo);
        if(order == null){
            return Result.error(404, "订单不存在");
        }
        return Result.success(order);
    }

    public Result<?> selectGoodsById(Long goodsId) {
        SeckillGoods goods = goodsMapper.selectById(goodsId);
        if(goods == null){
            return Result.error(404,"商品不存在");
        }
        return Result.success(goods);
    }

}
   

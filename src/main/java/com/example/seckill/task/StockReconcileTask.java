package com.example.seckill.task;

import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 库存对账任务 —— 定时巡检「DB 库存 / Redis 库存 / 订单占用」三份账是否一致
 *
 * ========== 它在解决什么问题？ ==========
 *   秒杀链路的库存被写在两个地方：
 *     · Redis  seckill:stock:{goodsId}  —— 挡流量的第一道闸门（Lua 原子扣减）
 *     · MySQL  seckill_goods.stock      —— 最终真相
 *   两条路各走各的，任何一边出错都会造成「少卖」或「超卖」：
 *     · Redis 回补失败  → 缓存显示售罄，DB 还有货 → 商品再也卖不出去（少卖）
 *     · DB 扣减失败     → 缓存扣了、订单也有了，DB 库存却没减 → 账不平
 *
 *   本类不做修复，只负责【发现】。先保证判定逻辑没有误报，再谈自动修复。
 *
 * ========== 三条独立等式（核心） ==========
 *   设 occupied = countOccupied(goodsId)，即 status IN (0,1) 的订单数
 *
 *     A  DB 账平衡：    stock + occupied      == initialStock
 *     B  Redis 账平衡： redisStock + occupied == initialStock
 *     C  两边一致：     redisStock            == stock
 *
 *   分开判定是为了定位坏在哪一侧：
 *     A 破 → 数据库侧（漏扣 / 订单状态流转错）
 *     B 破 → 缓存侧（key 丢失 / 取消时 INCR 失败）
 *     C 破 → 最直观的"两边对不上"冒烟信号
 *
 * ========== 为什么必须跳过 5 分钟内的商品 ==========
 *
 *   一次下单，库存在三个地方各改一次，但【节奏不同】：
 *     ① Redis  DECR        —— 请求线程，同步
 *     ② DB     deductStock —— 请求线程，同步（SeckillService.java:275）
 *     ③ 订单行 insert      —— 消费者，异步（OrderConsumer.java:66）
 *
 *   ①② 在同一个请求里完成，③ 要绕一圈 MQ 才落库。
 *   在 ②③ 之间那几毫秒里：stock 已减、redisStock 已减、occupied 还没 +
 *     → A（stock+occupied）和 B（redis+occupied）同时差 1，是【正常中间态】，不是 bug。
 *   订单行一落库，三条立刻全平。
 *
 *   所以跳过规则挡的不是"订单状态"，而是"这个商品的账还在变动"：
 *   countRecent(goodsId) > 0 —— 本轮直接跳过，等数据落定后下一轮再看。
 *
 * ========== 一处时钟原则 ==========
 *   5 分钟窗口由 MySQL 的 NOW() 计算（见 SeckillOrderMapper.countRecent）。
 *   绝不在 Java 里用 LocalDateTime.now() 拼边界传进去 —— 两边时钟可能不同步。
 */
@Slf4j
@Component
public class StockReconcileTask {

    /** Redis 库存 key 前缀，必须和 STOCK_LUA / StockPreheatRunner 里保持一致 */
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";

    private final SeckillGoodsMapper goodsMapper;
    private final SeckillOrderMapper orderMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public StockReconcileTask(SeckillGoodsMapper goodsMapper,
                              SeckillOrderMapper orderMapper,
                              StringRedisTemplate stringRedisTemplate) {
        this.goodsMapper = goodsMapper;
        this.orderMapper = orderMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 定时对账。
     *
     * fixedDelay：上一轮【执行完】之后再等 60 秒，避免上一轮没跑完下一轮又压上来。
     *
     * 最外层必须 try-catch：@Scheduled 的线程一旦被未捕获异常打断，后面的调度就再也不跑了。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcile() {

        List<SeckillGoods> goodsList;
        try {
            goodsList = goodsMapper.selectAll();
        } catch (Exception e) {
            // DB 挂了：本轮放弃，等下一轮
            log.error("【对账】查询商品列表失败，本轮跳过", e);
            return;
        }

        if (goodsList == null || goodsList.isEmpty()) {
            return;
        }

        for (SeckillGoods goods : goodsList) {
            // 单个商品异常不能影响其他商品
            try {
                checkOne(goods);
            } catch (Exception e) {
                log.error("【对账】商品 {} 校验异常", goods.getId(), e);
            }
        }
    }

    /**
     * 校验单个商品，不平则告警。
     *
     * 执行顺序：
     *   ① 最近有订单写入 → 账还没落定，本轮不判
     *   ② 读 Redis 库存，key 不存在 → 未预热，本轮不判
     *   ③ 取四个数
     *   ④ A / B / C 三条独立判定
     */
    private void checkOne(SeckillGoods goods) {

        Long goodsId = goods.getId();

        // ===== ① 最近 5 分钟有订单写入 → 账还在变动，本轮跳过 =====
        int recentCount = orderMapper.countRecent(goodsId);
        if (recentCount > 0) {
            return;
        }

        // ===== ② 读 Redis 库存 =====
        String redisVal = stringRedisTemplate.opsForValue().get(STOCK_KEY_PREFIX + goodsId);
        if (redisVal == null) {
            // key 不存在：没有 Redis 账可对，A/B/C 全无意义 → 单独告警后退出
            log.warn("【对账】商品 {} 的库存 key 不存在（未预热或已过期），本轮跳过", goodsId);
            return;
        }

        // ===== ③ 四个数 =====
        int occupied = orderMapper.countOccupied(goodsId);
        int stock = goods.getStock();
        int initialStock = goods.getInitialStock();

        int redisStock;
        try {
            redisStock = Integer.parseInt(redisVal); // 从String中提取Integer值
        } catch (NumberFormatException e) {
            // Redis 里的值被人写坏了，别让一个脏数据把整个商品的对账炸掉
            log.error("【对账】商品 {} 的 Redis 库存值不是合法数字：{}", goodsId, redisVal);
            return;
        }

        // ===== ④ 三条独立判定：不平就报警，全平才打一条通过 =====
        boolean allBalanced = true;

        // A：DB 侧 —— 扣掉的库存 + 占用中的订单，应当正好等于期初
        if (stock + occupied != initialStock) {
            allBalanced = false;
            log.warn("【对账】商品 {} 校验A不平（DB侧）：stock({}) + occupied({}) = {}，应为 initialStock({})",
                    goodsId, stock, occupied, stock + occupied, initialStock);
        }

        // B：缓存侧
        if (redisStock + occupied != initialStock) {
            allBalanced = false;
            log.warn("【对账】商品 {} 校验B不平（Redis侧）：redisStock({}) + occupied({}) = {}，应为 initialStock({})",
                    goodsId, redisStock, occupied, redisStock + occupied, initialStock);
        }

        // C：两边一致性 —— 最简单直接的"对不上"信号
        if (redisStock != stock) {
            allBalanced = false;
            log.warn("【对账】商品 {} 校验C不平（两边不一致）：redisStock({}) != stock({})",
                    goodsId, redisStock, stock);
        }

        if (allBalanced) {
            log.info("【对账】商品 {} 通过：stock={} redis={} occupied={} initial={}",
                    goodsId, stock, redisStock, occupied, initialStock);
        }
    }
}

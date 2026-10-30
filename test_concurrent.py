"""
秒杀系统并发压测脚本 —— 自动校验「防超卖 / 一人一单 / 库存一致性」

用法：
    python test_concurrent.py [并发数] [库存]

示例：
    python test_concurrent.py 50 10      # 50 并发抢 10 件库存
    python test_concurrent.py 200 50     # 200 并发抢 50 件库存

脚本会自动完成：
    1. 重置环境（DB 库存、Redis 库存、订单表、令牌桶）
    2. 发起 N 并发抢购
    3. 校验 5 项关键指标，输出 PASS / FAIL

────────────────────────────────────────────────────────────
⚠️ 关于限流：项目的令牌桶默认 capacity=10、rate=5（见 application.yml），
   所以高并发下大部分请求会被 429 拦下，这是【设计如此】。
   如果想压到「防超卖」那一层（让请求真正打到库存扣减），
   先临时把 application.yml 的限流参数调大再重启应用：

       seckill:
         limit:
           capacity: 100000
           rate: 100000

   然后：python test_concurrent.py 200 50
────────────────────────────────────────────────────────────
"""
import subprocess
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import requests

# ==================== 配置 ====================
BASE = "http://localhost:8081/api/seckill"
ENDPOINT = "redis"                    # 秒杀主接口
GOODS_ID = 1
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
MEMURAI = r"C:\Program Files\Memurai\memurai-cli.exe"
MYSQL_ARGS = ["-uroot", "-p123456", "-h", "127.0.0.1", "-P", "3307", "seckill"]

# ==================== 参数 ====================
concurrency = int(sys.argv[1]) if len(sys.argv) > 1 else 50
stock_init = int(sys.argv[2]) if len(sys.argv) > 2 else 10


# ==================== 工具函数 ====================
def mysql(sql):
    """执行 SQL，返回纯文本结果"""
    r = subprocess.run(
        [MYSQL] + MYSQL_ARGS + ["-N", "-B", "-e", sql],
        capture_output=True, text=True, encoding="utf-8",
    )
    return r.stdout.strip()


def redis(*args):
    """执行 Redis 命令"""
    r = subprocess.run(
        [MEMURAI, "-p", "6379"] + list(args),
        capture_output=True, text=True, encoding="utf-8",
    )
    return r.stdout.strip()


def reset_env():
    """重置测试环境"""
    mysql(f"UPDATE seckill_goods SET stock={stock_init} WHERE id={GOODS_ID};")
    mysql("DELETE FROM seckill_order;")
    redis("SET", f"seckill:stock:{GOODS_ID}", str(stock_init))
    redis("DEL", "seckill:limit:all")      # 清空令牌桶


def hit(i):
    """一次抢购请求"""
    try:
        r = requests.post(
            f"{BASE}/{ENDPOINT}",
            params={"userId": i, "goodsId": GOODS_ID},
            timeout=10,
        )
        body = r.json()
        return body.get("code"), body.get("message", "")
    except Exception as e:
        return 0, str(e)


# ==================== 主流程 ====================
print("=" * 62)
print(f"秒杀并发压测   接口=/{ENDPOINT}   并发={concurrency}   初始库存={stock_init}")
print("=" * 62)

print("\n[1/3] 重置测试环境...")
reset_env()
print(f"      DB 库存    = {mysql(f'SELECT stock FROM seckill_goods WHERE id={GOODS_ID};')}")
print(f"      Redis 库存 = {redis('GET', f'seckill:stock:{GOODS_ID}')}")
print(f"      订单数     = {mysql('SELECT COUNT(*) FROM seckill_order;')}")

print(f"\n[2/3] 发起 {concurrency} 并发抢购...")
with ThreadPoolExecutor(max_workers=concurrency) as ex:
    results = list(ex.map(hit, range(1, concurrency + 1)))

code_counter = Counter(c for c, _ in results)
ok = code_counter.get(200, 0)

print("\n      返回码分布：")
for code, count in sorted(code_counter.items(), key=lambda x: -x[1]):
    label = {200: "抢购成功", 429: "限流拦截", 400: "业务拒绝", 0: "请求异常"}.get(code, "其他")
    msg = next((m for c, m in results if c == code), "")
    print(f"        {code:>3} {label:<8} {count:>5} 条   示例：{msg[:34]}")

print("\n[3/3] 校验结果...")
# ⚠️ 关键：订单是 MQ 异步落单的，脚本必须等消费者写完再校验，
#    否则会读到「成功 50 但订单只有 13」这种假不一致。
import time as _time
for _ in range(30):                       # 最多等 6 秒
    _n = int(mysql("SELECT COUNT(*) FROM seckill_order;"))
    if _n >= ok:
        break
    _time.sleep(0.2)

db_stock = int(mysql(f"SELECT stock FROM seckill_goods WHERE id={GOODS_ID};"))
redis_stock = int(redis("GET", f"seckill:stock:{GOODS_ID}"))
order_count = int(mysql("SELECT COUNT(*) FROM seckill_order;"))
dup_users = mysql("SELECT user_id FROM seckill_order GROUP BY user_id HAVING COUNT(*) > 1;")
sold = stock_init - db_stock

checks = [
    ("无超卖（DB 库存 >= 0）", db_stock >= 0, f"DB 库存 = {db_stock}"),
    ("订单数 <= 库存", order_count <= stock_init, f"订单数 = {order_count}, 库存 = {stock_init}"),
    ("Redis 与 DB 库存一致", redis_stock == db_stock, f"Redis = {redis_stock}, DB = {db_stock}"),
    ("一人一单（无重复用户）", dup_users == "", f"重复用户 = {dup_users if dup_users else '无'}"),
    ("成功返回数 == 实际售出数", ok == sold, f"成功 = {ok}, 售出 = {sold}"),
    ("成功返回数 == 落单数", ok == order_count, f"成功 = {ok}, 订单 = {order_count}"),
]

print()
all_pass = True
for name, passed, detail in checks:
    print(f"      {'✅' if passed else '❌'} {name:<24} （{detail}）")
    if not passed:
        all_pass = False

# 提示：如果全被限流，校验虽然通过但没压到核心逻辑
if ok == 0 and code_counter.get(429, 0) > 0:
    print("\n      💡 提示：所有请求都被限流拦下了，本次未压到「防超卖」逻辑。")
    print("         想压到核心逻辑，请先把 application.yml 的 seckill.limit.capacity/rate ")
    print("         临时调大（如 100000）并重启应用，再重跑本脚本。")

print("\n" + "=" * 62)
print(f"结论：{'🎉 PASS —— 所有校验通过' if all_pass else '⚠️  FAIL —— 存在校验未通过项'}")
print("=" * 62)
sys.exit(0 if all_pass else 1)

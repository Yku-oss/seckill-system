#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""秒杀压测脚本 —— 拿真 QPS / 响应时间 / 成功率"""

import requests
import threading
import time
import subprocess
import sys

URL = "http://localhost:8081/api/seckill/redis"
GOODS_ID = 1
INITIAL_STOCK = 100


def reset_stock():
    """压测前重置库存为 100"""
    cmd = [
        "docker", "exec", "seckill-mysql",
        "mysql", "-uroot", "-p123456", "seckill", "-e",
        f"UPDATE seckill_goods SET stock={INITIAL_STOCK}, version=0 WHERE id={GOODS_ID}; "
        f"DELETE FROM seckill_order WHERE goods_id={GOODS_ID};"
    ]
    try:
        subprocess.run(cmd, capture_output=True, timeout=10)
        # 清 Redis 库存
        subprocess.run(["docker", "exec", "seckill-redis", "redis-cli",
                        "SET", f"seckill:stock:{GOODS_ID}", str(INITIAL_STOCK)],
                       capture_output=True, timeout=5)
        print(f"[重置] 库存={INITIAL_STOCK}，订单清空，Redis 重置")
    except Exception as e:
        print(f"[重置失败] {e}")


def stress_test(concurrency, total):
    results = []
    lock = threading.Lock()
    done = [0]

    def worker(uid):
        try:
            start = time.time()
            resp = requests.post(URL, data={"userId": uid, "goodsId": GOODS_ID}, timeout=15)
            elapsed = time.time() - start
            code = resp.status_code
            # 尝试解析业务 code
            try:
                body = resp.json()
                biz_code = body.get("code", code)
            except:
                biz_code = code
        except Exception as e:
            code, biz_code, elapsed = -1, -1, 0
        with lock:
            results.append((code, biz_code, elapsed))
            done[0] += 1

    threads = []
    t0 = time.time()
    for i in range(total):
        while len(threads) - done[0] >= concurrency:
            time.sleep(0.002)
        t = threading.Thread(target=worker, args=(i + 10000,))
        threads.append(t)
        t.start()
    for t in threads:
        t.join(timeout=20)
    total_time = time.time() - t0

    # 统计
    success = sum(1 for _, b, _ in results if b == 200)
    sold_out = sum(1 for _, b, _ in results if b == 400)
    limited = sum(1 for _, b, _ in results if b == 429)
    errors = sum(1 for c, _, _ in results if c == -1)
    latencies = [e for _, _, e in results if e > 0]
    qps = total / total_time if total_time > 0 else 0
    avg_lat = statistics.mean(latencies) * 1000 if latencies else 0
    p95_lat = statistics.quantiles(latencies, n=20)[18] * 1000 if len(latencies) >= 20 else 0

    print(f"\n{'='*60}")
    print(f"并发={concurrency}  总请求={total}")
    print(f"  成功下单: {success}")
    print(f"  已售罄:   {sold_out}")
    print(f"  被限流:   {limited} (429)")
    print(f"  异常:     {errors}")
    print(f"  总耗时:   {total_time:.2f}s")
    print(f"  QPS:      {qps:.1f}")
    print(f"  平均响应: {avg_lat:.1f}ms")
    print(f"  P95响应:  {p95_lat:.1f}ms")
    print(f"{'='*60}")
    return qps, avg_lat, p95_lat


if __name__ == "__main__":
    import statistics

    # 检查后端
    try:
        r = requests.get("http://localhost:8081/api/seckill/goods", timeout=5)
        print(f"[环境] 后端可达，HTTP {r.status_code}")
    except:
        print("[错误] 后端 8081 不可达，请先启动后端！")
        sys.exit(1)

    # 压测级别
    levels = [
        (100, 100),
        (500, 500),
        (1000, 1000),
        (2000, 2000),
    ]

    print("\n🚀 秒杀压测开始（库存=100，令牌桶 capacity=10 rate=5）\n")

    for conc, total in levels:
        reset_stock()
        time.sleep(1)
        stress_test(conc, total)
        time.sleep(2)

    print("\n✅ 压测完成。把上面的 QPS / P95 / 成功率写进简历。")
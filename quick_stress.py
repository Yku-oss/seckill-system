import requests, threading, time, statistics, sys

URL = "http://localhost:8081/api/seckill/redis"

def run(conc, total):
    results = []
    lock = threading.Lock()
    done = [0]

    def worker(uid):
        try:
            s = time.time()
            r = requests.post(URL, data={"userId": uid, "goodsId": 1}, timeout=10)
            e = time.time() - s
            try:
                b = r.json().get("code", r.status_code)
            except:
                b = r.status_code
            c = r.status_code
        except:
            c, b, e = -1, -1, 0
        with lock:
            results.append((c, b, e))
            done[0] += 1

    ts = []
    t0 = time.time()
    for i in range(total):
        while len(ts) - done[0] >= conc:
            time.sleep(0.003)
        t = threading.Thread(target=worker, args=(i + 10000,))
        ts.append(t)
        t.start()
    for t in ts:
        t.join(timeout=15)
    tt = time.time() - t0

    ok = sum(1 for _, b, _ in results if b == 200)
    so = sum(1 for _, b, _ in results if b == 400)
    lm = sum(1 for _, b, _ in results if b == 429)
    er = sum(1 for c, _, _ in results if c == -1)
    lats = [e for _, _, e in results if e > 0]
    qps = total / tt if tt > 0 else 0
    avg = statistics.mean(lats) * 1000 if lats else 0
    p95 = statistics.quantiles(lats, n=20)[18] * 1000 if len(lats) >= 20 else 0
    print(f"conc={conc} total={total} ok={ok} soldout={so} limit={lm} err={er} time={tt:.2f}s QPS={qps:.1f} avg={avg:.1f}ms p95={p95:.1f}ms")
    sys.stdout.flush()

# 重置库存
import subprocess
subprocess.run(["docker", "exec", "seckill-mysql", "mysql", "-uroot", "-p123456", "seckill", "-e",
    "UPDATE seckill_goods SET stock=100, version=0 WHERE id=1; DELETE FROM seckill_order WHERE goods_id=1;"],
    capture_output=True, timeout=10)
subprocess.run(["docker", "exec", "seckill-redis", "redis-cli", "SET", "seckill:stock:1", "100"],
    capture_output=True, timeout=5)
print("stock reset to 100")
sys.stdout.flush()

run(100, 100)
time.sleep(2)

# 重置
subprocess.run(["docker", "exec", "seckill-mysql", "mysql", "-uroot", "-p123456", "seckill", "-e",
    "UPDATE seckill_goods SET stock=100, version=0 WHERE id=1; DELETE FROM seckill_order WHERE goods_id=1;"],
    capture_output=True, timeout=10)
subprocess.run(["docker", "exec", "seckill-redis", "redis-cli", "SET", "seckill:stock:1", "100"],
    capture_output=True, timeout=5)
print("stock reset to 100")
sys.stdout.flush()

run(500, 500)
time.sleep(2)

# 重置
subprocess.run(["docker", "exec", "seckill-mysql", "mysql", "-uroot", "-p123456", "seckill", "-e",
    "UPDATE seckill_goods SET stock=100, version=0 WHERE id=1; DELETE FROM seckill_order WHERE goods_id=1;"],
    capture_output=True, timeout=10)
subprocess.run(["docker", "exec", "seckill-redis", "redis-cli", "SET", "seckill:stock:1", "100"],
    capture_output=True, timeout=5)
print("stock reset to 100")
sys.stdout.flush()

run(1000, 1000)
time.sleep(2)

# 重置
subprocess.run(["docker", "exec", "seckill-mysql", "mysql", "-uroot", "-p123456", "seckill", "-e",
    "UPDATE seckill_goods SET stock=100, version=0 WHERE id=1; DELETE FROM seckill_order WHERE goods_id=1;"],
    capture_output=True, timeout=10)
subprocess.run(["docker", "exec", "seckill-redis", "redis-cli", "SET", "seckill:stock:1", "100"],
    capture_output=True, timeout=5)
print("stock reset to 100")
sys.stdout.flush()

run(2000, 2000)

print("\nDONE")
import threading
import requests
import time

BASE = "http://localhost:8081/api/lock"

def call(path, label):
    try:
        r = requests.get(f"{BASE}/{path}", timeout=10)
        print(f"  [{label}] {r.text}")
    except Exception as e:
        print(f"  [{label}] 请求异常: {e}")

print("=" * 50)
print("实验1：BUG 版（不加 uuid 校验，直接 DEL）")
print("=" * 50)
requests.get(f"{BASE}/reset")
print("两个线程同时请求 /lock/bug ...\n")

t1 = threading.Thread(target=call, args=("bug", "线程A"))
t2 = threading.Thread(target=call, args=("bug", "线程B"))
t1.start()
t2.start()
t1.join()
t2.join()

print("\n预期：线程A先拿到锁→sleep5秒→3秒后锁过期→线程B拿到锁")
print("     →线程A sleep完→DEL删了B的锁→互斥破坏次数 >= 1")

time.sleep(1)
print("\n" + "=" * 50)
print("实验2：FIX 版（Lua 校验 uuid 再删）")
print("=" * 50)
requests.get(f"{BASE}/reset")
print("两个线程同时请求 /lock/fix ...\n")

t1 = threading.Thread(target=call, args=("fix", "线程A"))
t2 = threading.Thread(target=call, args=("fix", "线程B"))
t1.start()
t2.start()
t1.join()
t2.join()

print("\n预期：线程A的锁过期后B拿到锁，但A解锁时Lua发现锁不是自己的→不删")
print("     →互斥破坏次数 = 0，解锁结果=0（没删别人的）")
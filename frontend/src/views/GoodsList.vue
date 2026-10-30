<script setup>
import { ref, onMounted } from 'vue'
import { getGoods, doSeckill, getOrder } from '../api'

// ========== 响应式数据 ==========
const goods = ref([])          // 商品列表
const userId = ref(8888)       // 抢购用户 ID
const goodsId = ref(1)         // 商品 ID
const orderNo = ref('')        // 待查询的订单号
const queryResult = ref(null)  // 订单查询结果
const logs = ref([])           // 操作日志
const loading = ref(false)

// ========== 工具：加一条日志 ==========
function addLog(msg, ok = true) {
  const time = new Date().toLocaleTimeString()
  logs.value.unshift({ time, msg, ok })
}

// ========== ① 加载商品列表 ==========
async function loadGoods() {
  try {
    const res = await getGoods()
    goods.value = res.data.data || []
    addLog(`加载商品成功，共 ${goods.value.length} 条`)
  } catch (e) {
    addLog('加载商品失败：' + e.message, false)
  }
}

// ========== ② 秒杀下单 ==========
async function handleSeckill() {
  loading.value = true
  try {
    const res = await doSeckill(userId.value, goodsId.value)
    const body = res.data
    // 后端返回：{ code, message, data: { orderNo, message } }
    if (body.code === 200) {
      const { orderNo, message } = body.data
      addLog(`${message}｜订单号：${orderNo}`, true)
      orderNo.value = orderNo      // ⭐ 自动填入查询框，方便立刻查询
      await loadGoods()            // 刷新库存
    } else {
      addLog(`抢购失败(${body.code})：${body.message}`, false)
    }
  } catch (e) {
    addLog('请求异常：' + e.message, false)
  } finally {
    loading.value = false
  }
}

// ========== ③ 查询订单 ==========
async function handleQuery() {
  if (!orderNo.value.trim()) {
    addLog('请先输入订单号', false)
    return
  }
  try {
    const res = await getOrder(orderNo.value.trim())
    const body = res.data
    if (body.code === 200) {
      queryResult.value = body.data
      addLog(`订单查询成功：${body.data.orderNo}`)
    } else {
      queryResult.value = null
      addLog(`订单查询失败(${body.code})：${body.message}`, false)
    }
  } catch (e) {
    addLog('请求异常：' + e.message, false)
  }
}

// 状态码转中文
function statusText(s) {
  return { 0: '待支付', 1: '已支付', 2: '已取消' }[s] || '未知'
}

// 页面加载时自动拉商品
onMounted(loadGoods)
</script>

<template>
  <div class="page">
    <h1>⚡ 秒杀系统</h1>  
    <p class="sub">Spring Boot + Redis + RabbitMQ + Docker ｜ 前端 Vue 3 + Vite</p>

    <!-- ① 商品列表 -->
    <section class="card">
      <div class="card-head">
        <h2>商品列表</h2>
        <button class="btn-ghost" @click="loadGoods">刷新</button>
      </div>
      <table v-if="goods.length">
        <thead>
          <tr>
            <th>ID</th><th>名称</th><th>库存</th>
            <th>开始时间</th><th>结束时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in goods" :key="g.id">
            <td>{{ g.id }}</td>
            <td>
              <router-link :to="`/goods/` + g.id">{{g.goodsName}}</router-link>
            </td>
            <td>
              <span :class="['stock', g.stock > 0 ? 'ok' : 'empty']">
                {{ g.stock }}
              </span>
            </td>
            <td>{{ g.startTime }}</td>
            <td>{{ g.endTime }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty-tip">暂无商品数据</p>
    </section>

    <!-- ② 抢购 -->
    <section class="card">
      <h2>抢购</h2>
      <div class="row">
        <label>用户 ID
          <input v-model.number="userId" type="number" />
        </label>
        <label>商品 ID
          <input v-model.number="goodsId" type="number" />
        </label>
        <button class="btn-primary" :disabled="loading" @click="handleSeckill">
          {{ loading ? '抢购中…' : '立即抢购' }}
        </button>
      </div>
    </section>

    <!-- ③ 订单查询 -->
    <section class="card">
      <h2>订单查询</h2>
      <div class="row">
        <input v-model="orderNo" placeholder="输入订单号（orderNo）" class="wide" />
        <button class="btn-primary" @click="handleQuery">查询</button>
      </div>
      <div v-if="queryResult" class="order-box">
        <p><b>订单号：</b>{{ queryResult.orderNo }}</p>
        <p><b>用户 ID：</b>{{ queryResult.userId }}</p>
        <p><b>商品 ID：</b>{{ queryResult.goodsId }}</p>
        <p><b>状态：</b>{{ statusText(queryResult.status) }}</p>
      </div>
    </section>

    <!-- ④ 操作日志 -->
    <section class="card">
      <h2>操作日志</h2>
      <ul class="logs">
        <li v-for="(l, i) in logs" :key="i" :class="l.ok ? 'ok' : 'err'">
          <span class="time">{{ l.time }}</span>{{ l.msg }}
        </li>
      </ul>
      <p v-if="!logs.length" class="empty-tip">暂无日志</p>
    </section>
  </div>
</template>

<style scoped>
.page { max-width: 960px; margin: 0 auto; padding: 24px 16px 60px; font-family: system-ui, "Microsoft YaHei", sans-serif; }
h1 { margin: 0 0 4px; font-size: 26px; }
.sub { color: #888; margin: 0 0 24px; font-size: 13px; }
.card { background: #fff; border: 1px solid #e8e8e8; border-radius: 10px; padding: 18px; margin-bottom: 18px; box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.card h2 { margin: 0 0 14px; font-size: 16px; color: #333; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.card-head h2 { margin: 0; }

table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #f0f0f0; }
th { background: #fafafa; color: #666; font-weight: 600; }
.stock { font-weight: 700; }
.stock.ok { color: #16a34a; }
.stock.empty { color: #dc2626; }

.row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
label { display: flex; align-items: center; gap: 6px; font-size: 14px; color: #555; }
input { padding: 7px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 14px; width: 100px; }
input.wide { width: 100%; flex: 1; min-width: 240px; }

button { cursor: pointer; border: none; border-radius: 6px; font-size: 14px; padding: 8px 18px; transition: .15s; }
.btn-primary { background: #1677ff; color: #fff; }
.btn-primary:hover:not(:disabled) { background: #0958d9; }
.btn-primary:disabled { background: #a9c9ff; cursor: not-allowed; }
.btn-ghost { background: #f5f5f5; color: #555; padding: 5px 12px; }
.btn-ghost:hover { background: #e8e8e8; }

.order-box { margin-top: 14px; padding: 12px 14px; background: #f6ffed; border: 1px solid #b7eb8f; border-radius: 8px; font-size: 14px; }
.order-box p { margin: 5px 0; }

.logs { list-style: none; padding: 0; margin: 0; max-height: 260px; overflow-y: auto; font-size: 13px; }
.logs li { padding: 7px 10px; border-radius: 6px; margin-bottom: 6px; background: #f6ffed; color: #237804; font-family: Consolas, monospace; }
.logs li.err { background: #fff1f0; color: #cf1322; }
.time { color: #999; margin-right: 10px; }
.empty-tip { color: #bbb; font-size: 13px; text-align: center; padding: 14px 0; }
</style>

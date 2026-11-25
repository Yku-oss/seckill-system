<script setup>
// 商品详情页：看信息 → 抢购 → 成功弹单号 / 失败弹提示
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getGoodsById, doSeckill } from '../api.js'
import { showToast } from '../toast'

const route = useRoute()
const id = route.params.id

// 用户身份：首次进站随机发一个存在本地，刷新后还是同一个人。
// 真实项目这里是登录态，这里用 localStorage 模拟，好让「每人限购一件」能验证出来。
function getUserId() {
  let uid = localStorage.getItem('fs_uid')
  if (!uid) {
    uid = String(Math.floor(100000 + Math.random() * 900000))
    localStorage.setItem('fs_uid', uid)
  }
  return Number(uid)
}

const userId = ref(getUserId())
const buying = ref(false)
const orderResult = ref(null)
const loading = ref(false)
const goods = ref(null)

async function getGoodsDetail() {
  loading.value = true
  try {
    const res = await getGoodsById(id)
    goods.value = res.data.data
  } catch (e) {
    showToast('商品信息加载失败，请返回重试', 'error')
  } finally {
    loading.value = false
  }
}

async function handleBuy() {
  // 双保险：按钮虽然 disabled 了，但连点两下仍可能触发两次事件
  if (buying.value) return

  if (!goods.value || goods.value.stock <= 0) {
    showToast('该商品已售罄', 'error')
    return
  }

  buying.value = true
  try {
    const res = await doSeckill(userId.value, id)
    const body = res.data

    if (body.code === 200) {
      orderResult.value = body.data
      await getGoodsDetail()          // 抢完刷新库存
    } else {
      // 业务失败：每人限购一件 / 库存不足 / 活动未开始…
      // 后端把原因放在 message 里，直接给用户看
      showToast(body.message || '抢购失败，请稍后重试', 'error')
    }
  } catch (e) {
    // 网络层失败：断网、超时、后端 5xx
    const msg = e.response?.data?.message || '网络异常，请稍后重试'
    showToast(msg, 'error')
  } finally {
    buying.value = false
  }
}

onMounted(getGoodsDetail)
</script>

<template>
  <div class="page">
    <router-link to="/" class="back">
      <span class="back-arrow">←</span> 返回商品列表
    </router-link>

    <div v-if="loading" class="empty">正在加载中…</div>

    <div v-else-if="goods" class="detail">
      <div class="detail-head">
        <div class="eyebrow">LIMITED / <span class="mono">NO.{{ String(goods.id).padStart(3, '0') }}</span></div>
        <h1>{{ goods.goodsName }}</h1>
        <p class="sub">每人限购一件 · 库存实时同步</p>
      </div>

      <section class="card">
        <div class="card-title"><span class="card-index mono">INFO</span><h2>商品信息</h2></div>

        <div class="kv">
          <div class="kv-row">
            <span>当前库存</span>
            <b :class="['pill', goods.stock > 0 ? 'ok' : 'empty']">{{ goods.stock }}</b>
          </div>
          <div class="kv-row"><span>开始时间</span><b class="mono">{{ goods.startTime }}</b></div>
          <div class="kv-row"><span>结束时间</span><b class="mono">{{ goods.endTime }}</b></div>
          <div class="kv-row"><span>当前用户</span><b class="mono">#{{ userId }}</b></div>
        </div>

        <button
          class="btn btn-primary btn-buy"
          :disabled="buying || goods.stock <= 0"
          @click="handleBuy"
        >
          {{ buying ? '抢购中…' : goods.stock <= 0 ? '已售罄' : '⚡ 立即抢购' }}
        </button>
      </section>
    </div>

    <div v-else class="empty">商品不存在</div>

    <!-- 抢购成功：居中弹窗，带订单号，需要用户确认 -->
    <transition name="fade">
      <div v-if="orderResult" class="mask" @click.self="orderResult = null">
        <div class="dialog">
          <div class="dialog-icon">✓</div>
          <h3 class="dialog-title">抢购成功</h3>
          <p class="dialog-sub">订单已创建，请留意后续通知</p>
          <div class="dialog-no mono">{{ orderResult.orderNo }}</div>
          <button class="btn btn-primary dialog-btn" @click="orderResult = null">知道了</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.page { max-width: 780px; margin: 0 auto; padding: 64px 32px 96px; }

.back {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  font: 500 11px var(--mono);
  letter-spacing: .14em;
  text-transform: uppercase;
  color: var(--text-3);
  margin-bottom: 36px;
  transition: color .2s var(--ease);
}
.back:hover { color: var(--brand); }
.back-arrow { transition: transform .25s var(--ease); }
.back:hover .back-arrow { transform: translateX(-4px); }

.detail-head {
  position: relative;
  padding: 0 2px 32px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 26px;
  overflow: hidden;
}

.detail-head::before {
  content: "";
  position: absolute;
  top: -170px;
  right: -120px;
  width: 400px;
  height: 400px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(230, 184, 119, .1), transparent 62%);
  pointer-events: none;
}

.detail-head h1 {
  position: relative;
  margin: 22px 0 12px;
  font-size: clamp(30px, 4.6vw, 44px);
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -.035em;
}

.sub { margin: 0; font-size: 13px; line-height: 1.75; color: var(--text-2); }

.kv {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: rgba(0, 0, 0, .22);
  padding: 4px 20px;
}

.kv-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 17px 0;
  border-bottom: 1px solid rgba(255, 255, 255, .045);
  font-size: 13px;
}

.kv-row:last-child { border-bottom: none; }

.kv-row > span {
  font: 500 10px var(--mono);
  letter-spacing: .16em;
  text-transform: uppercase;
  color: var(--text-3);
}

.kv-row b { font-weight: 600; font-size: 13px; color: var(--text); }

.btn-buy {
  display: block;
  width: 100%;
  margin-top: 26px;
  height: 48px;
  font-size: 14px;
  letter-spacing: .04em;
}

.empty { color: var(--text-3); text-align: center; padding: 56px 0; font-size: 13px; }

/* ---------- 成功弹窗 ---------- */
.mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(4, 6, 12, .72);
  backdrop-filter: blur(6px);
}

.dialog {
  width: min(380px, 100%);
  padding: 36px 32px 28px;
  border: 1px solid rgba(78, 207, 138, .22);
  border-radius: 16px;
  background: linear-gradient(180deg, #10161c, #0b1015);
  text-align: center;
  box-shadow: 0 24px 80px rgba(0, 0, 0, .6);
}

.dialog-icon {
  width: 52px;
  height: 52px;
  margin: 0 auto 20px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: rgba(78, 207, 138, .12);
  color: var(--ok);
  font-size: 22px;
  box-shadow: 0 0 28px rgba(78, 207, 138, .22);
}

.dialog-title {
  margin: 0 0 10px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -.02em;
}

.dialog-sub { margin: 0 0 20px; font-size: 13px; color: var(--text-2); }

.dialog-no {
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, .04);
  border: 1px solid var(--line);
  font-size: 12px;
  color: var(--text);
  word-break: break-all;
}

.dialog-btn { width: 100%; height: 44px; margin-top: 22px; }

.fade-enter-active, .fade-leave-active { transition: opacity .22s var(--ease); }
.fade-enter-from, .fade-leave-to { opacity: 0; }

@media (max-width: 720px) {
  .page { padding: 40px 20px 72px; }
  .kv { padding: 4px 16px; }
}
</style>

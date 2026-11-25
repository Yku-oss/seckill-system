<script setup>
// 商品广场：用户在这里看有哪些限量商品，点进详情去抢
import { ref, onMounted } from 'vue'
import { getGoods } from '../api'
import { showToast } from '../toast'

const goods = ref([])
const loading = ref(false)

async function loadGoods() {
  loading.value = true
  try {
    const res = await getGoods()
    goods.value = res.data.data || []
  } catch (e) {
    showToast('商品加载失败，请刷新重试', 'error')
  } finally {
    loading.value = false
  }
}

// 商品状态：即将开始 / 抢购中 / 已售罄 / 已结束
function statusOf(g) {
  const now = Date.now()
  const start = new Date(g.startTime).getTime()
  const end = new Date(g.endTime).getTime()
  if (g.stock <= 0) return { text: '已售罄', cls: 'off' }
  if (now < start) return { text: '即将开始', cls: 'wait' }
  if (now > end) return { text: '已结束', cls: 'off' }
  return { text: '抢购中', cls: 'on' }
}

// 已抢比例，用来画进度条
function soldPercent(g) {
  if (!g.initialStock) return 0
  const sold = g.initialStock - g.stock
  return Math.min(100, Math.max(0, (sold / g.initialStock) * 100))
}

onMounted(loadGoods)
</script>

<template>
  <div class="page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="live-dot"></span> 限时抢购进行中</div>
        <h1>今日<span class="grad">限量</span></h1>
        <p class="sub">每人限购一件，先到先得，售完即止。</p>
      </div>
      <div class="hero-stat">
        <div class="stat-num mono">{{ goods.length }}</div>
        <div class="stat-label">在售商品</div>
      </div>
    </section>

    <div v-if="loading" class="empty">加载中…</div>

    <div v-else-if="goods.length" class="grid">
      <article v-for="g in goods" :key="g.id" class="goods">
        <div class="goods-head">
          <span class="goods-id mono">NO.{{ String(g.id).padStart(3, '0') }}</span>
          <span :class="['tag', statusOf(g).cls]">{{ statusOf(g).text }}</span>
        </div>

        <h3 class="goods-name">{{ g.goodsName }}</h3>

        <div class="stock">
          <span class="stock-num">{{ g.stock }}</span>
          <span class="stock-total">/ {{ g.initialStock }} 件</span>
        </div>

        <div class="bar"><i :style="{ width: soldPercent(g) + '%' }"></i></div>
        <div class="bar-note">已抢 {{ g.initialStock - g.stock }} 件</div>

        <router-link
          v-if="g.stock > 0"
          :to="`/goods/${g.id}`"
          class="btn btn-primary goods-btn"
        >立即抢购</router-link>
        <button v-else class="btn goods-btn sold-out" disabled>已售罄</button>
      </article>
    </div>

    <p v-else class="empty">暂无在售商品</p>
  </div>
</template>

<style scoped>
.page { max-width: 1160px; margin: 0 auto; padding: 64px 32px 96px; }

/* ---------- Hero ---------- */
.hero {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 40px;
  padding: 0 2px 40px;
  margin-bottom: 36px;
  border-bottom: 1px solid var(--line);
  overflow: hidden;
}

.hero::before {
  content: "";
  position: absolute;
  top: -190px;
  right: -90px;
  width: 480px;
  height: 480px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(230, 184, 119, .13), transparent 62%);
  pointer-events: none;
}

.hero h1 {
  position: relative;
  margin: 22px 0 12px;
  font-size: clamp(36px, 5.6vw, 60px);
  line-height: 1.02;
  letter-spacing: -.04em;
  font-weight: 700;
}

.grad {
  background: var(--gold);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.sub {
  margin: 0;
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-2);
  max-width: 48ch;
}

.hero-stat {
  position: relative;
  flex: none;
  text-align: right;
  padding: 20px 26px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: linear-gradient(180deg, rgba(230, 184, 119, .07), rgba(255, 255, 255, .012));
}

.stat-num {
  font-size: 48px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -.04em;
  font-variant-numeric: tabular-nums;
  background: var(--gold);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.stat-label {
  margin-top: 10px;
  font: 500 10px var(--mono);
  letter-spacing: .18em;
  text-transform: uppercase;
  color: var(--text-3);
}

/* ---------- 商品卡片 ---------- */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.goods {
  position: relative;
  padding: 26px 24px 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: linear-gradient(180deg, rgba(255, 255, 255, .022), rgba(255, 255, 255, .008));
  transition: border-color .25s var(--ease), transform .25s var(--ease);
}

.goods:hover {
  border-color: rgba(230, 184, 119, .32);
  transform: translateY(-2px);
}

.goods-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.goods-id { font: 500 10px var(--mono); letter-spacing: .16em; color: var(--text-3); }

.tag {
  font: 600 10px var(--mono);
  letter-spacing: .1em;
  padding: 4px 9px;
  border-radius: 4px;
  border: 1px solid transparent;
}
.tag.on { color: var(--ok); border-color: rgba(78, 207, 138, .28); background: rgba(78, 207, 138, .08); }
.tag.wait { color: var(--brand); border-color: rgba(230, 184, 119, .3); background: rgba(230, 184, 119, .08); }
.tag.off { color: var(--text-3); border-color: var(--line); background: rgba(255, 255, 255, .03); }

.goods-name {
  margin: 0 0 20px;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -.01em;
  line-height: 1.35;
}

.stock { display: flex; align-items: baseline; gap: 7px; }

.stock-num {
  font: 700 34px/1 var(--mono);
  letter-spacing: -.03em;
  font-variant-numeric: tabular-nums;
  color: var(--text);
}

.stock-total { font-size: 12px; color: var(--text-3); }

.bar {
  height: 4px;
  margin: 14px 0 8px;
  border-radius: 2px;
  background: rgba(255, 255, 255, .06);
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--gold);
  transition: width .5s var(--ease);
}

.bar-note { font: 11px var(--mono); color: var(--text-3); letter-spacing: .04em; }

.goods-btn {
  display: block;
  width: 100%;
  margin-top: 22px;
  height: 44px;
  font-size: 13px;
}

.sold-out { opacity: .45; cursor: not-allowed; }

.empty { color: var(--text-3); text-align: center; padding: 72px 0; font-size: 13px; }

@media (max-width: 720px) {
  .page { padding: 40px 20px 72px; }
  .hero { flex-direction: column; align-items: flex-start; gap: 26px; padding-bottom: 30px; }
  .hero::before { top: -240px; right: -160px; }
  .hero-stat { width: 100%; text-align: left; padding: 16px 20px; }
}
</style>

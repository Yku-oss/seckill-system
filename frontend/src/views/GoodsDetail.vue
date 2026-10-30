<script setup>
// 商品详情页
// 思路：从 URL 拿 id → 页面加载时调后端 → 显示商品信息
import { ref, onMounted } from 'vue'
import {useRoute} from 'vue-router'
import { getGoodsById } from '../api.js'
import { doSeckill } from '../api.js'

const userId = ref(8080)
const buying = ref(false)
const orderResult = ref(null)

const route = useRoute()
const id = route.params.id

const loading = ref(false)
const goods = ref(null)

async function getGoodsDetail() {
  loading.value = true
  try {
    const res = await getGoodsById(id)
    goods.value = res.data.data
  } catch (err) {
    console.log(err)
  } finally {
    loading.value = false
  }
}

async function handleBuy(){
  buying.value = true
  try{
    const res = await doSeckill(userId.value,id)
    if(res.data.code === 200){
      orderResult.value = res.data.data
    }
  }catch(err) {
    console.log(err)
  }finally {
    buying.value = false
  }
}

onMounted(getGoodsDetail)
</script>

<template>
  <div class="page">
    <div v-if="loading" class="empty">正在加载中...</div>
    <div v-else-if="goods" class="card">
      <h2>{{ goods.goodsName }}</h2>
      <p><b>库存</b>{{ goods.stock }}</p>
      <p><b>开始时间</b>{{ goods.startTime }}</p>
      <p><b>结束时间</b>{{ goods.endTime }}</p>
      <button class="btn-buy" :disabled="buying" @click="handleBuy">
        {{ buying ? '抢购中...' : '立即抢购' }}
      </button>
      <div v-if="orderResult" class="result">
        抢购成功！订单号：{{ orderResult.orderNo }}
      </div>
      <router-link to="/" class="back">← 返回列表</router-link>
    </div>
    <div v-else class="empty">暂无商品</div>
  </div>
</template>
<style scoped>
.page {
  max-width: 720px;
  margin: 0 auto;
  padding: 240px 160px;
}
.card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .04);
}
.card h2 {
  margin: 0 0 16px;
  font-size: 18px;
}
.card p {
  margin: 8px 0;
  font-size: 14px;
}
.card p b {
  display: inline-block;
  width: 72px;
  color: #888;
  font-weight: normal;
}
.back {
  display: inline-block;
  margin-top: 16px;
  font-size: 14px;
}
.empty {
  color: #bbb;
  text-align: center;
  padding: 24px 0;
}
.btn-buy {
  display: block;
  width: 100%;
  margin-top: 16px;
  padding: 10px;
  border: none;
  border-radius: 6px;
  background: #1677ff;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}
.btn-buy:disabled {
  background: #a9c9ff;
  cursor: not-allowed;
}
.result {
  margin-top: 12px;
  padding: 10px;
  background: #f6ffed;
  border: 1px solid #b7eb8f;
  border-radius: 6px;
  font-size: 14px;
  color: #237804;
}
</style>
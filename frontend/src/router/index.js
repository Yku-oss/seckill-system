import { createRouter, createWebHistory } from 'vue-router'
import GoodsList from '../views/GoodsList.vue'
import GoodsDetail from '../views/GoodsDetail.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: GoodsList },
    { path: '/goods/:id', component: GoodsDetail }
  ]
})

export default router
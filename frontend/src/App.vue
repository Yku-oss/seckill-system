<script setup>
// 根组件：渲染路由页面，并给全站套一层统一的顶部品牌栏
import { toast, hideToast } from './toast'
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="topbar-inner">
        <router-link to="/" class="brand">
          <span class="brand-mark">⚡</span>
          <span class="brand-text">FLASH SALE<span class="brand-dot">.</span></span>
        </router-link>
        <div class="topbar-meta">
          <span class="live-dot"></span>
          <span>限时抢购 · 每人限购一件</span>
        </div>
      </div>
    </header>

    <main class="main">
      <router-view />
    </main>

    <footer class="footer">Flash Sale · 限时抢购</footer>

    <!-- 全局轻提示：任意组件调 showToast() 都会在这里出现 -->
    <transition name="toast-fade">
      <div v-if="toast" :class="['toast', `toast-${toast.type}`]" @click="hideToast">
        <span class="toast-icon">{{ toast.type === 'ok' ? '✓' : toast.type === 'info' ? 'i' : '!' }}</span>
        <span class="toast-msg">{{ toast.message }}</span>
      </div>
    </transition>
  </div>
</template>

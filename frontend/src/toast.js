import { ref } from 'vue'

// 全局轻提示。
// 任何组件里 import { showToast } 就能弹一条，不用层层传 props。
// App.vue 里统一渲染一个容器，所以同一时刻只会有一条。
export const toast = ref(null)

let timer = null

/**
 * @param {string} message 提示文案
 * @param {'error'|'ok'|'info'} type 类型，决定颜色和图标
 * @param {number} duration 停留毫秒数
 */
export function showToast(message, type = 'error', duration = 3200) {
  toast.value = { message, type }
  clearTimeout(timer)
  timer = setTimeout(() => {
    toast.value = null
  }, duration)
}

export function hideToast() {
  clearTimeout(timer)
  toast.value = null
}

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // 与课程里 Live Server 的地址保持一致：http://127.0.0.1:5500/
    host: '127.0.0.1',
    port: 5500,
    // 端口被占用时自动换下一个端口，而不是直接报错
    strictPort: false,
    // 本地开发时把 /api 转发到后端，模拟线上 nginx 的反向代理。
    // 这样 api.js 里可以只写相对路径 /api/seckill，开发/生产代码完全一致。
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})

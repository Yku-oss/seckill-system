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
  },
})

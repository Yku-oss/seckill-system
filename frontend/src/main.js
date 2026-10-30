import { createApp } from 'vue'  //导入组件，createApp就是创建app框架
import './style.css'  // 引用全局应用
import App from './App.vue' // APP就是一个页面框架，相当于院子的围墙，导航，两侧，底部,也是DOM的根节点
import router from './router' // 路径

// 创建vue应用实例，使用路由插件，挂载到id为#app的DOM节点上
createApp(App).use(router).mount('#app')
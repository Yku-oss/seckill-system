import axios from 'axios'
// 引入axios库， axios库是一个专门发送HTTP请求的方法库

// 定义一个BASE常量，定义了一个后端服务器的地址
//
// ⚠️ 这里必须是【相对路径】，不能写 http://localhost:8081
//    因为 localhost 指的是「打开网页的那台电脑」，而不是服务器。
//    写成绝对地址，用户在手机上打开网站时会去连他自己的 8081 端口 → 请求全部失败。
//
// 两种环境的走向：
//   开发：浏览器 → vite(5500) → [vite proxy] → 后端 8081
//   线上：浏览器 → nginx(80)  → [nginx 反代] → 后端容器 8081
//    两边都以 /api 开头，所以前端代码不用区分环境
const BASE = '/api/seckill'

// export 是一个函数导出，也就是从上面的地址里导出，后面拼接的api
// （） => 就是一个箭头函数，（）表示不接收参数， => 表示拼接
// axios.get 就是表示发送的是一个GET请求
// ⚠️ 必须用【反引号 ` 】（模板字符串）—— 用单引号 ' 的话 ${BASE} 不会被替换！
// 那么url就变成http://localhost:8081/api/seckill/goods
export const getGoods = () => axios.get(`${BASE}/goods`)

// axios.post(url, body, config) 是 axios 的 POST 写法，三个参数
// 请求体 body，这里写 null，表示不往 body 里放数据。
// 第 3 个：配置项 { params: {...} }，params 表示把参数拼到 URL 后面当查询参数。
// 最终请求为 POST http://localhost:8081/api/seckill/redis?userId=1&goodsId=100
export const doSeckill = (userId,goodsId) =>
    axios.post(`${BASE}/redis`,null,{params : {userId,goodsId}})

export const getOrder = (orderNo) =>
    axios.get(`${BASE}/order`,{params: {orderNo}})

export const getGoodsById = (id) => axios.get(`${BASE}/goods/${id}`)
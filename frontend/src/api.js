import axios from 'axios'
// 引入axios库， axios库是一个专门发送HTTP请求的方法库

// 定义一个BASE常量，定义了一个后端服务器的地址
const BASE = 'http://localhost:8081/api/seckill'

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
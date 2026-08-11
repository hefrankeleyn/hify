import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import pinia from './stores'
import './styles/index.css'

/**
 * 应用入口。
 *
 * Element Plus 目前是**全量引入**（产物 gzip 后约 450KB）。
 * 若后续要按需引入以压体积，需要新增 unplugin-auto-import / unplugin-vue-components 两个依赖，
 * 按 CLAUDE.md 12.5「需要新依赖先问」的规矩，届时先确认再改。
 */
const app = createApp(App)

app.use(pinia)
app.use(router)
// locale 指定中文，否则分页器、日期选择器等组件的内置文案是英文
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')

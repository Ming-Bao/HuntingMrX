import { createApp } from 'vue'
import App from '../App.vue'
import router from './router'
import './style.css'

// Dark by default; ThemeToggle saves 'light' to switch it off. Set before the
// app mounts so the page never flashes the wrong theme while loading.
document.documentElement.classList.toggle('dark', localStorage.getItem('theme') !== 'light')

createApp(App).use(router).mount('#app')

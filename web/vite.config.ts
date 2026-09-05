import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버 5182. /api 는 Spring Boot(8085)로 프록시하므로 CORS 설정이 필요 없다. SSE 도 프록시를 그대로 지난다.
export default defineConfig({
  plugins: [react()],
  server: { port: 5182, strictPort: true, proxy: { '/api': { target: 'http://localhost:8085', changeOrigin: true } } },
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

const frontendRoot = fileURLToPath(new URL('.', import.meta.url))
const imageRoot = fileURLToPath(new URL('../main/resources/images/', import.meta.url))
const sceneFile = fileURLToPath(new URL('../main/resources/game/scene.json', import.meta.url))
const scenes = JSON.parse(readFileSync(sceneFile, 'utf8').replace(/^\uFEFF/, '')).scene

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: {
    '@': fileURLToPath(new URL('./src', import.meta.url)),
    '@images': imageRoot,
  } },
  define: { __GAME_SCENES__: JSON.stringify(scenes) },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    fs: { allow: [frontendRoot, imageRoot] },
    proxy: { '/api': {
      target: 'http://127.0.0.1:8080',
      changeOrigin: true,
      timeout: 180_000,
      proxyTimeout: 180_000,
    } },
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, timeout: 180_000, proxyTimeout: 180_000 } },
  },
  build: { outDir: 'dist', emptyOutDir: false },
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  define: {
    global: 'globalThis',
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8999',
      '/ws': { target: 'http://localhost:8999', ws: true },
      '/test-map.json': 'http://localhost:8999',
    }
  },
  build: {
    // maplibre-gl's own chunk (~800 kB) is the expected floor for a WebGL
    // map renderer and isn't further splittable — raised past that so the
    // warning still fires if something unrelated balloons unexpectedly,
    // instead of us tuning it out and missing a real regression.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // maplibre-gl is a large, rarely-changing vendor library — split it
        // into its own chunk so a deploy that only touches our game-view
        // code doesn't force everyone to re-download all of maplibre-gl
        // too (it stays cached under its own content hash across deploys).
        manualChunks: {
          maplibre: ['maplibre-gl'],
        },
      },
    },
  },
})

import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  root: 'web',
  base: './',
  plugins: [vue()],
  build: {
    outDir: '../docs',
    emptyOutDir: true,
  },
});

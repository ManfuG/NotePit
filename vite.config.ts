import { defineConfig } from 'vite';
export default defineConfig({
  base: '/assets/',
  build: { target: 'es2022' },
  server: { host: '127.0.0.1' },
});

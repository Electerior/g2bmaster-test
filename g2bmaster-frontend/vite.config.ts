import { defineConfig, type UserConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// 개발 중에는 Spring 백엔드(기본 8080)로 /api 를 그대로 프록시한다.
// 배포는 정적 번들을 별도 호스팅하고 VITE_API_BASE_URL 로 백엔드를 가리킨다.
const BACKEND = process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080';

/**
 * `test` 는 vitest 가 읽는 키다. vitest 2.x 는 자기만의 vite 사본을 물고 있어서
 * 'vitest/config' 의 defineConfig 를 쓰면 플러그인 타입이 두 vite 사이에서 충돌한다.
 * 실행에는 아무 영향이 없는 문제라, 타입만 여기서 넓혀 두고 vite 의 defineConfig 를 쓴다.
 */
type ViteConfigWithTest = UserConfig & {
  test?: {
    environment?: string;
    globals?: boolean;
    setupFiles?: string[];
  };
};

const config: ViteConfigWithTest = {
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    // Tailscale Funnel 로 외부에 노출할 때 dev 서버가 ts.net Host 헤더를 막지 않게 허용한다.
    // (Vite 6 는 기본적으로 알 수 없는 Host 를 'Blocked request' 로 막는다.)
    // 추가 도메인은 VITE_ALLOWED_HOSTS(콤마 구분)로 넣을 수 있다.
    allowedHosts: [
      '.ts.net',
      ...(process.env.VITE_ALLOWED_HOSTS?.split(',').map((h) => h.trim()).filter(Boolean) ?? []),
    ],
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/healthz': { target: BACKEND, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
};

export default defineConfig(config);

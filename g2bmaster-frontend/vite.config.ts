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
    port: Number(process.env.VITE_DEV_PORT) || 5173,
    /*
     * **포트가 물려 있으면 옆으로 도망가지 않고 멈춘다.** vite 기본값은 5173 이 차 있으면
     * 조용히 5174 로 올라가는데, 그러면 스크립트의 헬스체크는 5173 에서 "응답 없음"을 보고
     * 화면은 아무도 모르는 포트에 떠 있게 된다 — 이 머신에서 실제로 그 상태가 됐다
     * (다른 작업 트리의 dev 서버가 5174 에 밀려 올라가 있었다).
     */
    strictPort: true,
    /*
     * 바인딩 주소. **vite 기본값(127.0.0.1)을 쓰지 않는다.**
     *
     * 여기는 원격 개발 박스이고 브라우저는 다른 기기에 있다. 루프백에 묶으면 LAN·Tailscale
     * 어느 쪽에서도 연결이 거부되는데 포트는 LISTEN 이라, 화면에서는 "포트는 열려 있는데
     * 접속이 안 된다"로 보인다 — 이 환경에서 실제로 두 번 그렇게 막혔다. 백엔드는 이미
     * 모든 인터페이스에 뜨므로 화면만 잠가 두는 것은 보안이 아니라 사고에 가깝다.
     *
     * 이 머신에서만 열려면 명시적으로 잠근다: `VITE_DEV_HOST=127.0.0.1 npm run dev`.
     */
    host: process.env.VITE_DEV_HOST || '0.0.0.0',
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

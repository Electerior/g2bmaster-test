/*
 * 진입점 — 마운트 · 쿼리 클라이언트 · 라우터 · 오류 경계.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { ErrorBoundary } from '@/components/layout/ErrorBoundary';
import { AppRouter } from '@/routes/router';
import '@/styles/global.css';

/**
 * 쿼리 기본값.
 *  - staleTime 60초: 공고 데이터는 분 단위로도 잘 안 바뀌는데 서버는 G2B 원본 API 를
 *    다시 두드리므로, 탭을 오갈 때마다 재조회하면 호출 한도만 축낸다.
 *  - retry 1: 서버가 G2B 인증/한도 오류를 503·429 로 그대로 넘겨 준다. 세 번 더 두드려도
 *    결과가 같고 한도만 더 쓴다.
 *  - refetchOnWindowFocus 끔: 사용자가 결과를 읽는 도중 창을 다녀왔다고 표가 갈리면
 *    보던 행을 잃는다. 갱신은 명시적인 '검색'으로만.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

const container = document.getElementById('root');
if (!container) throw new Error('#root 엘리먼트를 찾지 못했습니다.');

createRoot(container).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppRouter />
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);

/// <reference types="vite/client" />

// 백엔드 주소는 배포 환경마다 다르다 — 빌드 시점 주입값이라 타입을 명시해 오타를 막는다.
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /**
   * 앱 API 키. 쓰기·비용 경로(@RequireAppAuth)를 백엔드가 이 키로 가린다.
   * 비우면 헤더를 붙이지 않는다(백엔드도 키가 없으면 인증을 끈다 = 개발 모드).
   * 자세한 배선은 lib/apiClient.ts 참고.
   */
  readonly VITE_APP_API_KEY?: string;
  /**
   * AI 요약·분석 계열(bid-summary·item-summary·scan-attachments)을 부를지.
   * 'true' 일 때만 호출한다 — 백엔드 AI 저장소·첨부 파싱이 이식되기 전에는 500 이 나므로
   * 기본(미설정)은 꺼짐이다. 백엔드 g2b.ai.enabled 와 짝. 자세한 건 api/config.ts 참고.
   */
  readonly VITE_AI_ENABLED?: string;
  /**
   * 베타 접수를 받는 구글 Apps Script 웹앱 주소(docs/beta-signup.gs).
   * 있으면 /beta 랜딩이 Spring 대신 여기로 붙는다. 비우면 /api/beta/* 로 돌아간다.
   */
  readonly VITE_BETA_SHEET_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module '*.css';

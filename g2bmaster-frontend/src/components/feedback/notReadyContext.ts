/*
 * "아직 준비 중" 알림의 배선.
 *
 * 백엔드에서 작업 중인 기능(첨부 전수조사 · 유사도 확장 · 수주기회 점수)의 조작부는
 * 화면에 **그대로 남긴다**. 지우면 다음 웨이브에서 되살릴 자리를 잃고, 사용자도 그런 기능이
 * 있었다는 사실을 모르게 된다. 대신 누르면 준비 중임을 알린다.
 *
 * 컨텍스트와 훅만 이 파일에 둔다 — 컴포넌트와 같은 모듈에서 내보내면 HMR 이 그 모듈을
 * 컴포넌트 모듈로 못 보고 화면 상태를 통째로 날린다(columnClass.ts 와 같은 이유).
 */
import { createContext, useContext } from 'react';

export interface NotReadyApi {
  /** @param label 사용자가 누른 것의 이름. 문구에 그대로 들어간다. */
  notify: (label: string) => void;
}

/**
 * 기본 구현은 아무것도 하지 않는다. Provider 밖(예: /beta 랜딩)에서 훅이 불려도
 * 화면이 죽지 않아야 하기 때문이다 — 알림은 있으면 좋은 것이지 필수 배선이 아니다.
 */
export const NotReadyContext = createContext<NotReadyApi>({ notify: () => {} });

export function useNotReady(): NotReadyApi {
  return useContext(NotReadyContext);
}

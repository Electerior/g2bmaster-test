/*
 * 검색 카테고리(키워드 · 품목 · 발주기관 · 낙찰자 · 담당자 · 업로드 분석)별 화면 구성.
 * 원본 public/search-modes.js 를 그대로 옮겼다 — 함수명(searchModeLayout)을 유지해야
 * 기존 node 테스트가 그대로 산다.
 *
 * 순수 함수로 분리한 이유(원본 주석 그대로): 모드 전환은 "감추기"와 "되살리기"가 짝이어야
 * 하는데, 토글이 setSearchMode 안에 흩어져 있으면 한쪽만 고쳐져 다른 모드로 돌아갔을 때
 * 화면이 안 돌아온다.
 */

export const SEARCH_MODES = [
  'keyword',
  'item',
  'instt',
  'corp',
  'officer',
  'upload',
  'spec-search',
] as const;

export type SearchMode = (typeof SEARCH_MODES)[number];

export interface SearchModeLayout {
  mode: SearchMode;
  keywordRows: boolean;
  insttRow: boolean;
  corpRow: boolean;
  officerRow: boolean;
  uploadRow: boolean;
  /** 첨부 전수조사 체크박스 노출 여부. */
  blockingCheck: boolean;
  /** 검색 전용 UI 묶음(기간 · 검색 버튼 · 즐겨찾기 · 탭 · 결과 영역). */
  searchUi: boolean;
}

function isSearchMode(mode: string): mode is SearchMode {
  return (SEARCH_MODES as readonly string[]).includes(mode);
}

/**
 * mode -> 어떤 영역을 보일지. 업로드 분석은 올린 파일 하나를 보는 화면이라 검색 묶음이
 * 통째로 빠진다. spec-search 역시 전용 UI(검색 입력·결과·추출 폼)를 쓰므로 공통 UI를 숨긴다.
 */
export function searchModeLayout(mode: string): SearchModeLayout {
  const resolved: SearchMode = isSearchMode(mode) ? mode : 'keyword';
  const isUpload = resolved === 'upload' || resolved === 'spec-search';
  return {
    mode: resolved,
    keywordRows: resolved === 'keyword' || resolved === 'item',
    insttRow: resolved === 'instt',
    corpRow: resolved === 'corp',
    officerRow: resolved === 'officer',
    uploadRow: isUpload,
    // 첨부 전수조사 체크박스는 스캔이 의미 있는 모드에서만(기존 동작: 낙찰자·담당자 제외).
    blockingCheck: resolved === 'keyword' || resolved === 'item' || resolved === 'instt',
    searchUi: !isUpload,
  };
}

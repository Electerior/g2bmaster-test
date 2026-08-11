/*
 * 하드웨어 스펙 검색 — 별도 모듈 서버로 가는 프록시 네 개.
 * 서버가 업스트림 JSON 을 그대로 통과시키므로 응답은 느슨하게 잡되, 요청은 좁게 고정한다.
 * (원본에서 이 화면은 렌더러가 있었는데도 탭에 배선되지 않아 죽어 있었다 — spec §1 참고.)
 */
import { useMutation } from '@tanstack/react-query';
import { post } from '@/lib/apiClient';

export type SpecType = 'cpu' | 'gpu';

/*
 * 제목 의미검색(`/api/search/titles`·`/api/rank/titles`)은 폐지했다.
 * 공고 제목에는 정작 필요한 사양이 한 글자도 없고 그건 첨부 본문에만 있다 —
 * 유사도는 이제 파일 내용에 대해서만 건다.
 */

/* ─── LLM 스펙 추출 ───────────────────────────────────────────────────────── */

/**
 * 추출 방향. 둘은 입력도 출력도 다르다.
 *
 * - `datasheet` — 제조사 스펙 문서에서 **제품 하나의 값**을 뽑는다. 스펙 사전을 쌓는 쪽.
 *   `spec_type` 이 없으면 서버가 400 이다(어느 스키마로 제약할지 모른다).
 * - `requirement` — 조달 규격서에서 **요구 품목과 조건**을 뽑는다. `spec_type` 은 무시된다.
 */
export type ExtractMode = 'datasheet' | 'requirement';

export interface ExtractSpecsRequest {
  mode?: ExtractMode;
  /** `mode='datasheet'` 일 때 필수. */
  spec_type?: SpecType;
  /** 비어 있으면 서버가 400. */
  chunks: string[];
  /** 비우면 서버가 현재 로드된 모델을 쓴다. */
  model?: string;
  backend?: string;
  base_url?: string;
  /** 문서에 제품이 여럿일 때 뽑을 제품명. `datasheet` 방향에서만 쓴다. */
  target?: string;
}

/** 규격서가 요구한 조건 하나. 값이 아니라 조건이라 연산자가 붙는다. */
export interface SpecConstraint {
  attr: string;
  op: 'gte' | 'lte' | 'eq' | 'approx';
  value: number | string;
  unit?: string | null;
  raw: string;
}

export interface RequirementItem {
  category: string;
  /** 규격서에 제품명이 적혀 있을 때만 채워진다. 없으면 null. */
  name: string | null;
  named: boolean;
  qty: number;
  unit?: string | null;
  constraints: SpecConstraint[];
  allow_equivalent: boolean;
  prebuilt: boolean;
  children?: RequirementItem[];
  notes?: string[];
  evidence: string;
  /** 서버가 원문에서 찾아 붙인 좌표. `found=false` 면 원문에 없는 문장이다. */
  evidence_span?: { quote: string; offset: number; length: number; found: boolean } | null;
}

export interface ExtractSpecsResponse {
  mode?: ExtractMode;
  /** `mode='requirement'` 일 때만. */
  items?: RequirementItem[];
  is_sufficient_data?: boolean;
  [key: string]: unknown;
}

export function extractSpecs(body: ExtractSpecsRequest): Promise<ExtractSpecsResponse> {
  return post<ExtractSpecsResponse>('/api/extract/specs', body);
}

/* ─── 규격서 공고 수집 / 문서 검색 ────────────────────────────────────────── */

export interface FetchSpecNoticesRequest {
  from_date: string;
  to_date: string;
  category?: string;
  keywords?: string;
  page_no?: number;
  per_page?: number;
}

export interface SearchSpecDocumentsRequest {
  query: string;
  from_date: string;
  to_date: string;
  category?: string;
  min_score?: number;
  top_k?: number;
}

export type SpecPassthroughResponse = Record<string, unknown>;

export function fetchSpecNotices(
  body: FetchSpecNoticesRequest,
): Promise<SpecPassthroughResponse> {
  return post<SpecPassthroughResponse>('/api/specs/fetch-notices', body);
}

export function searchSpecDocuments(
  body: SearchSpecDocumentsRequest,
): Promise<SpecPassthroughResponse> {
  return post<SpecPassthroughResponse>('/api/specs/search-documents', body);
}

export function useExtractSpecs() {
  return useMutation({ mutationFn: extractSpecs });
}

export function useFetchSpecNotices() {
  return useMutation({ mutationFn: fetchSpecNotices });
}

export function useSearchSpecDocuments() {
  return useMutation({ mutationFn: searchSpecDocuments });
}

/*
 * 하드웨어 스펙 검색 — 별도 모듈 서버로 가는 프록시 네 개.
 * 서버가 업스트림 JSON 을 그대로 통과시키므로 응답은 느슨하게 잡되, 요청은 좁게 고정한다.
 * (원본에서 이 화면은 렌더러가 있었는데도 탭에 배선되지 않아 죽어 있었다 — spec §1 참고.)
 */
import { useMutation } from '@tanstack/react-query';
import { post } from '@/lib/apiClient';

export type SpecType = 'cpu' | 'gpu';

/* ─── 제목 의미 검색 ──────────────────────────────────────────────────────── */

export interface SearchTitlesRequest {
  query: string;
  type?: SpecType | 'both';
  /** 1~50, 기본 5. */
  top_k?: number;
}

export interface SearchTitlesResponse {
  /** 모듈 서버 응답을 그대로 통과시킨다 — 형태 변경은 그쪽 저장소 소관. */
  results?: unknown[];
  [key: string]: unknown;
}

export function searchTitles(body: SearchTitlesRequest): Promise<SearchTitlesResponse> {
  return post<SearchTitlesResponse>('/api/search/titles', body);
}

/* ─── LLM 스펙 추출 ───────────────────────────────────────────────────────── */

export interface ExtractSpecsRequest {
  spec_type: SpecType;
  /** 비어 있으면 서버가 400. */
  chunks: string[];
  /** 비우면 서버가 현재 로드된 모델을 쓴다. */
  model?: string;
  backend?: string;
  base_url?: string;
}

export interface ExtractSpecsResponse {
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

export function useSearchTitles() {
  return useMutation({ mutationFn: searchTitles });
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

/*
 * 단가 DB — 물품 시세 카탈로그(price_catalog). 계약 원문: 백엔드 Contract B.
 *
 * 이 계열은 나라장터(G2B)를 부르지 않는다. 백엔드가 다나와·아이티마야·에누리에서 긁어
 * 적재해 둔 시세를 조회·수정하고, AI 로 새 시세를 추가 적재(ingest)한다.
 *
 * 주의 셋이 타입에 그대로 반영돼 있다.
 *  1. `source` 어휘는 danawa/itmaya/enuri 세 개뿐이다 — 공고 검색의 출처(나라장터·국방…)와
 *     전혀 다른 집합이니 섞어 쓰면 안 된다.
 *  2. `category` 는 물품 분류(GPU·CPU·서버…) **자유 텍스트**다. 공고의 업종구분(물품·용역·
 *     공사·외자)이 아니다.
 *  3. `priceKrw` 가 null 이면 **가격 미확인**이다. 0 으로 그리면 "공짜"로 오해된다 —
 *     화면은 반드시 '미확인'으로 표시한다(priceDbRows.priceText).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { del, get, post, put } from '@/lib/apiClient';
import { toSearchParams, type QueryValue } from './types';

/* ─── 값 집합 ─────────────────────────────────────────────────────────────── */

/** 시세 출처. 공고 검색의 출처와 다른 집합이다 — 세 개뿐. */
export const PRICE_SOURCES = ['danawa', 'itmaya', 'enuri'] as const;
export type PriceSource = (typeof PRICE_SOURCES)[number];

/** 출처 표시명 — 셀렉트·배지에 그대로 쓴다. */
export const PRICE_SOURCE_LABELS: Readonly<Record<PriceSource, string>> = {
  danawa: '다나와',
  itmaya: '아이티마야',
  enuri: '에누리',
};

/* ─── 응답 행 ─────────────────────────────────────────────────────────────── */

/**
 * 카탈로그 한 줄. camelCase 로 온다(저장 공고 목록의 snake_case 와 다르다).
 * `specPreview` 는 규격 전문이 아니라 미리보기다 — 목록 응답이 전문을 싣지 않기 때문이다.
 */
export interface PriceCatalogRow {
  id: number;
  source: PriceSource;
  category: string;
  name: string;
  model: string;
  /** null = 가격 미확인. 0 이 아니다. */
  priceKrw: number | null;
  url: string | null;
  note: string | null;
  specPreview: string;
  capturedAt: string;
  updatedAt: string;
}

/* ─── 목록 ────────────────────────────────────────────────────────────────── */

export interface PriceCatalogQuery {
  source?: PriceSource | '';
  /** 물품 분류(자유 텍스트) 필터. */
  category?: string;
  /** 통합 검색어(품명·모델). */
  q?: string;
  /** 1..500, 기본은 서버가 정한다. */
  limit?: number;
}

export interface PriceCatalogListResponse {
  items: PriceCatalogRow[];
  count: number;
}

export function fetchPriceCatalog(
  query: PriceCatalogQuery = {},
): Promise<PriceCatalogListResponse> {
  const qs = toSearchParams(query as Record<string, QueryValue>).toString();
  return get<PriceCatalogListResponse>(`/api/price-catalog${qs ? `?${qs}` : ''}`);
}

/* ─── 추가(upsert) ────────────────────────────────────────────────────────── */

export interface UpsertPriceRequest {
  source: PriceSource;
  category: string;
  name: string;
  model: string;
  spec: string;
  /** null = 가격 미확인으로 담는다. */
  priceKrw: number | null;
  url: string | null;
  note: string | null;
}

export function upsertPriceRow(body: UpsertPriceRequest): Promise<{ ok: true; id: number }> {
  return post<{ ok: true; id: number }>('/api/price-catalog', body);
}

/* ─── 수정 ────────────────────────────────────────────────────────────────── */

/** 전부 선택 — 준 필드만 덮어쓴다(PATCH 의미의 PUT). */
export interface UpdatePriceRequest {
  category?: string;
  name?: string;
  model?: string;
  spec?: string;
  priceKrw?: number | null;
  url?: string | null;
  note?: string | null;
}

export function updatePriceRow(
  id: number,
  body: UpdatePriceRequest,
): Promise<{ ok: true; id: number }> {
  return put<{ ok: true; id: number }>(`/api/price-catalog/${id}`, body);
}

/* ─── 삭제 ────────────────────────────────────────────────────────────────── */

export function deletePriceRow(id: number): Promise<{ ok: true; deleted: number }> {
  return del<{ ok: true; deleted: number }>(`/api/price-catalog/${id}`);
}

/* ─── AI 시세 적재(ingest) ────────────────────────────────────────────────── */

export interface IngestPricesRequest {
  query: string;
  category?: string;
  /** 안 주면 백엔드가 세 출처 전부에서 긁는다. */
  sources?: PriceSource[];
}

export interface IngestPricesResponse {
  query: string;
  /** 총 적재 건수. */
  ingested: number;
  /** 출처별 적재 건수. */
  perSource: Record<string, number>;
  /** 출처별 실패 사유 — 빈 배열이면 전부 성공. */
  errors: string[];
  /** AI 백엔드가 안 떠 있으면 true(오류가 아니다 — HTTP 200). */
  aiUnavailable?: true;
}

export function ingestPrices(body: IngestPricesRequest): Promise<IngestPricesResponse> {
  return post<IngestPricesResponse>('/api/price-catalog/ingest', body);
}

/* ─── 시세 이력 ───────────────────────────────────────────────────────────── */

/**
 * 이력 조회 — `catalogId` 로 부르거나, 없으면 (source·name·model·spec) 조합으로 부른다.
 * 두 경로가 같은 응답을 준다.
 */
export interface PriceHistoryQuery {
  catalogId?: number;
  source?: PriceSource;
  name?: string;
  model?: string;
  spec?: string;
  limit?: number;
}

export interface PriceHistoryEntry {
  id: number;
  /** null = 그 시점에 가격 미확인. */
  priceKrw: number | null;
  url: string | null;
  note: string | null;
  capturedAt: string;
}

export interface PriceHistoryResponse {
  catalogId?: number;
  items: PriceHistoryEntry[];
  count: number;
}

export function fetchPriceHistory(query: PriceHistoryQuery): Promise<PriceHistoryResponse> {
  const qs = toSearchParams(query as Record<string, QueryValue>).toString();
  return get<PriceHistoryResponse>(`/api/price-catalog/history${qs ? `?${qs}` : ''}`);
}

/* ─── 쿼리 키 ─────────────────────────────────────────────────────────────── */

export const priceKeys = {
  all: ['price-catalog'] as const,
  list: (q: PriceCatalogQuery) => ['price-catalog', 'list', q] as const,
  history: (q: PriceHistoryQuery) => ['price-catalog', 'history', q] as const,
};

/* ─── 훅 ──────────────────────────────────────────────────────────────────── */

export function usePriceCatalog(query: PriceCatalogQuery = {}, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: priceKeys.list(query),
    queryFn: () => fetchPriceCatalog(query),
    enabled: options.enabled ?? true,
  });
}

/** 이력은 행에서 '이력'을 펼칠 때만 부른다(enabled). 목록엔 이력이 없다. */
export function usePriceHistory(query: PriceHistoryQuery, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: priceKeys.history(query),
    queryFn: () => fetchPriceHistory(query),
    enabled: options.enabled ?? true,
  });
}

/** 추가·수정·삭제·적재는 모두 목록을 무효화한다 — 바꾼 값이 목록에 곧바로 반영돼야 한다. */
export function useUpsertPriceRow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: upsertPriceRow,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceKeys.all }),
  });
}

export function useUpdatePriceRow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number } & UpdatePriceRequest) => {
      const { id, ...patch } = vars;
      return updatePriceRow(id, patch);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceKeys.all }),
  });
}

export function useDeletePriceRow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deletePriceRow(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceKeys.all }),
  });
}

export function useIngestPrices() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ingestPrices,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceKeys.all }),
  });
}

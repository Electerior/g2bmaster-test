/*
 * 저장 공고 — 사용자가 담아 둔 공고. 여기서는 G2B API 를 부르지 않는다.
 *
 * 주의: 목록 응답의 행은 서버가 SQL 결과를 그대로 내려 주므로 **snake_case** 다.
 * 다른 엔드포인트(export-jobs 등)는 camelCase 라 섞어 쓰면 조용히 undefined 가 된다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { del, get, post } from '@/lib/apiClient';
import { toSearchParams } from './types';

/** SELECT 결과 그대로. 컬럼명이 곧 필드명이다. */
export interface SavedNoticeRow {
  bid_ntce_no: string;
  bid_ntce_ord: string;
  title: string;
  instt_nm: string;
  bid_clse_dt: string;
  amount: string | number | null;
  real_estimate: string | number | null;
  price_total: string | number | null;
  note: string | null;
  saved_at: string;
  updated_at: string;
  summary_preview: string | null;
  price_row_count: number;
}

export interface SavedNoticesResponse {
  items: SavedNoticeRow[];
  totalCount: number;
  query: string;
}

export interface SavedNoticesQuery {
  /** 공백으로 나눠 최대 8개 AND 항으로 쓴다. */
  q?: string;
  /** 1~500, 기본 100. */
  limit?: number;
}

export function fetchSavedNotices(query: SavedNoticesQuery = {}): Promise<SavedNoticesResponse> {
  return get<SavedNoticesResponse>(`/api/saved-notices?${toSearchParams({ ...query }).toString()}`);
}

/** 단건 전문 — 목록은 합계·미리보기만 주므로, 저장된 가격표(price_rows)는 이걸로 받는다. */
export interface SavedNoticeDetail extends Record<string, unknown> {
  bid_ntce_no: string;
  bid_ntce_ord: string;
  title: string | null;
  price_total: number | null;
  /** 저장된 가격표 행. 저장 시 넣은 그대로(PriceRow[]). */
  price_rows: Array<Record<string, unknown>> | null;
}

export function fetchSavedNotice(bidNtceNo: string, ord = ''): Promise<SavedNoticeDetail> {
  const query = ord ? `?${toSearchParams({ ord }).toString()}` : '';
  return get<SavedNoticeDetail>(`/api/saved-notices/${encodeURIComponent(bidNtceNo)}${query}`);
}

export interface SaveNoticeRequest {
  bidNtceNo: string;
  bidNtceOrd?: string;
  title?: string;
  insttNm?: string;
  bidClseDt?: string;
  /** 서버가 숫자 외 문자를 떼어 낸다. */
  amount?: string | number;
  summary?: string;
  note?: string;
  priceRows?: Array<Record<string, unknown>>;
  priceTotal?: number;
  raw?: Record<string, unknown>;
}

export function saveNotice(body: SaveNoticeRequest): Promise<{ ok: true; bidNtceNo: string }> {
  return post<{ ok: true; bidNtceNo: string }>('/api/saved-notices', body);
}

export function deleteSavedNotice(
  bidNtceNo: string,
  ord = '',
): Promise<{ ok: true; deleted: number }> {
  const query = toSearchParams({ ord }).toString();
  return del<{ ok: true; deleted: number }>(
    `/api/saved-notices/${encodeURIComponent(bidNtceNo)}${query ? `?${query}` : ''}`,
  );
}

export const savedKeys = {
  all: ['saved-notices'] as const,
  list: (q: SavedNoticesQuery) => ['saved-notices', 'list', q] as const,
  detail: (no: string, ord: string) => ['saved-notices', 'detail', no, ord] as const,
};

export function useSavedNotices(query: SavedNoticesQuery = {}, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: savedKeys.list(query),
    queryFn: () => fetchSavedNotices(query),
    enabled: options.enabled ?? true,
  });
}

/** 저장된 가격표를 열 때만 부른다(enabled). 목록엔 합계·행수만 있다. */
export function useSavedNoticeDetail(bidNtceNo: string, ord = '', options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: savedKeys.detail(bidNtceNo, ord),
    queryFn: () => fetchSavedNotice(bidNtceNo, ord),
    enabled: options.enabled ?? true,
  });
}

/** 저장·삭제 후 목록을 무효화한다 — 담아 두고 목록으로 돌아왔을 때 없으면 버그로 보인다. */
export function useSaveNotice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: saveNotice,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: savedKeys.all }),
  });
}

export function useDeleteSavedNotice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { bidNtceNo: string; ord?: string }) =>
      deleteSavedNotice(vars.bidNtceNo, vars.ord ?? ''),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: savedKeys.all }),
  });
}

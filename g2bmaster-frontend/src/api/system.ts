/*
 * 시스템 대시보드 — 원본은 별도 정적 페이지(system.html)였다. 라우트로 편입한다.
 *
 * 여기 응답 중 일부는 SQL 행을 그대로 내려 주므로 snake_case 다(schedules · calls).
 * 실패해도 화면이 뜨도록 서버가 503 에 빈 배열을 함께 실어 주는 경로가 있어 그것도 타입에 담는다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { del, get, post } from '@/lib/apiClient';
import { toSearchParams } from './types';

/* ─── 상태 ────────────────────────────────────────────────────────────────── */

export interface SystemStatusResponse {
  db: {
    ok: boolean;
    db?: string;
    at?: string;
    error?: string;
    tables: Array<{ t: string; n: number }> | null;
  };
  loader: {
    sync: Array<{
      operation: string;
      inqry_div: string;
      last_total_count: number;
      consecutive_failures: number;
      last_success: string | null;
    }> | null;
    calls: { total: number; ok: number; failed: number; avg_ms: number } | null;
  };
  participants: { covered: number; eligible: number; rows: number } | null;
  llm:
    | { reachable: true; base: string; loadedModel: string | null; availableCount: number; workers: unknown[] }
    | { reachable: false; base?: string; loadedModel?: string | null; availableCount?: number; workers?: unknown[] };
  search: {
    modes: string[];
    fusion: string;
    sparseWeight: number;
    embedding: {
      model: string;
      multilingual: boolean;
      runtime: string;
      cached: boolean;
      note: string;
    };
  };
  platform: string;
}

export function fetchSystemStatus(): Promise<SystemStatusResponse> {
  return get<SystemStatusResponse>('/api/system/status');
}

/* ─── 오퍼레이션 / 호출 로그 / 테이블 ─────────────────────────────────────── */

export interface SystemOperationsResponse {
  counts: { curated: number; important: number; all: number };
  operations: Array<{
    op: string;
    service: string;
    table: string;
    windowed: boolean;
    inqryDivs: string[];
    scopes: string[];
  }>;
}

export function fetchSystemOperations(): Promise<SystemOperationsResponse> {
  return get<SystemOperationsResponse>('/api/system/operations');
}

export interface SystemCallRow {
  operation: string;
  service_id: string;
  result_code: string;
  result_msg: string;
  error_text: string | null;
  http_status: number | null;
  item_count: number | null;
  total_count: number | null;
  duration_ms: number | null;
  started: string;
  params_json: string | null;
}

export interface SystemCallsQuery {
  /** '1' 이면 실패한 호출만. */
  onlyFailed?: '1';
  operation?: string;
  /** 기본 100, 최대 500. */
  limit?: number;
}

/** 503 이어도 calls: [] 가 함께 온다 — 대시보드가 통째로 비지 않도록. */
export interface SystemCallsResponse {
  calls: SystemCallRow[];
  error?: string;
}

export function fetchSystemCalls(query: SystemCallsQuery = {}): Promise<SystemCallsResponse> {
  return get<SystemCallsResponse>(`/api/system/calls?${toSearchParams({ ...query }).toString()}`);
}

export interface SystemTablesResponse {
  tables: Array<{ t: string; n: number }>;
  error?: string;
}

export function fetchSystemTables(): Promise<SystemTablesResponse> {
  return get<SystemTablesResponse>('/api/system/tables');
}

/* ─── 적재(backfill) ──────────────────────────────────────────────────────── */

export interface BackfillJob {
  running: boolean;
  done: boolean;
  cancelled: boolean;
  trigger: string;
  from: string;
  to: string;
  scope: string;
  withAttachments: boolean;
  total: number;
  index: number;
  current: string;
  phase: string;
  written: number;
  fetched: number;
  calls: number;
  failures: string[];
  attachments: { total: number; done: number; blocked: number; failed: number };
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
  embeddings?: { total: number; done: number; model: string } | { error: string };
}

/** 돌고 있는 작업이 없으면 서버는 { running: false } 만 준다. */
export type BackfillStatusResponse = { running: false } | BackfillJob;

export function fetchBackfillStatus(): Promise<BackfillStatusResponse> {
  return get<BackfillStatusResponse>('/api/system/backfill');
}

export interface StartBackfillRequest {
  from: string;
  to: string;
  scope?: 'curated' | 'important' | 'all';
  only?: string | null;
  withAttachments?: boolean;
}

export function startBackfill(
  body: StartBackfillRequest,
): Promise<{ started: true; job: BackfillJob }> {
  return post<{ started: true; job: BackfillJob }>('/api/system/backfill', body);
}

export function cancelBackfill(): Promise<{ cancelling: true; note: string }> {
  return del<{ cancelling: true; note: string }>('/api/system/backfill');
}

/* ─── 예약 적재 ───────────────────────────────────────────────────────────── */

/** 응답은 SQL 행 그대로(snake_case), 요청은 camelCase 다. 서버가 그렇게 갈라 놨다. */
export interface ScheduleRow {
  id: number;
  time_of_day: string;
  scope: string;
  lookback_days: number;
  with_attachments: boolean;
  enabled: boolean;
  last_run_at: string | null;
  last_result: string | null;
}

export interface SchedulesResponse {
  schedules: ScheduleRow[];
  error?: string;
}

export function fetchSchedules(): Promise<SchedulesResponse> {
  return get<SchedulesResponse>('/api/system/schedules');
}

export interface UpsertScheduleRequest {
  /** 없으면 새로 만든다. */
  id?: number;
  /** HH:MM — 없으면 400. */
  timeOfDay: string;
  scope?: 'curated' | 'important' | 'all';
  /** 1~365, 기본 3. */
  lookbackDays?: number;
  withAttachments?: boolean;
  enabled?: boolean;
}

export function upsertSchedule(
  body: UpsertScheduleRequest,
): Promise<{ schedule: ScheduleRow }> {
  return post<{ schedule: ScheduleRow }>('/api/system/schedules', body);
}

export function deleteSchedule(id: number): Promise<{ deleted: true }> {
  return del<{ deleted: true }>(`/api/system/schedules/${id}`);
}

/* ─── 검색 방식 비교 ──────────────────────────────────────────────────────── */

export interface SearchCompareRequest {
  query: string;
  type?: string;
  /** 1~10, 기본 3. */
  top_k?: number;
}

export interface SearchCompareResponse {
  query: string;
  type: string;
  /** 방식별로 결과 배열이거나 { error } 다 — 하나가 죽어도 나머지는 보여 준다. */
  results: {
    dense: unknown[] | { error: string };
    sparse: unknown[] | { error: string };
    hybrid: unknown[] | { error: string };
  };
}

export function compareSearch(body: SearchCompareRequest): Promise<SearchCompareResponse> {
  return post<SearchCompareResponse>('/api/system/search-compare', body);
}

/* ─── 훅 ──────────────────────────────────────────────────────────────────── */

export const systemKeys = {
  all: ['system'] as const,
  status: () => ['system', 'status'] as const,
  operations: () => ['system', 'operations'] as const,
  calls: (q: SystemCallsQuery) => ['system', 'calls', q] as const,
  tables: () => ['system', 'tables'] as const,
  backfill: () => ['system', 'backfill'] as const,
  schedules: () => ['system', 'schedules'] as const,
};

export function useSystemStatus() {
  return useQuery({ queryKey: systemKeys.status(), queryFn: fetchSystemStatus });
}

export function useSystemOperations() {
  return useQuery({ queryKey: systemKeys.operations(), queryFn: fetchSystemOperations });
}

export function useSystemCalls(query: SystemCallsQuery = {}) {
  return useQuery({ queryKey: systemKeys.calls(query), queryFn: () => fetchSystemCalls(query) });
}

export function useSystemTables() {
  return useQuery({ queryKey: systemKeys.tables(), queryFn: fetchSystemTables });
}

/**
 * 적재 진행 상황은 주기 폴링이다. 돌고 있을 때만 2초 간격으로 다시 묻고,
 * 멈춰 있으면 폴링을 끈다 — 아무도 안 보는 화면에서 초당 요청을 흘리지 않기 위해.
 */
export function useBackfillStatus(pollMs = 2000) {
  return useQuery({
    queryKey: systemKeys.backfill(),
    queryFn: fetchBackfillStatus,
    refetchInterval: (query) => (query.state.data?.running ? pollMs : false),
    staleTime: 0,
  });
}

export function useSchedules() {
  return useQuery({ queryKey: systemKeys.schedules(), queryFn: fetchSchedules });
}

export function useStartBackfill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: startBackfill,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.backfill() }),
  });
}

export function useCancelBackfill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: cancelBackfill,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.backfill() }),
  });
}

export function useUpsertSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: upsertSchedule,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.schedules() }),
  });
}

export function useDeleteSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deleteSchedule,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.schedules() }),
  });
}

export function useCompareSearch() {
  return useMutation({ mutationFn: compareSearch });
}

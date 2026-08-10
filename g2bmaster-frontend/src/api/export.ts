/*
 * AI 분석 엑셀 내보내기 — 서버가 백그라운드로 돌리는 장기 작업이다.
 *
 * WebSocket/SSE 는 없다. POST 로 작업을 만들고 GET 으로 1500ms 마다 상태를 훑는다
 * (네트워크 오류 시 3000ms 로 백오프). 작업 id 는 localStorage 에 남겨 페이지를 새로 열어도
 * 폴링을 재개한다 — 사용자는 탭을 닫았다 와도 진행률이 살아 있길 기대한다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { get, post } from '@/lib/apiClient';

/** 폴링 간격(ms). 원본 app.js 의 값과 동일. */
export const EXPORT_POLL_MS = 1500;
export const EXPORT_POLL_BACKOFF_MS = 3000;
/** 취소는 action 이 'purged' 가 될 때까지 이 간격으로 재시도한다. */
export const EXPORT_CANCEL_RETRY_MS = 3000;

export interface ExportJob {
  id: string;
  entityType: string;
  status: string;
  totalCount: number;
  analyzedCount: number;
  failedCount: number;
  skippedCount: number;
  exportedCount: number;
  pendingCount: number;
  progressPercent: number;
  etaSeconds: number | null;
  resultName: string | null;
  lastError: string | null;
  createdAt: string;
  startedAt: string | null;
  updatedAt: string;
  snapshotCompletedAt: string | null;
  completedAt: string | null;
  reused: boolean;
}

/** 조회 응답에만 붙는 두 필드. downloadUrl 은 완료 전에는 null 이다. */
export interface ExportJobDetail extends ExportJob {
  downloadUrl: string | null;
  canRetryAnalysis: boolean;
}

export interface CreateExportJobRequest {
  requestKey?: string;
  entityType: string;
  sourcePath: string;
  query?: Record<string, unknown>;
  filters?: Record<string, unknown>;
}

/**
 * Idempotency-Key 는 필수에 가깝다 — 사용자가 버튼을 두 번 누르면 서버가 같은 스냅샷으로
 * LLM 분석을 두 번 돌린다(비용이 그대로 두 배).
 */
export function createExportJob(
  body: CreateExportJobRequest,
  idempotencyKey: string,
): Promise<{ job: ExportJob }> {
  return post<{ job: ExportJob }>('/api/export-jobs', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function fetchExportJob(id: string): Promise<{ job: ExportJobDetail }> {
  return get<{ job: ExportJobDetail }>(`/api/export-jobs/${encodeURIComponent(id)}`);
}

export function retryExportJob(
  id: string,
  scope?: 'analysis',
): Promise<{ job: ExportJob; action: 'initialize' | 'analysis' | 'generate' }> {
  return post(`/api/export-jobs/${encodeURIComponent(id)}/retry`, { scope });
}

/**
 * 취소 응답은 job 으로 감싸지 않는다. 진행 중인 LLM 호출은 못 죽이므로 처음에는
 * 'cancelling' 이 오고, 실제로 정리가 끝나야 'purged' 가 된다.
 */
export interface CancelExportJobResponse {
  action: 'cancelling' | 'purged';
  runningCount: number;
  deleted: { analysisHistory: number; analysisJobs: number } | null;
  stoppedCount: number;
}

export function cancelExportJob(id: string): Promise<CancelExportJobResponse> {
  return post<CancelExportJobResponse>(`/api/export-jobs/${encodeURIComponent(id)}/cancel`);
}

/** 완성 파일은 앵커로 받는다 — 서버가 res.download 로 스트림한다. */
export function exportDownloadUrl(id: string): string {
  return `/api/export-jobs/${encodeURIComponent(id)}/download`;
}

export const exportKeys = {
  all: ['export-jobs'] as const,
  detail: (id: string) => ['export-jobs', id] as const,
};

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled']);

/**
 * 작업 하나를 폴링한다. 종료 상태에 닿으면 스스로 멈춘다 —
 * 원본은 clearInterval 을 여러 경로에서 불러야 했고 한 곳이라도 빠지면 영원히 돌았다.
 */
export function useExportJob(id: string | null) {
  return useQuery({
    queryKey: exportKeys.detail(id ?? ''),
    queryFn: () => fetchExportJob(id as string),
    enabled: Boolean(id),
    refetchInterval: (query) => {
      if (query.state.error) return EXPORT_POLL_BACKOFF_MS;
      const status = query.state.data?.job.status;
      if (status && TERMINAL_STATUSES.has(status)) return false;
      return EXPORT_POLL_MS;
    },
    staleTime: 0,
  });
}

export function useCreateExportJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { body: CreateExportJobRequest; idempotencyKey: string }) =>
      createExportJob(vars.body, vars.idempotencyKey),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: exportKeys.all }),
  });
}

export function useRetryExportJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: string; scope?: 'analysis' }) => retryExportJob(vars.id, vars.scope),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: exportKeys.all }),
  });
}

export function useCancelExportJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: cancelExportJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: exportKeys.all }),
  });
}

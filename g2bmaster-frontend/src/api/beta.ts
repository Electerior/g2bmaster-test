/*
 * 베타 테스터 모집 — 랜딩(/beta)이 쓰는 공개 엔드포인트.
 *
 * 백엔드가 @RequireAppAuth 를 일부러 붙이지 않은 공개 경로다. 랜딩은 로그인 전 방문자가
 * 보는 화면이라 앱 키를 요구하면 안 된다 — 그래서 여기에는 키를 다루는 코드가 없다.
 *
 * 이식하면서 원본 랜딩의 api/client.ts(fetch 래퍼)는 버렸다. 이 저장소에는 이미
 * lib/apiClient.ts 라는 axios 인스턴스가 있고, 그쪽 인터셉터가 서버의 한국어 error 문구를
 * 그대로 ApiError.message 로 넘겨 준다 — 원본이 client.ts 로 하던 일과 같다.
 *
 * ── 목적지가 둘이다 ────────────────────────────────────────────────────────
 * VITE_BETA_SHEET_URL 이 있으면 구글 Apps Script 웹앱을 직접 부르고, 없으면 Spring 의
 * /api/beta/* 를 부른다. 지금은 앞쪽으로 돌고 있다 — 랜딩만 먼저 띄워야 하는데
 * g2bmaster-backend 배포가 아직 없기 때문이다. 백엔드가 뜨면 .env 에서 그 값을 지우면
 * 되고, 이 파일 밖의 코드는 어느 쪽이든 똑같이 동작한다(오류도 ApiError 로 통일한다).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, get, post } from '@/lib/apiClient';

/** GET /api/beta/status — 평평한 객체 (계약 §1.1). */
export interface BetaStatus {
  /** 화면에 쓰는 정원. 실제로 받는 수(remaining 의 출발점)와 다를 수 있다. */
  total: number;
  remaining: number;
  /** ISO-8601, offset 포함 */
  deadline: string;
  /** false 면 프론트가 폼을 잠근다. 접수 자체는 대기 순번으로 계속 받을 수 있다. */
  open: boolean;
}

export interface BetaSignupRequest {
  name: string;
  organization: string;
  /** 업종. 자유 입력이고 폼이 datalist 로 예시만 띄운다. */
  industry: string;
  email: string;
  phone: string;
  privacyAgreed: boolean;
  /** 스팸 봇 유인용 히든 필드. 값이 차 있으면 서버가 조용히 버린다. */
  website?: string;
}

/** POST /api/beta/signups — 단순 확인 봉투 (계약 §1.1-3). */
export interface BetaSignupResponse {
  ok: true;
  receivedAt: string;
}

/**
 * 신청 폼의 화면 상태.
 *
 * 'submitting' 이 없는 것은 빠뜨린 게 아니다. 접수는 낙관적으로 반영해서 클릭 즉시
 * 'success' 로 넘어가고 전송은 뒤에서 돈다(Apply.handleSubmit 참고). 대기 상태를 남겨 두면
 * 나중에 누군가 그 자리에 스피너를 붙이면서 낙관적 반영을 되돌리게 된다.
 */
export type SignupState =
  | { status: 'idle' }
  | { status: 'success' }
  | { status: 'error'; message: string };

export const betaKeys = {
  all: ['beta'] as const,
  status: () => ['beta', 'status'] as const,
};

const SHEET_URL = import.meta.env.VITE_BETA_SHEET_URL ?? '';

/**
 * Apps Script 웹앱 호출.
 *
 * 두 가지를 맞춰 줘야 붙는다.
 *
 * 1. Content-Type 은 반드시 text/plain 이다. application/json 을 쓰면 브라우저가 preflight
 *    (OPTIONS)를 먼저 보내는데 Apps Script 웹앱은 OPTIONS 를 처리하지 못해 CORS 로 막힌다.
 *    text/plain 은 단순 요청이라 preflight 없이 지나간다 — 본문은 그래도 JSON 문자열이고
 *    스크립트가 JSON.parse 한다.
 * 2. Apps Script 는 실패해도 HTTP 200 을 준다. 상태 코드로 성패를 가릴 수 없으므로 본문의
 *    error 필드를 보고 ApiError 를 만든다. 그래야 화면 코드가 Spring 경로와 같아진다.
 */
/** JSON 대신 HTML 이 왔거나 시간 안에 안 온 경우. 재시도 판단에만 쓰고 밖으로 새지 않는다. */
class SheetTransportError extends Error {}

/**
 * 한 번의 호출을 포기하는 시각.
 *
 * 25초 간격으로 띄엄띄엄 측정한 값(7회): 2.3 · 2.9 · 3.0 · 2.0 · 32.5(실패) · 6.4 · 9.5 초.
 * 대부분 3초 안에 끝나지만 6~9초대가 섞이고, 일곱 번에 한 번쯤은 30초를 매달려 있다가
 * HTML 오류를 뱉는다. 요청을 연달아 던지면(Apps Script 는 계정 단위로 실행을 직렬화한다)
 * 느린 쪽과 실패가 눈에 띄게 늘지만, 간격을 둬도 실패 자체는 남는다.
 *
 * 그래서 9.5초짜리 성공은 살리고 30초짜리 실패는 버리는 선에 둔다. 더 짧게 잡으면 멀쩡한
 * 응답을 죽여 헛되이 다시 부르고, 더 길게 잡으면 이미 실패한 요청을 붙잡고 있게 된다.
 */
const CALL_TIMEOUT_MS = 12_000;

async function callSheetOnce<T>(payload?: unknown): Promise<T> {
  const abort = new AbortController();
  const timer = window.setTimeout(() => abort.abort(), CALL_TIMEOUT_MS);

  let res: Response;
  try {
    res = await fetch(SHEET_URL, {
      signal: abort.signal,
      ...(payload === undefined
        ? null
        : {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain;charset=utf-8' },
            body: JSON.stringify(payload),
          }),
    });
  } catch {
    // 시간 초과는 재시도할 값어치가 있다. 진짜 연결 실패는 다시 불러도 마찬가지다.
    if (abort.signal.aborted) throw new SheetTransportError();
    // 서버 문구가 없는 유일한 경우다. apiClient 가 status 0 에 쓰는 문구와 맞춘다.
    throw new ApiError('백엔드에 연결하지 못했습니다.', 0);
  } finally {
    window.clearTimeout(timer);
  }

  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    throw new SheetTransportError();
  }

  const err = body as { error?: string; code?: string } | null;
  if (err?.error) throw new ApiError(err.error, 400, err.code);
  return body as T;
}

/** 시간 초과·HTML 응답일 때 다시 부르는 횟수(첫 시도 포함). */
const MAX_ATTEMPTS = 3;

/**
 * Apps Script 호출 + 재시도.
 *
 * /exec 는 스크립트를 실행한 뒤 결과가 담긴 googleusercontent 주소로 302 를 준다. 이
 * 두 번째 요청이 간헐적으로 실패하고(측정상 5회 중 1회꼴) 구글의 "현재 파일을 열 수
 * 없습니다" HTML 이 돌아온다. 그대로 두면 방문자도 같은 확률로 실패를 본다.
 *
 * 실패가 느리게 온다는 점이 중요하다. 타임아웃이 없으면 32초짜리 실패 뒤에 재시도가
 * 붙어 체감 대기가 분 단위로 늘어난다. 9초에서 끊고 다시 부르면 최악이 27초, 보통은
 * 9초 + 2초다.
 *
 * 그리고 실패 시점이 중요하다. 302 를 받았다는 건 스크립트가 이미 끝났다는 뜻이라,
 * 접수는 시트에 저장된 뒤일 수 있다. 재시도가 그것을 "이미 신청된 이메일"로 막아 버리면
 * 정상 신청자가 실패 화면을 본다. 그래서 접수 요청에는 requestId 를 붙이고, 스크립트가
 * 같은 requestId 를 이미 저장했으면 성공으로 돌려준다 — 재시도는 조용히 통과하고 진짜
 * 중복은 그대로 막힌다. 판단이 서버에서 이뤄지므로 프론트가 응답을 지어내지 않는다.
 */
async function callSheet<T>(payload?: unknown): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await callSheetOnce<T>(payload);
    } catch (err) {
      if (!(err instanceof SheetTransportError)) throw err;
      if (attempt >= MAX_ATTEMPTS) {
        throw new ApiError('접수 서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.', 502);
      }
    }
  }
}

export function fetchBetaStatus(): Promise<BetaStatus> {
  return SHEET_URL ? callSheet<BetaStatus>() : get<BetaStatus>('/api/beta/status');
}

/**
 * 재시도가 같은 신청임을 서버가 알아보게 하는 식별자.
 * randomUUID 는 보안 컨텍스트(https·localhost)에서만 있으므로 대비책을 둔다.
 */
function newRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function submitBetaSignup(body: BetaSignupRequest): Promise<BetaSignupResponse> {
  // Spring 경로로 돌릴 때: BetaSignupRequest.java 에 industry 가 없어 업종만 조용히 버려진다.
  // Boot 는 모르는 필드를 무시하므로 오류는 나지 않는다. 그때 DTO·엔티티에 함께 추가할 것.
  if (!SHEET_URL) return post<BetaSignupResponse>('/api/beta/signups', body);

  // 한 번의 신청에 하나만 만든다. callSheet 안의 재시도는 이 값을 그대로 다시 보낸다.
  return callSheet<BetaSignupResponse>({ ...body, requestId: newRequestId() });
}

/**
 * 잔여 자리·마감일.
 *
 * 실패해도 화면은 뜬다 — 호출한 쪽이 대체값으로 그린다. 모집 현황이 조금 틀린 것보다
 * 페이지가 안 뜨는 게 더 큰 손해다. 그래서 retry 를 끈다: 랜딩 첫 화면에서 죽은 백엔드를
 * 두 번 더 두드려 봐야 잔여 슬롯 숫자가 늦게 뜰 뿐이다.
 *
 * 잔여 수의 출처는 구글 시트의 행 개수다. 운영자가 시트에서 행을 지우면 자리가 다시
 * 열린다 — 그래서 staleTime 을 짧게 잡아 탭을 다시 열면 최신 수를 읽게 한다.
 */
export function useBetaStatus() {
  return useQuery({
    queryKey: betaKeys.status(),
    queryFn: fetchBetaStatus,
    retry: false,
    staleTime: 30_000,
  });
}

/**
 * 신청 접수.
 *
 * 성공하면 현황 쿼리를 무효화한다. 잔여 수가 시트 행 개수에서 나오므로, 무효화하지 않으면
 * 방금 신청한 사람 화면에서만 숫자가 그대로 남는다 — 자기 신청이 반영 안 된 것처럼 보인다.
 */
export function useBetaSignup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: submitBetaSignup,

    /*
     * 보내기 전에 잔여 자리를 미리 줄인다.
     *
     * 화면(Apply)이 네트워크를 기다리지 않고 성공을 그리므로, 숫자만 2~20초 뒤에 움직이면
     * 접수가 안 된 것처럼 보인다. 되돌릴 수 있도록 이전 값을 들고 나간다.
     *
     * 접수 뒤에 현황을 다시 조회하지 않는 이유도 같다 — Apps Script 는 계정 단위로 실행을
     * 직렬화해서, 방금 끝난 쓰기 뒤에 조회가 줄을 선다. 정확한 수는 다음 방문에 맞춰진다.
     */
    onMutate: () => {
      const previous = queryClient.getQueryData<BetaStatus>(betaKeys.status());
      queryClient.setQueryData<BetaStatus>(betaKeys.status(), (prev) => {
        if (!prev) return prev;
        const remaining = Math.max(0, prev.remaining - 1);
        return { ...prev, remaining, open: prev.open && remaining > 0 };
      });
      return { previous };
    },

    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(betaKeys.status(), context.previous);
    },
  });
}

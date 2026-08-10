import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
} from 'axios';

/**
 * 백엔드(Spring) 호출용 단일 axios 인스턴스.
 *
 * 기존 모놀리스는 프론트와 API가 같은 오리진이었으므로 `fetch('/api/...')` 로 충분했다.
 * 저장소를 나눈 뒤에는 배포 환경마다 백엔드 주소가 달라지므로 baseURL 을 주입한다.
 * 값이 비어 있으면 같은 오리진으로 붙는다 — dev 서버는 vite 프록시가 받아준다.
 */
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * 앱 API 키. 쓰기·비용 경로(`@RequireAppAuth`: 저장 공고, 색인 수동 적재, 분석 작업 등)는
 * 백엔드가 `Authorization: Bearer <key>` 또는 `X-API-Key` 를 요구한다
 * (`security/AppAuthInterceptor`). 키가 비어 있으면 백엔드도 인증을 끄므로(개발 모드),
 * 값이 있을 때만 헤더를 붙인다 — 빈 문자열 Bearer 를 보내면 운영에서 401 이 된다.
 *
 * 이 키는 브라우저 번들에 그대로 들어간다. 조직 내부 배포 기준의 저강도 게이트일 뿐이며,
 * 공개 서비스라면 사용자별 토큰으로 대체해야 한다(그때도 주입 지점은 이 인터셉터 하나다).
 */
const appApiKey = import.meta.env.VITE_APP_API_KEY ?? '';

export const apiClient: AxiosInstance = axios.create({
  baseURL,
  timeout: 60_000,
  headers: { 'Content-Type': 'application/json' },
});

/**
 * 앱 키 주입 — 저장소를 나누기 전(모놀리스)에는 같은 오리진 세션이라 필요 없던 것이,
 * 프론트가 별도 오리진에서 뜨면서 명시적으로 실어 보내야 하는 값이 됐다.
 * 백엔드가 두 헤더를 모두 받으므로(`AppAuthInterceptor.presentedKey`) 표준적인 Bearer 를 쓴다.
 */
if (appApiKey) {
  apiClient.interceptors.request.use((config) => {
    config.headers.set('Authorization', `Bearer ${appApiKey}`);
    return config;
  });
}

/** 백엔드가 4xx/5xx 로 내려준 오류를 화면에서 쓸 수 있는 형태로 좁힌다. */
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly details?: unknown;

  constructor(message: string, status: number, code?: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

/**
 * `missing[]` 은 베타 모듈의 검증 실패(400)가 문제 된 필드명을 실어 보내는 자리다
 * (계약 §1.1-2). 화면은 보통 error 문구만 띄우지만, 필드 하이라이트가 필요해지면
 * details 로 받아 쓴다 — 여기서 버리면 서버가 보낸 정보가 사라진다.
 */
export type ErrorBody = {
  message?: string;
  error?: string;
  code?: string;
  details?: unknown;
  missing?: string[];
};

/**
 * 화면에 띄울 오류 문구를 고른다.
 *
 * @param transportMessage axios 가 만든 문구(`Request failed with status code 500` 등).
 *                         **마지막 수단이다** — 사용자에게는 아무 정보도 주지 못한다.
 */
export function errorMessageFrom(
  status: number,
  body: ErrorBody | undefined,
  transportMessage: string,
): string {
  // 백엔드가 준 한국어 문구가 있으면 그것이 사용자 대상 계약이다(api-contract §1.1-2).
  if (body?.message) return body.message;
  if (body?.error) return body.error;

  // 응답 자체가 오지 않았다 — 네트워크·DNS·CORS.
  if (status === 0) return '백엔드에 연결하지 못했습니다.';

  /*
   * 5xx 인데 본문에 문구가 없다 = **우리 백엔드가 만든 오류가 아니다.**
   * 백엔드의 5xx 는 GlobalExceptionHandler 를 거쳐 언제나 `{error: "한국어 문구"}` 를 싣는다.
   * 그러니 이 응답은 앞단(개발 서버 프록시·리버스 프록시)이 상류에 닿지 못해 만든 것이다 —
   * vite dev 프록시는 ECONNREFUSED 를 500 으로 바꾼다.
   *
   * 예전에는 여기서 axios 문구가 그대로 나가 화면에 "Request failed with status code 500"
   * 만 떴다. 백엔드가 죽어 있다는 사실이 그 문구 어디에도 없어서, 원인을 찾으려면
   * 개발 서버 로그를 열어야 했다.
   */
  if (status >= 500) return `백엔드에 연결하지 못했습니다 (HTTP ${status}).`;

  return transportMessage;
}

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ErrorBody>) => {
    const status = error.response?.status ?? 0;
    const body = error.response?.data;
    return Promise.reject(
      new ApiError(
        errorMessageFrom(status, body, error.message),
        status,
        body?.code,
        body?.details ?? body?.missing,
      ),
    );
  },
);

/** GET 헬퍼 — 응답 본문만 돌려준다. */
export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const { data } = await apiClient.get<T>(url, config);
  return data;
}

/** POST 헬퍼 — 응답 본문만 돌려준다. */
export async function post<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const { data } = await apiClient.post<T>(url, body, config);
  return data;
}

/** PUT 헬퍼 — 응답 본문만 돌려준다. post 와 같은 모양(부분 수정 계약에 쓴다). */
export async function put<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const { data } = await apiClient.put<T>(url, body, config);
  return data;
}

/** DELETE 헬퍼 — 응답 본문만 돌려준다. */
export async function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const { data } = await apiClient.delete<T>(url, config);
  return data;
}

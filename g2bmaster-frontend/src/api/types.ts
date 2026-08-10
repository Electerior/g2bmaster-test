/*
 * 백엔드와의 공통 계약 — 저장소가 갈렸으므로 이 타입들이 사실상 인터페이스 문서다.
 * 필드명은 server.js 의 실제 res.json({...}) 에서 가져왔다. 추측한 것은 없다.
 */

/** 검색 계열 공통 봉투 (lib/search.js paginate). pageNo 0 = 전체 반환. */
export interface PageEnvelope<T> {
  items: T[];
  totalCount: number;
  pageNo: number;
  numOfRows: number;
}

/** 서버 캐시에서 나온 응답인지. 화면은 이 값으로 "방금 조회" 여부를 표시한다. */
export interface CachedFlag {
  _cached?: boolean;
}

/** 백그라운드 AI 분석 큐 상태 (lib/search-analysis.js). */
export interface AnalysisQueue {
  background: boolean;
  requested: number;
  scheduled: number;
  truncated: boolean;
}

/** 행마다 붙는 분석 상태. status 는 'pending' | 'skipped' | 'unavailable' 등 서버 문자열. */
export interface ItemAnalysisState {
  entityType?: string;
  entityId?: string;
  entityOrd?: string;
  inputHash?: string;
  promptVersion?: string;
  analysisMode?: string;
  status: string;
  jobId?: number | null;
  historyId?: number | null;
  attemptCount?: number;
  maxAttempts?: number;
  error?: string | null;
  updatedAt?: string | null;
}

/**
 * AI 실패 신호 — **HTTP 200 으로 온다**. 오류로 취급하면 안 된다.
 * LLM 이 죽어도 메타데이터 기반 요약은 돌려주는 것이 서버의 설계라, 화면은 본문을 그대로
 * 보여 주면서 "AI 없이 만든 값"이라는 표시만 덧붙여야 한다.
 *  - aiFallback + aiError : LLM 호출이 던졌다 (aiError 는 180자 컷)
 *  - aiFallback + aiTimeout : 빈 completion (aiError 없음)
 *  - aiDisabled : LLM 백엔드가 아예 안 떠 있다 (aiError 없음)
 */
export interface AiFallbackFlags {
  aiFallback?: boolean;
  aiError?: string;
  aiTimeout?: boolean;
  aiDisabled?: boolean;
}

/** 서버 오류 본문. 문구는 전부 한국어다. */
export interface ApiErrorBody {
  error: string;
}

/** 첨부 문서에서 뽑아낸 신호 — bid-summary / item-summary 응답에 덧붙는다. */
export interface DocumentSignals {
  documentTags?: string[];
  frontMatterRecipient?: { name: string; email: string; organization: string };
  legalReviewText?: string;
  bidBlockingClauses?: {
    excluded: boolean;
    reasons: string[];
    matches: Array<{ reason: string; excerpt: string }>;
  };
  legalAssessment?: LegalAssessment | null;
}

export interface LegalAssessment {
  verdict: string;
  engine: string;
  model: string;
  engineNote: string;
  summary: string;
  findings: Array<{
    id: string;
    title: string;
    category: string;
    severity: string;
    verdict: string;
    reason: string;
    counterpoint: string;
    excerpt: string;
    basis: Array<{ law: string; article: string; heading: string; point: string; verified: boolean }>;
  }>;
  citations: Array<{ law: string; article: string; heading: string; verified: boolean }>;
  disclaimer: string;
  checkedAt: string;
}

/** 첨부 파싱 요약 (server.js:1892). */
export interface FileSummary {
  listed: number;
  attempted: number;
  ok: number;
  failed: number;
  needsOcr: number;
  bytes: number;
  complete: boolean;
}

/** 어디에서 본문을 얻었는지의 추적 정보 (server.js:2511). */
export interface SourceTrace {
  source: string;
  bidNtceNo?: string;
  bidNtceSqNo?: string;
  detailBidNtceNo?: string;
  specRgstNo?: string;
  inputFileEntries?: Array<{ name: string; url: string }>;
  parsedFiles?: unknown[];
  failedFiles?: unknown[];
  relatedItems?: unknown[];
}

/** 공고 첨부 항목 — 검색 결과 행이 들고 있고, 요약 요청에 되돌려 준다. */
export interface FileEntry {
  name?: string;
  url?: string;
  [key: string]: unknown;
}

/**
 * URLSearchParams 로 넘길 값 — undefined/빈 문자열은 붙이지 않는다.
 * 원본 buildParams 가 스프레드 조건부로 하던 일을 한곳에 모은 것.
 */
export type QueryValue = string | number | boolean | undefined | null;

export function toSearchParams(input: Record<string, QueryValue>): URLSearchParams {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(input)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  return params;
}

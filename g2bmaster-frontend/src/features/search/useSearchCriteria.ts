/*
 * 검색 조건의 단일 출처 = URL search params.
 *
 * 원본은 전역 `state` 객체를 직접 변형하고, 늦게 도착한 응답이 화면을 덮어쓰는 것을
 * `searchSeq` 카운터로 막았다. 그 방식에는 두 가지 문제가 있었다.
 *  1. 검색 결과 화면을 링크로 공유할 수 없다 — 조건이 메모리에만 있으므로.
 *  2. 뒤로 가기가 앱 안의 별도 히스토리 스택(viewHistory, 각 10개)이라 브라우저와 어긋난다.
 *
 * 조건을 URL 로 올리면 두 문제가 함께 사라지고, 경쟁 조건은 TanStack Query 의 queryKey 가
 * 처리한다(조건이 바뀌면 키가 바뀌고, 옛 키의 응답은 화면에 반영되지 않는다).
 * → searchSeq 수동 취소 장치는 이식하지 않는다.
 */
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { NoticeSearchQuery } from '@/api/notices';
import {
  BUSINESS_DIVISIONS,
  NOTICE_CATEGORIES,
  NOTICE_MAX_PER_PAGE,
  NOTICE_STATES,
  isNoticeSortKey,
  type BusinessDivision,
  type NoticeCategory,
  type NoticeIndexQuery,
  type NoticeState,
} from '@/api/search';
import type { ScreenKind } from '@/domain/columns';
import { localDateString, toG2bDate } from '@/domain/format';
import { searchModeLayout, type SearchMode } from '@/domain/searchModes';

/* ─── 조건 ────────────────────────────────────────────────────────────────── */

/**
 * 구분 필터. `외자` 는 색인 검색에만 있는 값이다 — 기존 4종 API 에는 그 오퍼레이션이
 * 팬아웃 대상으로 들어 있지 않다. 그래서 값 집합은 넓히되 버튼을 그릴지는 화면이 정한다.
 */
export type BidType = '' | BusinessDivision;
export type SortDir = 'asc' | 'desc';

export interface SearchCriteria {
  /** 모두 포함 / 하나 이상 / 제외 / 파일 내. */
  andTerms: string[];
  orTerms: string[];
  notTerms: string[];
  fileKeywords: string[];
  /** 유사도 확장은 '하나 이상'(제목)과 '파일 내'(규격서 본문)에만 붙는다. */
  simOr: boolean;
  simFile: boolean;
  fromDate: string;
  toDate: string;
  pageNo: number;
  perPage: number;
  activeOnly: boolean;
  excludeBlockingClauses: boolean;
  bidType: BidType;
  /** 다른 탭에서 공고번호를 들고 넘어왔을 때. 이 값이 있으면 키워드는 무시된다. */
  crossBidNtceNo: string;
  insttNm: string;
  corpNm: string;
  brnNo: string;
  officerInsttNm: string;
  sortKey: string;
  sortDir: SortDir;
  mode: SearchMode;

  /* ── 아래는 공고 통합 검색(로컬 색인) 전용 ─────────────────────────────── */

  /** 조달 생애주기 단계. 탭이 아니라 필터다 — 넷은 같은 건의 단계이지 다른 종류가 아니다. */
  category: NoticeCategory | '';
  /** 공고 상태. 넷 모두 예외 상태이고 정상 공고는 값이 없다. */
  noticeState: NoticeState | '';
  /** 지역. 빈 값이면 필터 없음. 지역을 지정해도 전국 공고는 함께 온다(계약 §4.3). */
  region: string;
  /** 마감일 구간(`YYYY-MM-DD`). 공고일 구간(fromDate/toDate)과 다른 축이다. */
  closeFrom: string;
  closeTo: string;
  /** 추정가격 구간(원). 빈 문자열이면 필터 없음 — 0 과 '미지정'을 구분해야 한다. */
  minAmount: string;
  maxAmount: string;
  /** 세부품명번호 접두 일치. 표에서 품목번호를 눌러 좁힐 때 쓴다. */
  detailProductCode: string;
  /** 사전규격등록번호. 사전규격 → 그 규격에서 나온 입찰공고로 넘어갈 때 쓴다. */
  beforeSpecRgstNo: string;
}

/** URL 파라미터 이름. 원본 state 필드명보다 짧게 — 주소창에 그대로 노출되는 값이다. */
const PARAM = {
  and: 'and',
  or: 'or',
  not: 'not',
  file: 'file',
  simOr: 'simOr',
  simFile: 'simFile',
  from: 'from',
  to: 'to',
  page: 'page',
  perPage: 'perPage',
  activeOnly: 'active',
  blocking: 'blocking',
  bidType: 'type',
  crossNo: 'ntceNo',
  instt: 'instt',
  corp: 'corp',
  brn: 'brn',
  officer: 'officer',
  sortKey: 'sort',
  sortDir: 'dir',
  mode: 'mode',
  category: 'cat',
  noticeState: 'state',
  region: 'region',
  closeFrom: 'closeFrom',
  closeTo: 'closeTo',
  minAmount: 'min',
  maxAmount: 'max',
  detailProductCode: 'prdct',
  beforeSpecRgstNo: 'spec',
} as const;

export const DEFAULT_CRITERIA: SearchCriteria = {
  andTerms: [],
  orTerms: [],
  notTerms: [],
  fileKeywords: [],
  simOr: false,
  simFile: false,
  fromDate: '',
  toDate: '',
  pageNo: 1,
  perPage: 20,
  /*
   * 기본값이 꺼짐이다 — 예전 입찰 공고 탭에서는 켜짐이었다.
   * 통합 검색은 계획 → 사전규격 → 입찰 → 마감을 한 목록으로 훑는 화면이고, '마감 전만'을
   * 기본으로 걸면 마감 패싯이 언제나 0건이 되어 단계 필터가 고장 난 것처럼 보인다.
   * (패싯은 목록과 반드시 같은 조건으로 세야 하므로 여기만 예외를 둘 수는 없다.)
   */
  activeOnly: false,
  excludeBlockingClauses: true,
  bidType: '',
  crossBidNtceNo: '',
  insttNm: '',
  corpNm: '',
  brnNo: '',
  officerInsttNm: '',
  sortKey: '',
  sortDir: 'asc',
  mode: 'keyword',
  category: '',
  noticeState: '',
  region: '',
  closeFrom: '',
  closeTo: '',
  minAmount: '',
  maxAmount: '',
  detailProductCode: '',
  beforeSpecRgstNo: '',
};

const BID_TYPES: readonly BidType[] = ['', ...BUSINESS_DIVISIONS];

/** 모르는 값은 빈 문자열(= 필터 없음)로 떨어뜨린다 — 백엔드의 `NoticeCategory.of` 와 같은 규약. */
function parseEnum<T extends string>(raw: string | null, allowed: readonly T[]): T | '' {
  return raw && (allowed as readonly string[]).includes(raw) ? (raw as T) : '';
}

/** 숫자만 남긴다. 못 읽으면 빈 문자열 — 0 과 '미지정'을 구분해야 한다. */
function parseAmount(raw: string | null): string {
  if (!raw) return '';
  const digits = raw.replace(/[^0-9]/g, '');
  return digits;
}

function parseTerms(raw: string | null): string[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

/** 기본값이 true 인 플래그는 'false' 문자열일 때만 끈다 — 파라미터 없음 = 기본값. */
function parseBoolDefaultTrue(raw: string | null): boolean {
  return raw !== 'false';
}

function parseBoolDefaultFalse(raw: string | null): boolean {
  return raw === 'true';
}

function parsePositiveInt(raw: string | null, fallback: number): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
}

export function criteriaFromParams(params: URLSearchParams): SearchCriteria {
  const bidTypeRaw = params.get(PARAM.bidType) ?? '';
  const bidType = (BID_TYPES as readonly string[]).includes(bidTypeRaw)
    ? (bidTypeRaw as BidType)
    : '';
  return {
    andTerms: parseTerms(params.get(PARAM.and)),
    orTerms: parseTerms(params.get(PARAM.or)),
    notTerms: parseTerms(params.get(PARAM.not)),
    fileKeywords: parseTerms(params.get(PARAM.file)),
    simOr: parseBoolDefaultFalse(params.get(PARAM.simOr)),
    simFile: parseBoolDefaultFalse(params.get(PARAM.simFile)),
    fromDate: params.get(PARAM.from) ?? '',
    toDate: params.get(PARAM.to) ?? '',
    pageNo: parsePositiveInt(params.get(PARAM.page), DEFAULT_CRITERIA.pageNo),
    perPage: parsePositiveInt(params.get(PARAM.perPage), DEFAULT_CRITERIA.perPage),
    // 기본값이 꺼짐으로 바뀌었으므로 'true' 일 때만 켠다. 예전 주소의 `active=false` 도
    // 그대로 '꺼짐'으로 읽히므로 공유된 링크가 깨지지 않는다.
    activeOnly: parseBoolDefaultFalse(params.get(PARAM.activeOnly)),
    excludeBlockingClauses: parseBoolDefaultTrue(params.get(PARAM.blocking)),
    bidType,
    crossBidNtceNo: params.get(PARAM.crossNo) ?? '',
    insttNm: params.get(PARAM.instt) ?? '',
    corpNm: params.get(PARAM.corp) ?? '',
    brnNo: params.get(PARAM.brn) ?? '',
    officerInsttNm: params.get(PARAM.officer) ?? '',
    sortKey: params.get(PARAM.sortKey) ?? '',
    sortDir: params.get(PARAM.sortDir) === 'desc' ? 'desc' : 'asc',
    mode: searchModeLayout(params.get(PARAM.mode) ?? '').mode,
    category: parseEnum(params.get(PARAM.category), NOTICE_CATEGORIES),
    noticeState: parseEnum(params.get(PARAM.noticeState), NOTICE_STATES),
    region: params.get(PARAM.region) ?? '',
    closeFrom: params.get(PARAM.closeFrom) ?? '',
    closeTo: params.get(PARAM.closeTo) ?? '',
    minAmount: parseAmount(params.get(PARAM.minAmount)),
    maxAmount: parseAmount(params.get(PARAM.maxAmount)),
    detailProductCode: params.get(PARAM.detailProductCode) ?? '',
    beforeSpecRgstNo: params.get(PARAM.beforeSpecRgstNo) ?? '',
  };
}

/** 기본값과 같은 항목은 URL 에서 뺀다 — 주소가 짧아야 공유가 된다. */
export function criteriaToParams(criteria: SearchCriteria): URLSearchParams {
  const params = new URLSearchParams();
  const setIf = (key: string, value: string, isDefault: boolean) => {
    if (!isDefault && value) params.set(key, value);
  };

  setIf(PARAM.and, criteria.andTerms.join(','), criteria.andTerms.length === 0);
  setIf(PARAM.or, criteria.orTerms.join(','), criteria.orTerms.length === 0);
  setIf(PARAM.not, criteria.notTerms.join(','), criteria.notTerms.length === 0);
  setIf(PARAM.file, criteria.fileKeywords.join(','), criteria.fileKeywords.length === 0);
  if (criteria.simOr) params.set(PARAM.simOr, 'true');
  if (criteria.simFile) params.set(PARAM.simFile, 'true');
  setIf(PARAM.from, criteria.fromDate, !criteria.fromDate);
  setIf(PARAM.to, criteria.toDate, !criteria.toDate);
  if (criteria.pageNo !== DEFAULT_CRITERIA.pageNo) params.set(PARAM.page, String(criteria.pageNo));
  if (criteria.perPage !== DEFAULT_CRITERIA.perPage) {
    params.set(PARAM.perPage, String(criteria.perPage));
  }
  if (criteria.activeOnly) params.set(PARAM.activeOnly, 'true');
  if (!criteria.excludeBlockingClauses) params.set(PARAM.blocking, 'false');
  setIf(PARAM.bidType, criteria.bidType, !criteria.bidType);
  setIf(PARAM.crossNo, criteria.crossBidNtceNo, !criteria.crossBidNtceNo);
  setIf(PARAM.instt, criteria.insttNm, !criteria.insttNm);
  setIf(PARAM.corp, criteria.corpNm, !criteria.corpNm);
  setIf(PARAM.brn, criteria.brnNo, !criteria.brnNo);
  setIf(PARAM.officer, criteria.officerInsttNm, !criteria.officerInsttNm);
  if (criteria.sortKey) {
    params.set(PARAM.sortKey, criteria.sortKey);
    params.set(PARAM.sortDir, criteria.sortDir);
  }
  if (criteria.mode !== DEFAULT_CRITERIA.mode) params.set(PARAM.mode, criteria.mode);
  setIf(PARAM.category, criteria.category, !criteria.category);
  setIf(PARAM.noticeState, criteria.noticeState, !criteria.noticeState);
  setIf(PARAM.region, criteria.region, !criteria.region);
  setIf(PARAM.closeFrom, criteria.closeFrom, !criteria.closeFrom);
  setIf(PARAM.closeTo, criteria.closeTo, !criteria.closeTo);
  setIf(PARAM.minAmount, criteria.minAmount, !criteria.minAmount);
  setIf(PARAM.maxAmount, criteria.maxAmount, !criteria.maxAmount);
  setIf(PARAM.detailProductCode, criteria.detailProductCode, !criteria.detailProductCode);
  setIf(PARAM.beforeSpecRgstNo, criteria.beforeSpecRgstNo, !criteria.beforeSpecRgstNo);
  return params;
}

/* ─── 판정 헬퍼 (원본 app.js 의 순수 함수들) ──────────────────────────────── */

/**
 * 검색어가 공고번호처럼 보이면(공백 없는 숫자 위주 단일 토큰, 끝 '-01' 차수 허용) 키워드가
 * 아니라 bidNtceNo 직접조회로 라우팅. 예: 2026LKD004328889-01(D2B), R26BK01638523(나라장터).
 * 원본 looksLikeNoticeNo(app.js:1188).
 */
export function looksLikeNoticeNo(value: string): boolean {
  const t = String(value || '').trim();
  if (!t || /\s/.test(t)) return false;
  const digits = (t.match(/\d/g) || []).length;
  return t.length >= 10 && digits >= 6 && /^[0-9A-Za-z]+(-\d{1,3})?$/.test(t);
}

/**
 * 블로킹 조항 판정이 의미있는 검색인가 — 공고/발주계획/사전규격 + 낙찰자·담당자 조회 제외.
 * (낙찰자=과거 낙찰결과, 담당자=연락처 조회라 입찰 여부 판단 대상이 아님.)
 * 원본 isBlockingRelevant(app.js:1197).
 */
export function isBlockingRelevant(kind: ScreenKind, mode: SearchMode): boolean {
  if (mode === 'corp' || mode === 'officer') return false;
  return kind === 'bid-plan' || kind === 'pre-spec' || kind === 'bid-announce';
}

/**
 * 백엔드에 첨부 전수조사 표면(`POST /api/scan-attachments`)이 아직 없다.
 * 첨부 파싱(HWP/PDF/ZIP)이 미착수라 컨트롤러 자체가 없다 —
 * `g2bmaster-backend/docs/porting-status.md` 의 "첨부 파싱 ❌ 미착수".
 *
 * 검색창은 이미 이 기능을 '준비 중'으로 막아 두었지만(`SearchHeader`),
 * **막은 것은 입력이지 조건이 아니다** — 조건의 단일 출처는 URL 이라 예전에 공유된 링크나
 * 손으로 붙인 `?file=...` 이 그대로 살아 있다. 그 상태로 아래가 참이 되면 화면은
 * 없는 엔드포인트를 부르고, 404 로 스캔이 실패한 자리를 `rows = scan.data?.rows ?? []`
 * 가 **빈 표**로 그린다 — 검색은 멀쩡히 성공했는데 결과가 0건으로 보인다.
 *
 * 백엔드가 이 표면을 내면 `true` 로 되돌린다. 그때 되살아나야 하는 것은 아래 함수의
 * 원래 판정과 `buildQuery` 의 `fileScan=true`(캐시 우회) 둘이다.
 */
export const ATTACHMENT_SCAN_READY = false;

/**
 * 체크박스와 무관하게 스캔한다 — 체크 해제 시엔 제외 대신 '?'로 사유를 표시해야 하므로.
 * 원본 shouldScanAttachments(app.js:1201).
 */
export function shouldScanAttachments(criteria: SearchCriteria, kind: ScreenKind): boolean {
  if (!ATTACHMENT_SCAN_READY) return false;
  return criteria.fileKeywords.length > 0 || isBlockingRelevant(kind, criteria.mode);
}

/**
 * 조회 기간이 통째로 과거인가. 과거만 보는 검색에 '마감 전만' 을 그대로 걸면 결과가
 * 항상 0건이 된다 — 원본은 이 경우 activeOnly 를 자동으로 끈다.
 * 원본 isPastOnlyAnnounceRange(app.js:247).
 */
export function isPastOnlyAnnounceRange(criteria: SearchCriteria, kind: ScreenKind): boolean {
  return kind === 'bid-announce' && !!criteria.toDate && criteria.toDate < localDateString();
}

/* ─── buildQuery — 원본 buildParams(app.js:1205)의 타입 붙은 이식 ─────────── */

export interface BuildQueryOverrides {
  activeOnly?: boolean;
  /** 첨부 전수조사는 pageNo=0(전체) 으로 부른다. */
  pageNo?: number;
}

export function buildQuery(
  criteria: SearchCriteria,
  kind: ScreenKind,
  overrides: BuildQueryOverrides = {},
): NoticeSearchQuery {
  const itemMode = criteria.mode === 'item';
  const keywordMode = criteria.mode !== 'instt';

  // 키워드가 단일 공고번호면 번호조회로 전환(키워드는 비움).
  const soleTerm =
    keywordMode &&
    criteria.andTerms.length === 1 &&
    criteria.orTerms.length === 0 &&
    criteria.notTerms.length === 0
      ? criteria.andTerms[0]
      : '';
  const queryNo =
    criteria.crossBidNtceNo ||
    (kind === 'bid-announce' && looksLikeNoticeNo(soleTerm) ? soleTerm : '');
  const useTerms = keywordMode && !queryNo;

  const activeOnly = overrides.activeOnly ?? criteria.activeOnly;
  const useActiveOnly =
    activeOnly && kind === 'bid-announce' && !queryNo && !isPastOnlyAnnounceRange(criteria, kind);

  const pageNo = overrides.pageNo ?? criteria.pageNo;

  const query: NoticeSearchQuery = {
    andTerms: useTerms ? criteria.andTerms.join(',') : '',
    orTerms: useTerms ? criteria.orTerms.join(',') : '',
    notTerms: useTerms ? criteria.notTerms.join(',') : '',
    pageNo,
  };

  // 아래는 전부 "값이 있을 때만" 붙인다. 빈 값을 붙이면 서버가 필터로 받아들인다.
  if (criteria.perPage !== DEFAULT_CRITERIA.perPage) query.perPage = criteria.perPage;
  if (useActiveOnly) query.activeOnly = 'true';
  if (criteria.fromDate) query.fromDate = toG2bDate(criteria.fromDate);
  if (criteria.toDate) query.toDate = toG2bDate(criteria.toDate);
  if (queryNo) query.bidNtceNo = queryNo;
  if (criteria.insttNm) query.insttNm = criteria.insttNm;
  if (criteria.corpNm) query.corpNm = criteria.corpNm;
  if (criteria.bidType) query.bidType = criteria.bidType;
  if (criteria.sortKey) {
    query.sortKey = criteria.sortKey;
    query.sortDir = criteria.sortDir;
  }
  if (shouldScanAttachments(criteria, kind)) query.fileScan = 'true';
  if (itemMode) query.searchField = 'item';
  // 유사도는 '하나 이상'(제목)과 '파일 내'(규격서 본문)에만 붙는다.
  // '모두 포함'·'제외'는 정확히 걸러야 하는 조건이라 의미 확장을 넣지 않는다.
  if (criteria.simOr && criteria.orTerms.length) query.simOr = 'true';
  if (criteria.simFile && criteria.fileKeywords.length) query.simFile = 'true';

  return query;
}

/* ─── buildNoticeIndexQuery — 공고 통합 검색(로컬 색인) ────────────────────── */

/**
 * 지금 모드에서 키워드 조건이 실제로 걸리는가.
 *
 * 발주기관·낙찰자·담당자 모드에서는 키워드 입력줄이 화면에서 사라진다. 그때 URL 에 남아 있던
 * 낱말을 그대로 질의에 실으면 **보이지 않는 조건**이 결과를 깎는다 — 사용자는 왜 0건인지
 * 알 방법이 없다. 기존 buildQuery 의 `keywordMode` 판정과 같은 취지이고, 판정 근거를
 * 모드 목록이 아니라 "그 줄이 보이는가"(searchModeLayout)로 둔 것만 다르다.
 */
function keywordsApply(criteria: SearchCriteria): boolean {
  return searchModeLayout(criteria.mode).keywordRows;
}

/** 검색어가 하나라도 있는가. 정렬 기본값이 여기에 달려 있다(있으면 관련도, 없으면 최신). */
export function hasKeywords(criteria: SearchCriteria): boolean {
  if (!keywordsApply(criteria)) return false;
  return (
    criteria.andTerms.length > 0 || criteria.orTerms.length > 0 || criteria.notTerms.length > 0
  );
}

/**
 * 사용자가 정렬을 고르지 않았을 때 서버가 실제로 쓰는 정렬. 표 머리의 화살표를 그리는 데만
 * 쓴다 — 이 값을 질의에 실어 보내면 안 된다. 보내는 순간 "검색어가 생기면 관련도로 바뀐다"는
 * 서버의 규칙이 화면 쪽 고정값에 덮인다.
 */
export function effectiveIndexSort(criteria: SearchCriteria): { key: string; dir: SortDir } {
  if (criteria.sortKey && isNoticeSortKey(criteria.sortKey)) {
    return { key: criteria.sortKey, dir: criteria.sortDir };
  }
  return { key: hasKeywords(criteria) ? 'relevance' : 'created', dir: 'desc' };
}

/**
 * 조건 → `/api/search/notices` 질의.
 *
 * 기존 `buildQuery` 와 갈라 둔 이유는 이름만 다른 게 아니라 **의미가 다르기** 때문이다.
 *  - 날짜가 `YYYY-MM-DD` 다(기존은 `YYYYMMDD`). 여기서 `toG2bDate` 를 부르면 서버가
 *    조용히 파싱에 실패하고 **필터 없음**으로 넘어간다 — 400 이 아니라서 눈치채기 어렵다.
 *  - `perPage` 는 숫자만 받는다. 기존의 `'all'` 문자열은 없고 상한이 500 이다.
 *  - `bidType` → `division`, `sortKey/sortDir` → `sort/dir` 로 이름이 다르다.
 *
 * @param overrides `page` 는 첫 페이지 고정 같은 호출부 사정을 위한 것이다.
 */
export function buildNoticeIndexQuery(
  criteria: SearchCriteria,
  overrides: { page?: number; perPage?: number } = {},
): NoticeIndexQuery {
  const query: NoticeIndexQuery = {
    page: overrides.page ?? criteria.pageNo,
    perPage: Math.min(overrides.perPage ?? criteria.perPage, NOTICE_MAX_PER_PAGE),
  };

  // 아래는 전부 "값이 있을 때만". 빈 값을 붙이면 서버가 그것도 필터로 받아들인다.
  // 키워드는 그 입력줄이 화면에 있을 때만 — 위 keywordsApply 주석 참고.
  if (keywordsApply(criteria)) {
    if (criteria.andTerms.length) query.andTerms = criteria.andTerms.join(',');
    if (criteria.orTerms.length) query.orTerms = criteria.orTerms.join(',');
    if (criteria.notTerms.length) query.notTerms = criteria.notTerms.join(',');
  }

  if (criteria.category) query.category = criteria.category;
  if (criteria.noticeState) query.state = criteria.noticeState;
  if (criteria.bidType) query.division = criteria.bidType;
  if (criteria.region) query.region = criteria.region;
  if (criteria.insttNm) query.insttNm = criteria.insttNm;
  if (criteria.detailProductCode) query.detailProductCode = criteria.detailProductCode;
  if (criteria.beforeSpecRgstNo) query.beforeSpecRgstNo = criteria.beforeSpecRgstNo;

  if (criteria.fromDate) query.fromDate = criteria.fromDate;
  if (criteria.toDate) query.toDate = criteria.toDate;
  if (criteria.closeFrom) query.closeFrom = criteria.closeFrom;
  if (criteria.closeTo) query.closeTo = criteria.closeTo;
  if (criteria.activeOnly) query.activeOnly = 'true';

  if (criteria.minAmount) query.minAmount = Number(criteria.minAmount);
  if (criteria.maxAmount) query.maxAmount = Number(criteria.maxAmount);

  // 고르지 않았으면 아예 보내지 않는다 — 위 effectiveIndexSort 주석 참고.
  if (criteria.sortKey && isNoticeSortKey(criteria.sortKey)) {
    query.sort = criteria.sortKey;
    query.dir = criteria.sortDir;
  }

  return query;
}

/** 패싯 축 — 화면의 칩 줄(또는 지역 드롭다운) 하나에 대응한다. */
export type FacetAxis = 'category' | 'division' | 'state' | 'region';

/**
 * 패싯용 질의 — 목록과 같은 조건에서 페이지·정렬을 뺀 것.
 *
 * 페이지·정렬을 빼는 이유는 조건이 달라서가 아니라 캐시 때문이다. 페이지를 넘길 때마다
 * 패싯을 다시 부르면 같은 건수를 열 번 세게 된다(패싯은 조건이 같으면 결과도 같다).
 *
 * `omit` 은 **그 축 자신의 필터만** 뺀다. 서버의 패싯은 목록과 한 WHERE 를 쓰도록 설계돼
 * 있어서(BidNoticeSearchService.buildBuilder), 단계를 '입찰'로 좁힌 채 그대로 물으면
 * `GROUP BY category` 가 '입찰' 한 줄만 돌려준다 — 나머지 단계 칩이 전부 0건으로 그려진다.
 * 칩에 건수를 붙인 목적이 "누르기 전에 안다"인데 정확히 그 반대가 된다. 그래서 축마다
 * 자기 필터를 뺀 질의를 따로 보낸다. 다른 축의 조건은 그대로 남는다 — '물품'을 고른
 * 상태에서 보고 싶은 단계별 건수는 '물품 안에서의' 건수이지 색인 전체가 아니다.
 */
export function buildNoticeFacetQuery(
  criteria: SearchCriteria,
  omit?: FacetAxis,
): NoticeIndexQuery {
  const query = buildNoticeIndexQuery(criteria);
  delete query.page;
  delete query.perPage;
  delete query.sort;
  delete query.dir;
  if (omit) delete query[omit];
  return query;
}

/* ─── 훅 ──────────────────────────────────────────────────────────────────── */

export interface UseSearchCriteriaResult {
  criteria: SearchCriteria;
  /** 일부 필드만 갱신한다. 조건이 바뀌면 페이지는 1로 되돌린다(page 를 직접 준 경우 제외). */
  setCriteria: (patch: Partial<SearchCriteria>, options?: { replace?: boolean }) => void;
  /** 페이지 이동 전용 — 나머지 조건은 그대로 둔다. */
  setPage: (pageNo: number) => void;
  /** 검색 조건만 초기화한다. 라우트(화면)는 그대로. */
  reset: () => void;
}

export function useSearchCriteria(): UseSearchCriteriaResult {
  const [searchParams, setSearchParams] = useSearchParams();

  // searchParams 는 매 렌더 새 객체지만 toString() 은 안정적이라 그것으로 메모한다.
  const key = searchParams.toString();
  const criteria = useMemo(() => criteriaFromParams(new URLSearchParams(key)), [key]);

  const setCriteria = useCallback(
    (patch: Partial<SearchCriteria>, options: { replace?: boolean } = {}) => {
      const next: SearchCriteria = {
        ...criteria,
        ...patch,
        // 조건이 바뀌었는데 3페이지에 머물면 빈 화면이 뜬다 — 명시적으로 page 를 준 게
        // 아니면 1로 되돌린다.
        pageNo: patch.pageNo ?? (Object.keys(patch).length > 0 ? 1 : criteria.pageNo),
      };
      setSearchParams(criteriaToParams(next), { replace: options.replace ?? false });
    },
    [criteria, setSearchParams],
  );

  const setPage = useCallback(
    (pageNo: number) => {
      setSearchParams(criteriaToParams({ ...criteria, pageNo }));
    },
    [criteria, setSearchParams],
  );

  const reset = useCallback(() => {
    setSearchParams(new URLSearchParams());
  }, [setSearchParams]);

  return { criteria, setCriteria, setPage, reset };
}

/*
 * 행 유틸 — 원본 rowKeyForItem(app.js:3777) · collectFileEntries(4932) ·
 * isDownloadLikeUrl(4978) · firstPageUrl(4986) · parseProductDetailList(4866).
 *
 * 전부 순수 함수라 그대로 옮겼다. 원본은 이것들을 렌더 함수 사이에 흩어 두었는데, 셋 다
 * "서버가 준 한 행에서 무언가를 끄집어낸다"는 같은 일을 한다 — 한 파일에 모은다.
 */
import type { DecoratedRow, FileEntry } from '@/api';

/** 첨부 전수조사 결과가 덧붙는 행. 서버가 준 필드가 아니라 화면이 붙이는 것이다. */
export interface ScannedRow extends DecoratedRow {
  /** 파일 내 키워드가 걸린 부분의 발췌. */
  _fileExcerpt?: string;
  _matchedKeywords?: string[];
  /** 입찰 불가 조항 판정. 자동 제외를 꺼 둔 상태에서만 붙는다. */
  _blocking?: { reasons: string[]; excerpt: string };
  /** 스캔 요청/응답을 짝지을 화면 내부 id. */
  __rowId?: string;
}

/**
 * 행의 고유 키. 원본 rowKeyForItem 그대로.
 *
 * G2B 는 오퍼레이션마다 식별자 필드가 달라(공고번호·조달요청번호·규격번호…) 하나로는
 * 못 잡는다. 그래서 후보를 전부 이어 붙이고 마지막에 인덱스를 넣는다 — 같은 공고가 차수만
 * 달리해 두 번 나오는 경우까지 갈라야 하기 때문이다.
 */
export function rowKeyForItem(item: DecoratedRow, index: number, scope: string): string {
  return [
    scope,
    item.__sourceStage ?? '',
    item._type || item.bsnsDivNm || '',
    item.bidNtceNo ?? '',
    item.bidNtceSqNo || item.bidNtceOrd || '',
    item.prcrmntReqNo ?? '',
    item.orderPlanUntyNo ?? '',
    item.bfSpecRgstNo || item.prdctClfcNo || '',
    index,
  ]
    .map((value) => String(value ?? '').replace(/[|]/g, '/'))
    .join('|');
}

/** 파일 다운로드 URL 처럼 보이는가. 원본 isDownloadLikeUrl. */
export function isDownloadLikeUrl(url: unknown): boolean {
  const text = String(url ?? '').trim();
  if (!/^https?:\/\//i.test(text)) return false;
  const compact = text.replace(/\s/g, '');
  return (
    /downloadfile|filedown|untyatchfile|atchfile/i.test(compact) ||
    /\.(pdf|hwp|hwpx|doc|docx|xls|xlsx|zip)(?:[?#].*)?$/i.test(compact)
  );
}

/** 후보 키들 중 "브라우저로 열 페이지" 첫 번째. 다운로드 링크는 건너뛴다. 원본 firstPageUrl. */
export function firstPageUrl(item: DecoratedRow, keys: readonly string[]): string {
  for (const key of keys) {
    const url = String(item[key] ?? '').trim();
    if (/^https?:\/\//i.test(url) && !isDownloadLikeUrl(url)) return url;
  }
  return '';
}

/** 서랍 하단 '나라장터에서 전체 보기' 가 훑는 키 순서. 원본 openPlanModal/openSpecModal 과 동일. */
export const PAGE_URL_KEYS = [
  'prcrmntReqInfoUrl',
  'bidNtceUrl',
  'bidNtceDtlUrl',
  'ntceDtlUrl',
  'ntceUrl',
  'dtlUrl',
  'infoUrl',
  'lnkUrl',
] as const;

/**
 * 행 안에 흩어져 있는 첨부 URL 을 모은다. 원본 collectFileEntries.
 *
 * G2B 는 첨부를 `ntceSpecDocUrl1..10` / `specDocFileUrl1..10` / `atchFileUrl…` 처럼
 * 번호 붙은 필드로 흩어 준다. 필드 이름을 열거하는 대신 "http 로 시작하는 값 중 파일처럼
 * 보이는 것"을 고른다 — 새 필드가 생겨도 자동으로 걸린다.
 */
export function collectFileEntries(item: DecoratedRow | null | undefined): FileEntry[] {
  const entries: FileEntry[] = [];
  const seen = new Set<string>();
  if (!item) return entries;

  const nameFor = (key: string): string => {
    const idx = /(\d+)$/.exec(key)?.[1];
    const candidates = [
      key.replace(/Url$/i, 'Nm'),
      key.replace(/DocUrl/i, 'FileNm'),
      key.replace(/FileUrl/i, 'FileNm'),
      idx ? `ntceSpecFileNm${idx}` : '',
      idx ? `specDocFileNm${idx}` : '',
      idx ? `fileNm${idx}` : '',
      idx ? `atchFileNm${idx}` : '',
    ].filter(Boolean);
    for (const nameKey of candidates) {
      const value = item[nameKey];
      if (value) return String(value);
    }
    return key;
  };

  for (const [key, value] of Object.entries(item)) {
    const url = String(value ?? '').trim();
    if (!/^https?:\/\//i.test(url)) continue;
    const k = key.toLowerCase();
    const isFileLike =
      k.includes('fileurl') ||
      k.includes('docurl') ||
      k.includes('atch') ||
      /downloadfile|filedown|untyatchfile/i.test(url.replace(/\s/g, ''));
    // 상세 페이지 주소는 첨부가 아니다 — 다만 그 안에 download 가 들어 있으면 파일이다.
    const isPageOnly =
      k.includes('infourl') || k.includes('dtlurl') || k.includes('ntceurl') || k.includes('lnkurl');
    if (!isFileLike || (isPageOnly && !/download/i.test(url))) continue;
    if (seen.has(url)) continue;
    seen.add(url);
    entries.push({ url, name: nameFor(key) });
  }

  return entries;
}

/** 물품 상세 목록 `1^12345678^품명` 파싱. 원본 parseProductDetailList. */
export interface ProductDetail {
  seq: string;
  code: string;
  name: string;
}

export function parseProductDetailList(value: unknown): ProductDetail[] {
  const text = String(value ?? '');
  return [...text.matchAll(/(\d+)\^(\d{6,12})\^([^\][,]+)/g)]
    .map((match) => ({
      seq: match[1],
      code: match[2],
      name: String(match[3] ?? '').trim(),
    }))
    .filter((item) => item.code || item.name);
}

export function firstProductDetail(value: unknown): ProductDetail | null {
  return parseProductDetailList(value)[0] ?? null;
}

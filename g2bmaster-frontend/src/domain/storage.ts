/*
 * localStorage 접근 — 원본이 앱 전체에 흩뿌려 놓았던 getItem/setItem 을 한곳으로 모은다.
 *
 * 원본은 키 문자열과 JSON.parse 를 사용처마다 되풀이했고, 파싱 실패를 `catch {}` 로
 * 삼켰다. 저장된 값이 깨져 있어도 앱은 떠야 하므로 그 관용은 유지하되, 키와 형태는
 * 타입으로 고정한다 — 오타 난 키에 쓰면 값이 조용히 사라지는 부류의 버그를 컴파일에서 잡는다.
 *
 * 이어받는 키(spec §2): g2b_ui_state, g2b_ui_version, g2b_presets,
 * companyProfile, dealMinScore, dealActiveOnly, g2b-active-export-job.
 */

export const STORAGE_KEYS = {
  uiState: 'g2b_ui_state',
  uiVersion: 'g2b_ui_version',
  presets: 'g2b_presets',
  companyProfile: 'companyProfile',
  dealMinScore: 'dealMinScore',
  dealActiveOnly: 'dealActiveOnly',
  activeExportJob: 'g2b-active-export-job',
} as const;

export type StorageKey = (typeof STORAGE_KEYS)[keyof typeof STORAGE_KEYS];

/**
 * 저장 스키마 버전. 값이 바뀌면 g2b_ui_state 를 버린다.
 * 원본 APP_VERSION(app.js:240) 과 같은 역할 — 화면 구조가 바뀐 뒤 옛 상태를 복원하면
 * 존재하지 않는 탭으로 복귀해 빈 화면이 뜬다.
 */
export const UI_STATE_VERSION = '20260727-1';

/** 원본 saveUiState(app.js:400) 가 담던 필드 그대로. */
export interface PersistedUiState {
  tab?: string;
  searchMode?: string;
  andTerms?: string[];
  orTerms?: string[];
  notTerms?: string[];
  fileKeywords?: string[];
  excludeBlockingClauses?: boolean;
  bidType?: string;
  insttNm?: string;
  corpNm?: string;
  brnNo?: string;
  officerInsttNm?: string;
  fromDate?: string;
  toDate?: string;
  trendDayLimit?: string;
  trendDaySort?: string;
}

/** 즐겨찾기 프리셋 — 원본 savePreset(app.js:1014). */
export interface SearchPreset {
  name: string;
  andTerms: string[];
  orTerms: string[];
  notTerms: string[];
}

/* ─── 원시 접근자. SSR·시크릿 모드에서 localStorage 가 던질 수 있어 전부 감싼다. ───── */

function readRaw(key: StorageKey): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeRaw(key: StorageKey, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* 용량 초과·비활성화 — 저장 실패로 앱이 멈추면 안 된다. */
  }
}

export function removeStored(key: StorageKey): void {
  try {
    window.localStorage.removeItem(key);
  } catch {
    /* 위와 같음 */
  }
}

function readJson<T>(key: StorageKey, fallback: T): T {
  const raw = readRaw(key);
  if (raw == null) return fallback;
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed == null ? fallback : (parsed as T);
  } catch {
    return fallback;
  }
}

function writeJson(key: StorageKey, value: unknown): void {
  writeRaw(key, JSON.stringify(value));
}

/* ─── 키별 타입 붙은 접근자 ────────────────────────────────────────────────── */

export function loadUiState(): PersistedUiState | null {
  return readJson<PersistedUiState | null>(STORAGE_KEYS.uiState, null);
}

export function saveUiState(state: PersistedUiState): void {
  writeJson(STORAGE_KEYS.uiState, state);
}

/**
 * 버전이 다르면 저장된 UI 상태를 버린다. 원본 resetUiStateForCurrentVersion(app.js:253).
 * 반환값은 "버렸는가" — 호출부가 기본값으로 시작할지 판단한다.
 */
export function ensureUiStateVersion(): boolean {
  if (readRaw(STORAGE_KEYS.uiVersion) === UI_STATE_VERSION) return false;
  removeStored(STORAGE_KEYS.uiState);
  writeRaw(STORAGE_KEYS.uiVersion, UI_STATE_VERSION);
  return true;
}

export function loadPresets(): SearchPreset[] {
  const list = readJson<SearchPreset[]>(STORAGE_KEYS.presets, []);
  return Array.isArray(list) ? list : [];
}

export function savePresets(list: SearchPreset[]): void {
  writeJson(STORAGE_KEYS.presets, list);
}

/** 회사 소개 — 모든 AI 요청 payload 가 이 값을 읽는다. */
export function loadCompanyProfile(): string {
  return readRaw(STORAGE_KEYS.companyProfile) ?? '';
}

export function saveCompanyProfile(text: string): void {
  writeRaw(STORAGE_KEYS.companyProfile, text);
}

/** 수주 데스크 점수 게이트. 원본은 0~100 으로 클램프한다(app.js:562). */
export function loadDealMinScore(): number {
  return Math.max(0, Math.min(100, Number(readRaw(STORAGE_KEYS.dealMinScore)) || 45));
}

export function saveDealMinScore(score: number): void {
  writeRaw(STORAGE_KEYS.dealMinScore, String(score));
}

/** 미마감만 표시 — 원본은 문자열 'false' 일 때만 끈다(기본 on). */
export function loadDealActiveOnly(): boolean {
  return readRaw(STORAGE_KEYS.dealActiveOnly) !== 'false';
}

export function saveDealActiveOnly(activeOnly: boolean): void {
  writeRaw(STORAGE_KEYS.dealActiveOnly, String(activeOnly));
}

/** 진행 중인 엑셀 내보내기 작업 id — 페이지를 새로 열어도 폴링을 재개하기 위한 것. */
export function loadActiveExportJobId(): string | null {
  return readRaw(STORAGE_KEYS.activeExportJob);
}

export function saveActiveExportJobId(id: string): void {
  writeRaw(STORAGE_KEYS.activeExportJob, id);
}

export function clearActiveExportJobId(): void {
  removeStored(STORAGE_KEYS.activeExportJob);
}

/**
 * 전체 리셋 — 검색 조건 · 즐겨찾기를 지우고 회사 프로필은 남긴다.
 * 원본 헤더의 '전체 리셋' 버튼(app.js:472) 과 같은 범위다. 프로필을 남기는 이유는
 * 그것만 사용자가 직접 타이핑한 자산이기 때문.
 */
export function fullReset(): void {
  removeStored(STORAGE_KEYS.uiState);
  removeStored(STORAGE_KEYS.presets);
  writeRaw(STORAGE_KEYS.uiVersion, UI_STATE_VERSION);
}

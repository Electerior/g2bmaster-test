/*
 * 페이지당 표시 건수 선택지.
 *
 * 컴포넌트 파일에서 분리한 이유는 columnClass.ts 와 같다 — 상수를 컴포넌트와 같은 모듈에서
 * 내보내면 HMR 이 그 모듈을 컴포넌트 모듈로 못 보고 화면 상태를 통째로 날린다.
 */

export interface PerPageOption {
  value: number;
  label: string;
}

/** '전체' 는 팬아웃 검색 서버가 문자열 'all' 로 받는다(server.js:1015). */
export const PER_PAGE_ALL = 99999;

/** 기존 4탭(팬아웃 검색). */
export const PER_PAGE_OPTIONS: readonly PerPageOption[] = [
  { value: 20, label: '20개' },
  { value: 50, label: '50개' },
  { value: 100, label: '100개' },
  { value: PER_PAGE_ALL, label: '전체' },
];

/**
 * 공고 통합 검색(로컬 색인)용. 한 페이지 상한이 500이고 '전체'가 없다 — 그 위로는 응답이
 * 수십 MB가 되어 브라우저가 먼저 죽는다는 것이 서버 쪽 이유다. '전체'를 남겨 두면 눌러도
 * 500건만 나오면서 화면은 전체라고 우기게 된다.
 */
export const INDEX_PER_PAGE_OPTIONS: readonly PerPageOption[] = [
  { value: 20, label: '20개' },
  { value: 50, label: '50개' },
  { value: 100, label: '100개' },
  { value: 200, label: '200개' },
  { value: 500, label: '500개' },
];

/**
 * 선택지에 없는 값을 있는 값으로 끌어온다.
 *
 * 조건은 URL 이라 선택지 밖의 숫자가 그냥 들어온다 — 예전 '전체'(99999)를 담아 둔 링크,
 * 입찰 결과 탭에서 '전체'를 고른 뒤 공고 검색 탭으로 넘어오는 경우가 그렇다. 그대로 두면
 * 제어된 &lt;select&gt; 가 어느 옵션과도 안 맞아 React 가 첫 옵션('20개')을 보여 주는데,
 * 실제로는 500건을 받아 온다. 게다가 그 상태에서 '20개'를 다시 고르면 change 가 안 나서
 * 20건으로 돌아갈 수도 없다. 표시와 실제를 하나로 맞춘다.
 *
 * @returns 값 이하의 가장 큰 선택지. 전부보다 작으면 가장 작은 선택지.
 */
export function snapPerPage(
  value: number,
  options: readonly PerPageOption[] = INDEX_PER_PAGE_OPTIONS,
): number {
  const sorted = [...options].sort((a, b) => a.value - b.value);
  const fallback = sorted[0].value;
  if (!Number.isFinite(value) || value < fallback) return fallback;
  let picked = fallback;
  for (const option of sorted) {
    if (option.value <= value) picked = option.value;
  }
  return picked;
}

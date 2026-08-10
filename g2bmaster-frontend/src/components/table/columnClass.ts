/*
 * 컬럼 키 → 클래스명. 원본 renderTable 안의 colClass(app.js:3919) 와 같은 치환.
 * 컬럼 폭은 table.css 가 이 클래스로 잡는다(.col-bidNtceNm 등).
 *
 * 컴포넌트 파일에서 분리한 이유: 함수를 컴포넌트와 같은 모듈에서 내보내면 HMR 이 그 모듈을
 * 컴포넌트 모듈로 못 보고 화면 상태를 통째로 날린다(react-refresh/only-export-components).
 */
export function columnClass(key: string): string {
  return `col-${String(key || '').replace(/[^a-zA-Z0-9_-]/g, '-')}`;
}

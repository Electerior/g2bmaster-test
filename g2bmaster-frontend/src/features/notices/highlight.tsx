/*
 * 발췌 안의 키워드 강조 — 원본 renderTable 의 발췌 행 조립(app.js:3950).
 *
 * 원본은 `escHtml(excerpt)` 한 뒤 키워드마다 정규식 replace 로 `<mark>` 을 끼워 넣었다.
 * 이스케이프 **뒤에** 태그를 넣는 순서라 동작은 했지만, 키워드가 `&amp;` 같은 이스케이프
 * 결과와 겹치면 엉뚱한 자리가 잡혔다. JSX 에서는 문자열을 자르고 조각을 배열로 돌려주므로
 * 그 문제가 없다.
 */
import type { ReactNode } from 'react';

/** 정규식 메타문자 이스케이프 — 키워드는 사용자가 친 문자열이다. */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function highlightKeywords(text: string, keywords: readonly string[]): ReactNode[] {
  const valid = keywords.filter(Boolean);
  if (!valid.length) return [text];

  const pattern = new RegExp(`(${valid.map(escapeRegExp).join('|')})`, 'gi');
  const parts = text.split(pattern);
  return parts.map((part, i) =>
    // split 의 홀수 인덱스가 캡처 그룹(= 일치한 키워드)이다.
    i % 2 === 1 ? (
      <mark key={i} className="file-kw-mark">
        {part}
      </mark>
    ) : (
      part
    ),
  );
}

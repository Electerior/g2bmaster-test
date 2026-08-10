/*
 * 접이식 블록 — 원본 .raw-details(`<details><summary>…`).
 *
 * native <details> 를 그대로 쓴다. 열림 상태를 React state 로 들고 있을 이유가 없다:
 * 접었다 펴는 것 말고는 아무 일도 하지 않고, 브라우저가 키보드·스크린리더까지 처리해 준다.
 * 원본도 같은 이유로 JS 이벤트를 붙이지 않았다.
 */
import { Fragment, type ReactNode } from 'react';
import '@/components/overlay/overlay.css';

interface CollapsibleProps {
  summary: ReactNode;
  children: ReactNode;
  /** 원본 API 필드 덤프처럼 눈에 덜 띄어야 하는 것. */
  quiet?: boolean;
  defaultOpen?: boolean;
}

export function Collapsible({
  summary,
  children,
  quiet = false,
  defaultOpen = false,
}: CollapsibleProps) {
  return (
    <details className={quiet ? 'collapsible quiet' : 'collapsible'} open={defaultOpen}>
      <summary>{summary}</summary>
      {children}
    </details>
  );
}

interface RawFieldGridProps {
  /** 원본 API 응답을 그대로 늘어놓는다. `__` 로 시작하는 내부 필드는 뺀다. */
  fields: Record<string, unknown>;
}

export function RawFieldGrid({ fields }: RawFieldGridProps) {
  const rows = Object.entries(fields).filter(
    ([key, value]) => !key.startsWith('__') && value !== undefined && value !== null && value !== '',
  );
  return (
    <div className="raw-grid">
      {rows.map(([key, value]) => (
        <Fragment key={key}>
          <div className="raw-key">{key}</div>
          <div className="raw-val">{String(value)}</div>
        </Fragment>
      ))}
    </div>
  );
}

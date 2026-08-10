/*
 * 필드 세트 — 원본 dealSet/dsVal(app.js:2516~2530).
 *
 * 명시적인 값(큼)을 위에, 덜 명시적인 값(작음)을 아래에 묶는다. 저장 공고 카드와
 * AI 수주 데스크 카드가 같은 격자를 쓰므로 공용으로 둔다.
 *
 * `pending` 이 요점이다: 값이 아직 없지만 **곧 채워질 자리**와 그냥 없는 자리를 구분한다.
 * 둘 다 '-' 로 그리면 사용자는 분석이 끝난 것으로 오해한다.
 */
import type { ReactNode } from 'react';
import './fieldset.css';

export interface FieldValue {
  label: string;
  value: ReactNode;
  /** 값이 비었을 때 '규격서 확인 후 표시' 로 보일지. */
  pending?: boolean;
}

interface FieldSetProps {
  primary: FieldValue;
  secondary?: FieldValue;
  wide?: boolean;
  hero?: boolean;
}

function renderValue({ value, pending }: FieldValue): ReactNode {
  const filled = value != null && String(value).trim() !== '';
  if (filled) return value;
  return pending ? (
    <span className="ds-pending">규격서 확인 후 표시</span>
  ) : (
    <span className="ds-muted">-</span>
  );
}

export function FieldSet({ primary, secondary, wide = false, hero = false }: FieldSetProps) {
  const classes = ['ds-set', wide ? 'ds-wide' : '', hero ? 'ds-hero-item' : '']
    .filter(Boolean)
    .join(' ');
  return (
    <div className={classes}>
      <div className="ds-pri">
        <span className="ds-p-l">{primary.label}</span>
        <span className="ds-p-v">{renderValue(primary)}</span>
      </div>
      {secondary ? (
        <div className="ds-sec">
          <span className="ds-s-l">{secondary.label}</span>{' '}
          <span className="ds-s-v">{renderValue(secondary)}</span>
        </div>
      ) : null}
    </div>
  );
}

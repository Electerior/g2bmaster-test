/*
 * 키워드 칩 입력 — 원본 addTag/removeTag/renderTags/commitTagInput(app.js:1104~1143).
 *
 * 확정 규칙은 원본 그대로다.
 *  - Enter  : 확정하고 검색까지 실행 (입력이 비어 있으면 검색만)
 *  - ','    : 확정만 (검색은 안 함) — 여러 개를 이어서 칠 수 있어야 한다
 *  - Backspace(빈 상태) : 마지막 칩 제거
 *  - blur / change      : 확정 (검색은 안 함) — 타이핑해 놓고 버튼을 누르는 사람이 많다
 *
 * 원본이 `onclick="removeTag('and','…')"` 로 문자열 안에 값을 끼워 넣던 부분이 사라졌다.
 * 그 자리는 따옴표가 든 키워드에서 조용히 깨지던 곳이다.
 */
import { useState, type KeyboardEvent } from 'react';
import './search.css';

export type TagKind = 'and' | 'or' | 'not' | 'file';

interface TagInputProps {
  kind: TagKind;
  /** 왼쪽 배지 문구 — 모두 포함 / 하나 이상 / 제외 / 파일 내. */
  badgeLabel: string;
  placeholder: string;
  values: readonly string[];
  onChange: (values: string[]) => void;
  /**
   * Enter — 검색. 이때 새로 확정된 칩이 있으면 그 배열을 함께 넘긴다.
   *
   * onChange 와 나눠 부르면 안 된다: 둘 다 같은 렌더의 조건 객체를 읽어 URL 을 새로 쓰므로,
   * 나중 호출이 먼저 호출의 결과(방금 넣은 칩)를 덮어쓴다. 한 번에 넘겨 호출부가 하나의
   * 갱신으로 합치게 한다.
   */
  onSubmit: (committed: string[] | null) => void;
  /**
   * 유사도 토글이 붙는 행(하나 이상 · 파일 내)만 준다.
   * `notReady` 가 붙으면 토글은 그려지되 조건을 바꾸지 않고 알림만 띄운다.
   */
  similarity?: {
    checked: boolean;
    onChange: (checked: boolean) => void;
    title: string;
    notReady?: NotReadyMark;
  };
  /**
   * 백엔드가 아직 이 조건을 받지 못할 때. 입력은 화면에 **남기되** 조건으로는 나가지 않고,
   * 손대는 순간 준비 중임을 알린다. 지워 버리면 다음 웨이브에서 되살릴 자리를 잃는다.
   */
  notReady?: NotReadyMark;
}

export interface NotReadyMark {
  label: string;
  notify: (label: string) => void;
}

export function TagInput({
  kind,
  badgeLabel,
  placeholder,
  values,
  onChange,
  onSubmit,
  similarity,
  notReady,
}: TagInputProps) {
  const [draft, setDraft] = useState('');

  /** 원본 addTag: 앞뒤 공백과 콤마를 떼고, 이미 있으면 넣지 않는다. */
  const commit = (raw: string): string[] | null => {
    const value = raw.trim().replace(/,+/g, '');
    if (!value || values.includes(value)) return null;
    return [...values, value];
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    const hasDraft = draft.trim().length > 0;

    if (e.key === 'Enter') {
      e.preventDefault();
      if (!hasDraft) {
        onSubmit(null);
        return;
      }
      const next = commit(draft);
      setDraft('');
      onSubmit(next);
      return;
    }
    if (e.key === ',' && hasDraft) {
      e.preventDefault();
      const next = commit(draft);
      setDraft('');
      if (next) onChange(next);
      return;
    }
    if (e.key === 'Backspace' && !draft && values.length > 0) {
      onChange(values.slice(0, -1));
    }
  };

  const handleBlur = () => {
    if (!draft.trim()) return;
    const next = commit(draft);
    setDraft('');
    if (next) onChange(next);
  };

  if (notReady) {
    return (
      <div className="bool-row">
        <span className={`bool-badge badge-${kind}`}>{badgeLabel}</span>
        <button
          type="button"
          className="tag-wrap not-ready-control"
          onClick={() => notReady.notify(notReady.label)}
          title={`${notReady.label}: 백엔드에서 작업 중입니다`}
        >
          <span className="tag-text-disabled">{placeholder}</span>
        </button>
        {similarity ? (
          <span className="sim-toggle not-ready-control" title={similarity.title}>
            <input type="checkbox" checked={false} readOnly tabIndex={-1} aria-hidden="true" /> 유사도
          </span>
        ) : null}
      </div>
    );
  }

  return (
    <div className="bool-row">
      <span className={`bool-badge badge-${kind}`}>{badgeLabel}</span>
      {/* 칩 사이 빈 곳을 눌러도 입력으로 들어가야 한다 — 원본 wrap 의 click → focus. */}
      <label className="tag-wrap">
        <span className="sr-only">{badgeLabel} 키워드</span>
        {values.map((value) => (
          <span key={value} className={`qtag qtag-${kind}`}>
            {value}
            <button
              type="button"
              aria-label={`${value} 제거`}
              onClick={() => onChange(values.filter((v) => v !== value))}
            >
              ×
            </button>
          </span>
        ))}
        <input
          className="tag-text"
          value={draft}
          placeholder={placeholder}
          autoComplete="off"
          enterKeyHint="search"
          inputMode="search"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
        />
      </label>
      {similarity ? (
        <label
          className={similarity.notReady ? 'sim-toggle not-ready-control' : 'sim-toggle'}
          title={
            similarity.notReady
              ? `${similarity.notReady.label}: 백엔드에서 작업 중입니다`
              : similarity.title
          }
        >
          <input
            type="checkbox"
            // 준비 중일 때는 상태를 바꾸지 않는다 — 켜 두면 다음 검색이 조용히 달라진 것처럼
            // 보이는데 실제로는 아무것도 달라지지 않는다.
            checked={similarity.notReady ? false : similarity.checked}
            readOnly={Boolean(similarity.notReady)}
            onChange={(e) => {
              if (similarity.notReady) {
                similarity.notReady.notify(similarity.notReady.label);
                return;
              }
              similarity.onChange(e.target.checked);
            }}
          />{' '}
          유사도
        </label>
      ) : null}
    </div>
  );
}

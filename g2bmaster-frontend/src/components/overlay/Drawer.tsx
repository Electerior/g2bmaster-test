/*
 * 우측 슬라이드인 서랍의 껍데기.
 *
 * 원본은 서랍이 DOM 에 늘 존재하고 .hidden 을 붙였다 뗐다 했다(app.js:4252). 그래서 서랍
 * 내용이 닫힌 뒤에도 남아 있었고, 다음에 열 때 이전 공고의 요약이 잠깐 보였다. React 에서는
 * 열릴 때만 마운트한다 — 그 잔상 문제가 구조적으로 사라진다.
 *
 * 껍데기가 책임지는 것: 오버레이, Esc, body 스크롤 잠금, 그리고 **여닫기 애니메이션**.
 * 닫힘은 즉시 언마운트하지 않고 퇴장 애니메이션을 한 번 재생한 뒤 onClose 를 부른다 —
 * 스크림·Esc·X 버튼 어느 경로로 닫아도 같은 애니메이션을 타도록 requestClose 하나로 모은다.
 */
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import './overlay.css';

/** 서랍을 애니메이션과 함께 닫는 함수. DrawerHeader 의 X 버튼이 이걸 쓴다. */
const DrawerCloseContext = createContext<() => void>(() => {});

/** 퇴장 애니메이션 길이. overlay.css 의 drawer-slide-out 과 반드시 같아야 한다. */
const EXIT_MS = 260;

interface DrawerProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  /** 스크린리더용 이름. 보통 공고명. */
  label?: string;
}

export function Drawer({ open, onClose, children, label = '상세 정보' }: DrawerProps) {
  // 닫힘 요청이 들어오면 곧장 언마운트하지 않고 퇴장 애니메이션을 재생하는 동안 true.
  const [closing, setClosing] = useState(false);

  const requestClose = useCallback(() => {
    setClosing((already) => {
      if (already) return already; // 이미 닫는 중이면 중복 타이머를 걸지 않는다.
      window.setTimeout(() => {
        setClosing(false);
        onClose();
      }, EXIT_MS);
      return true;
    });
  }, [onClose]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') requestClose();
    };
    document.addEventListener('keydown', onKeyDown);
    // 서랍이 뜬 채로 뒤 페이지가 스크롤되면 서랍만 제자리에 남아 배경이 흘러간다.
    const previousOverflow = document.body.style.overflow;
    const previousPaddingRight = document.body.style.paddingRight;
    // overflow:hidden 으로 세로 스크롤바가 사라지면 그 폭(≈15px)만큼 본문이 넓어져 화면이
    // 왼쪽으로 툭 밀린다 — 사라지는 스크롤바 폭만큼 padding 으로 채워 레이아웃을 고정한다.
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    document.body.style.overflow = 'hidden';
    if (scrollbarWidth > 0) {
      document.body.style.paddingRight = `${scrollbarWidth}px`;
    }
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      document.body.style.paddingRight = previousPaddingRight;
    };
  }, [open, requestClose]);

  if (!open) return null;

  return (
    <DrawerCloseContext.Provider value={requestClose}>
      <button
        type="button"
        className={closing ? 'overlay-scrim closing' : 'overlay-scrim'}
        aria-label="닫기"
        tabIndex={-1}
        onClick={requestClose}
      />
      <aside
        className={closing ? 'drawer closing' : 'drawer'}
        role="dialog"
        aria-modal="true"
        aria-label={label}
      >
        {children}
      </aside>
    </DrawerCloseContext.Provider>
  );
}

interface DrawerHeaderProps {
  /** 구분 배지(물품/용역/공사 또는 발주계획·사전규격). */
  badge: ReactNode;
  /** 하위 호환용 — 실제 닫기는 Drawer 의 애니메이션 경로(context)를 쓴다. */
  onClose?: () => void;
}

export function DrawerHeader({ badge }: DrawerHeaderProps) {
  // X 버튼도 스크림·Esc 와 같은 애니메이션 경로로 닫는다.
  const requestClose = useContext(DrawerCloseContext);
  return (
    <div className="drawer-header">
      {badge}
      <button type="button" className="drawer-close" onClick={requestClose} aria-label="서랍 닫기">
        ×
      </button>
    </div>
  );
}

/** 메타 격자의 한 줄. 값이 비면 아예 그리지 않는다 — 원본의 `.filter(([, v]) => v)`. */
export interface MetaRow {
  label: string;
  value: ReactNode;
  /** 값이 긴 항목(기관명·품명 등)은 카드 두 칸을 차지해 줄바꿈 없이 읽히게 한다. */
  wide?: boolean;
}

interface DrawerMetaProps {
  rows: readonly MetaRow[];
  /** 격자 아래에 붙는 접이식 블록들. */
  children?: ReactNode;
}

export function DrawerMeta({ rows, children }: DrawerMetaProps) {
  return (
    <div className="drawer-meta">
      {rows.map((row) => (
        // 각 항목을 카드 셀로 — 넓은 패널에서 2~3열로 배열돼 빈 공간을 채운다(토글 불필요).
        <div className={row.wide ? 'meta-field wide' : 'meta-field'} key={row.label}>
          <div className="meta-key">{row.label}</div>
          <div className="meta-val">{row.value}</div>
        </div>
      ))}
      {children}
    </div>
  );
}

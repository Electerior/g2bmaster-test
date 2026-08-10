/*
 * 화면 자리표시자.
 *
 * 이 웨이브의 목표는 셸과 라우팅이 맞는지 확인하는 것이다. 각 화면의 내부는 다음 웨이브에서
 * 채운다. 자리표시자를 두는 이유는 라우트를 나중에 붙이면 "라우트는 있는데 컴포넌트가
 * 없어서 흰 화면" 상태를 거치게 되고, 그 상태에서는 셸이 맞는지 확인할 수 없기 때문이다.
 */
import { useEffect } from 'react';

interface ScreenPlaceholderProps {
  title: string;
  /** 다음 웨이브에서 이 화면이 무엇을 하게 되는지 — 한 줄. */
  description?: string;
}

export function ScreenPlaceholder({ title, description }: ScreenPlaceholderProps) {
  // 라우트마다 문서 제목을 바꾼다 — 브라우저 히스토리에서 화면을 구분할 수 있어야 한다.
  useEffect(() => {
    document.title = `${title} — G2B Masters`;
  }, [title]);

  return (
    <section className="panel" aria-label={title}>
      <h2 className="panel-title">{title}</h2>
      <div className="empty-msg">
        준비 중
        {description ? (
          <div className="meta" style={{ marginTop: 8 }}>
            {description}
          </div>
        ) : null}
      </div>
    </section>
  );
}

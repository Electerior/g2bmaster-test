import { SAMPLE_NOTICES } from '@/features/beta/landing.config';

const FILTERS = [
  { name: 'IT장비 · 네트워크', count: 34, on: true },
  { name: '사무기기 유통', count: 18, on: false },
  { name: 'SI · 소프트웨어', count: 27, on: false },
  { name: '전산장비 렌탈', count: 9, on: false },
];
const ORGS = [
  { name: '지방자치단체', count: 52 },
  { name: '교육청', count: 21 },
];

/** 히어로 하단에 절반만 걸치는 제품 목업. 이미지가 아니라 전부 마크업입니다. */
export default function ProductMock() {
  return (
    <div className="app">
      <div className="app-bar">
        <span className="dot" />
        <span className="dot" />
        <span className="dot" />
        <span className="app-title">G2B MASTERS — 오늘의 매칭</span>
      </div>

      <div className="app-body">
        <aside className="app-side">
          <div className="side-lbl">FILTER</div>
          {FILTERS.map((f) => (
            <div key={f.name} className={`side-item${f.on ? ' on' : ''}`}>
              {f.name}
              <s>{f.count}</s>
            </div>
          ))}
          <div className="side-lbl" style={{ marginTop: 12 }}>
            기관
          </div>
          {ORGS.map((o) => (
            <div key={o.name} className="side-item">
              {o.name}
              <s>{o.count}</s>
            </div>
          ))}
        </aside>

        <div className="app-main">
          <div className="app-head">
            <h4>
              오늘 수집된 공고 <span style={{ color: 'var(--gold)' }}>312</span>건 중 매칭 3건
            </h4>
            <span>2026-08-06 07:00</span>
          </div>

          {SAMPLE_NOTICES.map((n) => (
            <div key={n.no} className={`row${n.hot ? ' hot' : ''}`}>
              <div>
                <div className="row-meta">
                  <span>{n.no}</span>
                  <span>{n.org}</span>
                  <span className="dday">{n.dday}</span>
                </div>
                <div className="row-t">{n.title}</div>
                <div className="row-price">{n.price}</div>
              </div>
              <div className="score">
                <b>{n.score}</b>
                <s>매칭</s>
                <div className="bar-mini">
                  <i style={{ width: `${n.score}%` }} />
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

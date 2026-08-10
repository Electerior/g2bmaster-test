import { d } from '@/features/beta/useLandingMotion';

const DONE = ['공고 수집', '맞춤 선별', '첨부파일 요약', '낙찰 데이터 대조'];

const FACTS = [
  ['매칭도', '94'],
  ['유사 실적', '3건'],
  ['제한 요건', '충족'],
  ['평균 경쟁', '23.6개사'],
  ['과거 낙찰 사정률', '87.4%'],
  ['감점 요인', '2건'],
];

/** 담당자가 하는 일은 Go / Stop 둘 중 하나라는 것을 한 카드로 보여줍니다. */
export default function Decision({ onApply }: { onApply: () => void }) {
  return (
    <section className="sec">
      <div className="wrap">
        <div className="pin" data-reveal>
          <span className="kicker">Decision</span>
          <h2>담당자가 하는 일은 Go 아니면 Stop, 둘 중 하나</h2>
        </div>

        <p className="lead" data-reveal style={{ ...d(80), maxWidth: 680, marginBottom: 'clamp(30px,4vw,48px)' }}>
          공고를 찾고 고르고 첨부파일을 열어보는 일은 G2B MASTERS가 끝냅니다. 담당자는 정리된 한 장을
          보고 들어갈지 넘길지만 정하면 됩니다.
        </p>

        <div className="card gold decide" data-reveal style={d(160)}>
          <div className="done">
            <span className="done-t">여기까지는 G2B MASTERS가 끝냈습니다</span>
            {DONE.map((t) => (
              <span key={t} className="done-i">
                {t}
              </span>
            ))}
          </div>

          <div className="decide-rule" />

          <div>
            <div className="decide-meta">
              <span>20260806-00417</span>
              <span>○○광역시청</span>
              <span className="dday">마감 D-4</span>
              <span>적격심사</span>
            </div>
            <h3>네트워크 장비 및 부대장비 구매설치 · 추정가격 428,600,000원</h3>
            <div className="facts">
              {FACTS.map(([label, value]) => (
                <span key={label} className="fact">
                  {label}
                  <b>{value}</b>
                </span>
              ))}
            </div>
          </div>

          <div className="decide-rule" />

          <div className="gostop">
            <button type="button" className="gs go" onClick={onApply}>
              <em>GO</em>
              <span>들어간다</span>
              <small>투찰 준비를 시작합니다</small>
            </button>
            <button type="button" className="gs stop" onClick={onApply}>
              <em>STOP</em>
              <span>넘긴다</span>
              <small>다음 공고로 이동합니다</small>
            </button>
          </div>
        </div>

        <p className="form-note" data-reveal style={{ ...d(240), marginTop: 22 }}>
          투찰 방식과 투찰가 결정, 실제 투찰은 나라장터에서 담당자가 직접 진행합니다.
        </p>
      </div>
    </section>
  );
}

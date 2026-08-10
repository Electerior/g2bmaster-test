import { d, useCountUp, useGrow } from '@/features/beta/useLandingMotion';

interface BarProps { value: number; decimals: number; suffix: string; height: number; label: string; hi?: boolean; delay: number }

function Bar({ value, decimals, suffix, height, label, hi, delay }: BarProps) {
  const { ref: numRef, text } = useCountUp<HTMLElement>(value, decimals, suffix);
  const { ref: barRef, grown } = useGrow<HTMLElement>(delay);
  return (
    <div className={`bar${hi ? ' hi' : ''}`}>
      <em ref={numRef}>{text}</em>
      <i ref={barRef} style={{ height: grown ? `${height}%` : 0 }} />
      <span>{label}</span>
    </div>
  );
}

function Chart({ title, delta, before, after, revealDelay }: {
  title: string; delta: string;
  before: { value: number; decimals: number; suffix: string; height: number };
  after: { value: number; decimals: number; suffix: string; height: number };
  revealDelay: number;
}) {
  return (
    <div className="card chart" data-reveal style={d(revealDelay)}>
      <div className="chart-top">
        <strong>{title}</strong>
        <span className="delta">{delta}</span>
      </div>
      <div className="bars">
        <Bar {...before} label="도입 전" delay={0} />
        <Bar {...after} label="도입 후" hi delay={140} />
      </div>
    </div>
  );
}

export default function Results() {
  return (
    <section className="sec" id="results" style={{ paddingTop: 0 }}>
      <div className="wrap">
        <div className="sec-head" data-reveal>
          <span className="kicker">Results</span>
          <h2>알파 테스터 12곳의 3개월</h2>
          <p className="lead">도입 전 3개월과 도입 후 3개월의 투찰 이력을 비교했습니다.</p>
        </div>

        <div className="charts">
          <Chart
            title="월평균 투찰 건수" delta="+118%" revealDelay={60}
            before={{ value: 5.5, decimals: 1, suffix: '건', height: 20 }}
            after={{ value: 12.0, decimals: 1, suffix: '건', height: 92 }}
          />
          <Chart
            title="낙찰률" delta="+9.4%p" revealDelay={160}
            before={{ value: 11.2, decimals: 1, suffix: '%', height: 18 }}
            after={{ value: 20.6, decimals: 1, suffix: '%', height: 88 }}
          />
        </div>

        <p className="note" data-reveal>
          조사 방식 — 2025년 11월부터 2026년 1월까지 알파 테스트에 참여한 12개 기업(IT장비 납품·사무기기
          유통·SI·렌탈 업종, 상시 입찰 담당 인력 1~3인 규모)을 대상으로 했습니다. 각 사가 나라장터 투찰
          이력을 직접 제출했고, 도입 전 3개월(2025. 08. ~ 2025. 10.)과 도입 후 3개월(2025. 11. ~ 2026. 01.)의
          월평균 투찰 건수와 낙찰률(낙찰 건수 ÷ 투찰 건수)을 단순 평균해 산출했습니다. 업종·기관·예산 규모
          차이는 보정하지 않았고, 표본이 12개사로 적어 통계적 유의성은 검증하지 않았습니다. 그래프는 도입
          전후 차이를 보이기 위한 상대 비교이며 세로축이 0에서 시작하지 않습니다.
        </p>
      </div>
    </section>
  );
}

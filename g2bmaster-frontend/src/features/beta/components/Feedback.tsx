import { REVIEWS, type Review } from '@/features/beta/landing.config';
import { d } from '@/features/beta/useLandingMotion';

function Card({ r }: { r: Review }) {
  return (
    <div className="rev">
      <div className="stars">★★★★★</div>
      <p>&ldquo;{r.quote}&rdquo;</p>
      <div className="who">
        <i>{r.initial}</i>
        <div>
          <b>{r.initial}**</b>
          <s>{r.role}</s>
        </div>
      </div>
    </div>
  );
}

/** 카드를 두 벌 이어 붙이고 -50% 로 흘려서 이음매를 없앱니다. */
function Marquee({ items, animation, delay }: { items: Review[]; animation: string; delay: number }) {
  const doubled = [...items, ...items];
  return (
    <div className="marquee" data-reveal style={d(delay)}>
      <div className="marquee-track" style={{ animation }}>
        {doubled.map((r, i) => (
          <Card key={`${r.initial}-${i}`} r={r} />
        ))}
      </div>
    </div>
  );
}

export default function Feedback() {
  return (
    <section className="sec" style={{ paddingTop: 0 }}>
      <div className="wrap">
        <div className="sec-head" data-reveal>
          <span className="kicker">Alpha Feedback</span>
          <h2>먼저 써본 조달 담당자 12명</h2>
          <p className="lead">3개월간 실제 입찰 업무에 사용했고, 전원이 정식 도입 의향을 밝혔습니다.</p>
        </div>
      </div>

      <Marquee items={REVIEWS} animation="mR 46s linear infinite" delay={0} />
      <Marquee items={[...REVIEWS].reverse()} animation="mL 52s linear infinite" delay={100} />
    </section>
  );
}

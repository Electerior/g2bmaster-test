import { BENEFITS } from '@/features/beta/landing.config';
import { d } from '@/features/beta/useLandingMotion';

export default function Benefits() {
  return (
    <section className="sec" style={{ paddingTop: 'clamp(60px,8vw,110px)' }}>
      <div className="wrap">
        <div className="sec-head" data-reveal>
          <span className="kicker">Benefits</span>
          <h2>베타 참여 혜택</h2>
        </div>
        <div className="bene">
          {BENEFITS.map((b, i) => (
            <div key={b.num} className={`card${b.gold ? ' gold' : ''}`} data-reveal style={d(60 + i * 90)}>
              <span className="num">{b.num}</span>
              <h3>{b.title}</h3>
              <p>{b.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

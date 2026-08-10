import { FEATURE_TABS, TAB_DURATION } from '@/features/beta/landing.config';
import { useAutoRotate } from '@/features/beta/useLandingMotion';
import FeatureVisual from './FeatureVisual';

export default function Features({ onApply }: { onApply: () => void }) {
  const { index, select, running, containerProps } = useAutoRotate(FEATURE_TABS.length, TAB_DURATION);

  return (
    <section className="sec" id="features" style={{ paddingTop: 0 }}>
      <div className="wrap">
        <div className="sec-head" data-reveal>
          <span className="kicker">Product</span>
          <h2>네 가지 일을 대신합니다</h2>
        </div>

        <div className="tabs" role="tablist">
          {FEATURE_TABS.map((t, i) => (
            <button
              key={t.label}
              type="button"
              role="tab"
              aria-selected={i === index}
              className={`tab${i === index ? ' on' : ''}${i === index && !running ? ' paused' : ''}`}
              onClick={() => select(i)}
            >
              <strong>{t.label}</strong>
              <span>{t.caption}</span>
            </button>
          ))}
        </div>

        <div className="feat" {...containerProps}>
          <div>
            {FEATURE_TABS.map((t, i) => (
              <div key={t.label} className={`panel${i === index ? ' on' : ''}`}>
                <h3>{t.title}</h3>
                <p>{t.body}</p>
                <ul>
                  {t.bullets.map((b) => (
                    <li key={b}>{b}</li>
                  ))}
                </ul>
              </div>
            ))}
            <button type="button" className="btn ghost" style={{ marginTop: 6 }} onClick={onApply}>
              베타로 먼저 써보기
            </button>
          </div>

          <FeatureVisual index={index} />
        </div>
      </div>
    </section>
  );
}

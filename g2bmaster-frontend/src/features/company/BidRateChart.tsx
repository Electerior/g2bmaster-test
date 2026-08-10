/*
 * 투찰률 성향 그래프 — 원본 renderCompanyProfile 안의 SVG 조립(app.js:2056~2104).
 *
 * 좌표 계산은 원본 그대로다(W 620 · H 160 · 패딩 t16/r20/b32/l48). 이 그래프가 답하는
 * 질문은 "이 업체가 얼마나 공격적으로 넣는가"이고, 그러려면 **절대 투찰률이 아니라 그 업체의
 * 최소~최대 사이에서의 위치**가 보여야 한다. 그래서 y축을 0~100% 가 아니라 min~max 로 잡는다.
 * 점 하나로는 추세가 없으므로 2개 미만이면 그리지 않는다.
 */
import type { CompanyHistoryResponse } from '@/api';
import './company.css';

type Series = CompanyHistoryResponse['stats']['bidRateSeries'];

const W = 620;
const H = 160;
const PAD = { t: 16, r: 20, b: 32, l: 48 };
const INNER_W = W - PAD.l - PAD.r;
const INNER_H = H - PAD.t - PAD.b;

interface BidRateChartProps {
  series: Series;
}

export function BidRateChart({ series }: BidRateChartProps) {
  if (series.length < 2) return null;

  const rates = series.map((p) => p.rate);
  const minR = Math.min(...rates);
  const maxR = Math.max(...rates);
  // 전부 같은 값이면 span 이 0 이 되어 0 나누기가 난다 — 원본과 같이 1 로 둔다(선이 평평해진다).
  const rSpan = maxR - minR || 1;

  const toX = (i: number) => PAD.l + (i / (series.length - 1)) * INNER_W;
  const toY = (rate: number) => PAD.t + (1 - (rate - minR) / rSpan) * INNER_H;

  const polyPoints = series.map((p, i) => `${toX(i).toFixed(1)},${toY(p.rate).toFixed(1)}`).join(' ');
  const areaBottom = H - PAD.b;
  const areaPoints = `${PAD.l},${areaBottom} ${polyPoints} ${toX(series.length - 1).toFixed(1)},${areaBottom}`;
  const gridRates = [minR, (minR + maxR) / 2, maxR];

  // x축 라벨은 6개쯤만 — 전부 찍으면 겹쳐 읽을 수 없다. 마지막 점은 항상 넣는다.
  const step = Math.max(1, Math.ceil(series.length / 6));
  const labelIndexes = series
    .map((_, i) => i)
    .filter((i) => i % step === 0 || i === series.length - 1);

  return (
    <div className="corp-chart-wrap">
      <div className="corp-chart-title">투찰률 성향 그래프</div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="corp-trend-svg"
        role="img"
        aria-label="투찰률 성향 그래프"
      >
        <defs>
          <linearGradient id="corpAreaGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--corp-line)" stopOpacity="0.25" />
            <stop offset="100%" stopColor="var(--corp-line)" stopOpacity="0" />
          </linearGradient>
        </defs>

        {gridRates.map((rate, i) => (
          <g key={`grid-${i}`}>
            <line
              x1={PAD.l}
              y1={toY(rate).toFixed(1)}
              x2={W - PAD.r}
              y2={toY(rate).toFixed(1)}
              stroke="var(--corp-grid)"
              strokeWidth="1"
              strokeDasharray="4,3"
            />
            <text
              x={PAD.l - 4}
              y={(toY(rate) + 4).toFixed(1)}
              textAnchor="end"
              fontSize="10"
              fill="var(--corp-axis)"
            >
              {rate.toFixed(2)}%
            </text>
          </g>
        ))}

        <polygon points={areaPoints} fill="url(#corpAreaGrad)" />
        <polyline
          points={polyPoints}
          fill="none"
          stroke="var(--corp-line)"
          strokeWidth="2"
          strokeLinejoin="round"
        />

        {series.map((point, i) => (
          <circle
            key={`dot-${i}`}
            cx={toX(i).toFixed(1)}
            cy={toY(point.rate).toFixed(1)}
            r="4"
            fill={point.won ? 'var(--corp-dot-won)' : 'var(--corp-dot-lost)'}
            stroke="var(--corp-dot-stroke)"
            strokeWidth="1.5"
          >
            {/* SVG <title> 이 곧 툴팁이다 — 자바스크립트 없이 브라우저가 띄운다. */}
            <title>
              {point.date} {point.name || ''} | 투찰률 {point.rate.toFixed(3)}% |{' '}
              {point.won ? '낙찰' : point.rank ? `${point.rank}순위` : '미낙찰'}
            </title>
          </circle>
        ))}

        {labelIndexes.map((i) => (
          <text
            key={`x-${i}`}
            x={toX(i).toFixed(1)}
            y={H - PAD.b + 14}
            textAnchor="middle"
            fontSize="10"
            fill="var(--corp-axis)"
          >
            {series[i].date.slice(2, 7)}
          </text>
        ))}
      </svg>
      <div className="corp-chart-legend">
        <span>
          <span className="corp-legend-dot won" />
          낙찰
        </span>
        <span>
          <span className="corp-legend-dot" />
          미낙찰
        </span>
      </div>
    </div>
  );
}

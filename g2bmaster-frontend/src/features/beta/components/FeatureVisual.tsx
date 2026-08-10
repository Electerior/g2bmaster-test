/** 자동 순환 탭의 우측 목업. 배경 프레임은 고정하고 안쪽 내용만 바뀝니다. */

function Row({ label, value, ok }: { label: string; value: string; ok?: boolean }) {
  return (
    <div className={`vrow${ok ? ' ok' : ''}`}>
      <em>{label}</em>
      <s>{value}</s>
    </div>
  );
}

interface Chip { label: string; on?: boolean }

function Chips({ items }: { items: Chip[] }) {
  return (
    <div className="chips">
      {items.map((c) => (
        <span key={c.label} className={`chip${c.on ? ' on' : ''}`}>
          {c.label}
        </span>
      ))}
    </div>
  );
}

const PANES = [
  <>
    <div className="vhead">
      <b>수집 로그</b>
      <s>2026-08-06 07:00:12</s>
    </div>
    <Row label="지방자치단체" value="+128건" />
    <Row label="교육청 · 국립대" value="+64건" />
    <Row label="공공기관 · 공단" value="+87건" />
    <Row label="사전규격" value="+33건" />
    <Row label="수집 완료" value="312건 · 4.2초" ok />
    <Chips items={[{ label: '본공고', on: true }, { label: '사전규격', on: true }, { label: '긴급', on: true }, { label: '종료' }]} />
  </>,
  <>
    <div className="vhead">
      <b>매칭 근거</b>
      <s>20260806-00417</s>
    </div>
    <Row label="업종 일치 — 정보통신공사업" value="+32" ok />
    <Row label="유사 실적 3건 (최근 2년)" value="+28" ok />
    <Row label="직접생산확인증명서 보유" value="+21" ok />
    <Row label="지역제한 충족" value="+13" ok />
    <Row label="중소기업자간 경쟁 해당 없음" value="0" />
    <Chips items={[{ label: '종합 매칭도 94', on: true }, { label: '기준 조정' }]} />
  </>,
  <>
    <div className="vhead">
      <b>규격서 요약</b>
      <s>규격서.hwp · 42p</s>
    </div>
    <Row label="납품 기한" value="계약일로부터 45일" />
    <Row label="설치 범위" value="본청 4개층 · 야간작업" />
    <Row label="하자보수" value="3년" />
    <Row label="⚠ 지체상금률 0.15% — 표준 대비 높음" value="확인" ok />
    <Row label="⚠ 규격 미달 시 전량 반품 조항" value="확인" ok />
    <Chips items={[{ label: '제출서류 7종', on: true }, { label: '감점요인 2건', on: true }]} />
  </>,
  <>
    <div className="vhead">
      <b>투찰가 분포</b>
      <s>○○광역시청 · 네트워크장비 · 최근 3년</s>
    </div>
    <div className="dist">
      {[18, 31, 48, 66, 88, 100, 72, 44, 26, 14].map((h, i) => (
        <i key={i} className={h >= 70 && i >= 4 && i <= 6 ? 'hit' : ''} style={{ height: `${h}%` }} />
      ))}
    </div>
    <div className="vrow" style={{ marginTop: 8 }}>
      <em>과거 낙찰 사정률 중앙값</em>
      <s>87.4%</s>
    </div>
    <Row label="평균 참여 업체 수" value="23.6개사" />
    <Chips items={[{ label: '동일 기관 14건', on: true }, { label: '동일 품목 61건' }]} />
  </>,
];

export default function FeatureVisual({ index }: { index: number }) {
  return (
    <div className="visual">
      {PANES.map((pane, i) => (
        <div key={i} className={`vpane${i === index ? ' on' : ''}`}>
          {pane}
        </div>
      ))}
    </div>
  );
}

const LINKS = [
  { label: '이용약관', href: '/terms', strong: false },
  { label: '개인정보처리방침', href: '/privacy', strong: true },
  { label: '이메일무단수집거부', href: '/no-email-collect', strong: false },
];

const BIZ = [
  '주식회사 브레인웨어',
  '대표 ○○○',
  '사업자등록번호 000-00-00000',
  '통신판매업신고 제0000-○○○○-0000호',
  '주소 ○○시 ○○구 ○○로 00, 0층',
  '대표전화 000-0000-0000',
  '개인정보보호책임자 ○○○',
];

export default function Footer() {
  return (
    <footer>
      <div className="foot">
        <div className="foot-links">
          {LINKS.map((l) => (
            <a key={l.label} href={l.href} style={l.strong ? { color: '#fff' } : undefined}>
              {l.label}
            </a>
          ))}
          <span className="mail">문의 hello@brainware.co.kr</span>
        </div>

        <div className="biz">
          {BIZ.map((b) => (
            <span key={b}>{b}</span>
          ))}
        </div>

        <p className="note" style={{ marginTop: 4 }}>
          본 페이지에 표기된 성과 수치는 알파 테스트 참여 기업의 자체 집계 평균이며, 개별 기업의
          투찰·낙찰 결과를 보장하지 않습니다. 서비스가 제공하는 공고 정보와 분석 자료는 참고 목적이며,
          최종 입찰 판단과 그 결과에 대한 책임은 이용 기업에 있습니다. 원문 공고와 계약 조건은
          나라장터(www.g2b.go.kr) 공고문을 기준으로 확인해 주시기 바랍니다. 신청 시 수집하는
          이름·소속·이메일·연락처는 베타 운영과 결과 안내 목적으로만 이용하며, 베타 종료 후 지체 없이
          파기합니다. 화면 이미지는 개발 중인 제품의 예시이며 실제와 다를 수 있습니다.
        </p>

        <span style={{ fontSize: 12, color: 'rgba(255,255,255,.35)' }}>
          © 2026 Brainware Co., Ltd. All rights reserved.
        </span>
      </div>
    </footer>
  );
}

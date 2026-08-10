/**
 * 랜딩 페이지 콘텐츠 · 상수.
 * 모집 인원과 마감일은 서버(/api/beta/status)가 정답이고, 아래 값은
 * 서버 응답이 오기 전 또는 실패했을 때 쓰는 대체값입니다.
 */

/*
 * 접수처(docs/beta-signup.gs 의 CONFIG)와 값을 맞춰 둔다. total 은 화면에 내거는 정원,
 * remaining 의 출발점은 실제로 받는 수(CAPACITY)다 — 둘이 다른 건 의도한 것이다.
 * 여기 값이 어긋나도 접수는 서버 값으로 돌아가지만, 응답이 오기 전 한 프레임 동안
 * 틀린 숫자가 보인다.
 */
export const FALLBACK_STATUS = {
  total: 50,
  remaining: 12,
  deadline: '2026-08-31T23:59:59+09:00',
  open: true,
} as const;

/**
 * 신청 폼 '업종' 칸의 입력 도우미(datalist).
 *
 * 고르는 목록이 아니라 예시입니다 — 여기 없는 업종도 그대로 적을 수 있습니다. 알파 테스트
 * 참여사(Results 섹션의 조사 방식 문단)에서 실제로 나온 업종을 앞에 뒀습니다.
 */
export const INDUSTRY_SUGGESTIONS: string[] = [
  'IT장비 납품',
  '사무기기 유통',
  'SI · 시스템 구축',
  '렌탈 · 리스',
  '소프트웨어 개발',
  '통신 · 네트워크 공사',
  '전기 · 소방 공사',
  '건설 · 인테리어',
  '의료기기',
  '가구 · 비품',
  '용역 · 컨설팅',
];

/** 자동 순환 탭 전환 주기(ms). landing.css 의 --tabdur 기본값과 맞춰야 합니다. */
export const TAB_DURATION = 4200;

export interface FeatureTab {
  label: string;
  caption: string;
  title: string;
  body: string;
  bullets: string[];
}

export const FEATURE_TABS: FeatureTab[] = [
  {
    label: '공고 수집',
    caption: '매일 07:00 자동',
    title: '나라장터를 대신 훑습니다',
    body: '매일 아침 7시, 전 기관 공고와 사전규격을 한 번에 수집합니다. 담당자가 키워드를 바꿔가며 검색할 필요가 없습니다.',
    bullets: ['본공고·사전규격·긴급공고 동시 수집', '정정공고와 마감 변경 자동 반영', '수집 즉시 메일·알림 발송'],
  },
  {
    label: '맞춤 매칭',
    caption: '업종·실적 기반',
    title: '우리 회사에 맞는 것만 남깁니다',
    body: '업종, 보유 실적, 인증, 과거 투찰 이력을 기준으로 매칭도를 계산합니다. 참여할 수 없는 공고는 애초에 목록에 오르지 않습니다.',
    bullets: ['제한 요건 미충족 공고 자동 제외', '매칭 근거를 항목별로 표시', '기준 조정 시 즉시 재계산'],
  },
  {
    label: '첨부파일 요약',
    caption: '한글·엑셀 판독',
    title: '첨부파일을 열지 않아도 됩니다',
    body: '규격서·과업지시서의 한글과 엑셀 파일을 읽어 필요한 항목만 뽑아냅니다. 감점 요인과 제출 서류도 함께 짚습니다.',
    bullets: ['납품 기한·설치 조건·하자보수 기간 추출', '제출 서류 체크리스트 자동 생성', '독소조항·감점 요인 표시'],
  },
  {
    label: '낙찰 데이터',
    caption: '과거 투찰 분포',
    title: '얼마에 넣을지 판단할 근거를 줍니다',
    body: '같은 기관, 같은 품목의 과거 낙찰 이력과 투찰가 분포를 보여줍니다. 투찰가 결정은 담당자의 몫이지만, 감으로 하지 않게 됩니다.',
    bullets: ['동일 기관·품목 낙찰 이력', '참여 업체 수와 투찰가 분포', '사정률 구간별 낙찰 빈도'],
  },
];

export interface Review { initial: string; role: string; quote: string }

export const REVIEWS: Review[] = [
  { initial: '김', role: 'IT장비 납품기업 · 영업팀장', quote: '매일 아침 나라장터를 두 시간씩 뒤졌습니다. 지금은 정리된 공고를 받고 투찰 여부만 판단합니다.' },
  { initial: '이', role: '사무기기 유통사 · 조달담당', quote: '규격서 첨부파일을 일일이 열어보지 않아도 됩니다. 요약본에 필요한 항목이 다 있었습니다.' },
  { initial: '박', role: '교육기자재 제조사 · 대표', quote: '놓치던 사전규격이 눈에 들어옵니다. 규격 단계에서 대응하니 준비 기간이 늘었습니다.' },
  { initial: '최', role: '네트워크 구축업체 · 관리팀', quote: '신입 담당자도 첫 주부터 공고를 걸러냅니다. 검색 기준을 따로 가르칠 필요가 없었습니다.' },
  { initial: '정', role: '전산장비 렌탈사 · 입찰담당', quote: '감점 요인을 미리 짚어준 덕에 서류 누락으로 떨어지는 일이 없었습니다.' },
  { initial: '한', role: 'SI 개발사 · 사업개발팀', quote: '검토하는 공고 수는 줄었는데 투찰 건수는 늘었습니다. 그만큼 맞는 공고가 왔다는 뜻입니다.' },
];

export const BENEFITS = [
  { num: '01', title: '정식 출시 후 6개월 무료', body: '베타 기간과 정식 출시 후 6개월 동안 전체 기능을 무료로 제공합니다.', gold: true },
  { num: '02', title: '요청 기능 우선 반영', body: '테스터가 요청한 분석 항목과 알림 조건을 개발 우선순위에 먼저 반영합니다.', gold: false },
  { num: '03', title: '담당자 직접 응대', body: '기획·개발 담당자가 전용 채널에서 문의를 직접 확인하고 답변합니다.', gold: false },
];

/** 화면 예시용 더미 공고. 실제 API 연동 시 /api/notices 응답으로 교체하세요. */
export const SAMPLE_NOTICES = [
  { no: '20260806-00417', org: '○○광역시청', dday: 'D-4', title: '네트워크 장비 및 부대장비 구매설치', price: '추정가격 428,600,000원 · 적격심사', score: 94, hot: true },
  { no: '20260805-02138', org: '○○교육청', dday: 'D-9', title: '스마트교실 구축용 무선AP 구매', price: '추정가격 96,200,000원 · 최저가', score: 81, hot: false },
  { no: '20260805-00902', org: '한국○○공단', dday: 'D-12', title: '정보시스템 서버 임대 용역', price: '추정가격 213,000,000원 · 협상계약', score: 76, hot: false },
];

/*
 * 화면별 컬럼 정의 — 원본 public/app.js 의 `TABS`(2~120행)를 그대로 옮긴 것.
 *
 * 원본은 이 표 하나로 탭 라벨 · 엔드포인트 · 컬럼 · 화면 종류(deal/saved/trend)를 전부
 * 결정했다. 라우터가 생긴 뒤에도 컬럼과 라벨은 여전히 한곳에 있어야 하므로 표는 유지하되,
 * "이 탭은 표인가 딜인가 트렌드인가"를 boolean 플래그 대신 판별 가능한 union 으로 바꿨다.
 * 원본의 `cfg?.deal` / `cfg?.saved` / `cfg?.trend` 분기는 전부 이 union 의 `kind` 로 대체된다.
 */

/**
 * 셀 포매터 종류.
 *
 * 앞쪽 스무 개는 원본 formatCell(app.js:3990) 의 switch 분기와 1:1 대응이고,
 * 뒤쪽은 공고 통합 검색(로컬 색인) 전용이다. **어휘는 하나로 두되 다루는 렌더러는 둘**이다 —
 * 두 계통은 같은 이름의 필드조차 의미가 다르다(색인의 `dday` 는 날짜 문자열이 아니라 남은
 * 일수 숫자이고, `lowestBidRate` 는 이미 백분율이다). 한 switch 에 섞으면 그 차이가 조용히
 * 뭉개진다. 각 렌더러는 자기 부분집합만 알고 나머지는 default 로 흘린다.
 */
export type CellFmt =
  | 'money'
  | 'date'
  | 'datetime'
  | 'rate'
  | 'd-day'
  | 'tel'
  | 'email'
  | 'type-badge'
  | 'source-badge'
  | 'status-badge'
  | 'method-summary'
  | 'requirement-tags'
  | 'opportunity'
  | 'link'
  | 'ntce-link'
  | 'plan-link'
  | 'spec-link'
  | 'ntce-cross'
  | 'result-cross'
  | 'announce-cross'
  /* ── 공고 통합 검색 전용 ─────────────────────────────────────────────── */
  | 'category-badge'
  | 'state-badge'
  | 'region'
  | 'institutions'
  | 'dday-count'
  | 'notice-name'
  | 'spec-cross'
  | 'save-star'
  | 'close-dday'
  | 'opportunity-pending';

export interface ColumnDef {
  label: string;
  key: string;
  /** 없으면 원본 default 분기 = 문자열 그대로 출력. */
  fmt?: CellFmt;
  /**
   * 정렬할 때 서버로 보낼 키. 생략하면 `key` 를 그대로 쓴다(기존 4탭의 동작).
   * `null` 이면 **정렬할 수 없는 컬럼**이라 머리글이 버튼이 아니라 글자로만 그려진다 —
   * 색인 검색은 정렬 화이트리스트가 여섯 개뿐이라 나머지를 누를 수 있게 두면 거짓말이 된다.
   */
  sortKey?: string | null;
}

/** 트렌드 화면 설정 — 원본 TABS[*].trend. */
export interface TrendConfig {
  title: string;
  itemLabel: string;
  quickKeywords: readonly string[];
}

/** 라우트가 고르는 화면 식별자. 원본 state.tab 의 값과 같은 문자열을 쓴다. */
export type ScreenKind =
  | 'notice-search'
  | 'bid-plan'
  | 'pre-spec'
  | 'bid-announce'
  | 'bid-result'
  | 'deal-radar'
  | 'saved-notices'
  | 'spec-search'
  | 'price-db'
  | 'product-trend'
  | 'service-trend'
  | 'construction-trend';

interface ScreenBase {
  label: string;
  endpoint: string;
}

export type ScreenConfig =
  | (ScreenBase & { kind: 'table'; columns: readonly ColumnDef[] })
  | (ScreenBase & { kind: 'deal' })
  | (ScreenBase & { kind: 'saved' })
  | (ScreenBase & { kind: 'trend'; trend: TrendConfig })
  | (ScreenBase & { kind: 'spec-search' })
  | (ScreenBase & { kind: 'price-db' });

export const SCREENS: Readonly<Record<ScreenKind, ScreenConfig>> = {
  /*
   * 공고 통합 검색 — 계획 · 사전규격 · 입찰 · 마감이 한 목록에 섞여 온다.
   *
   * 아래 세 화면(bid-plan · pre-spec · bid-announce)을 이 하나로 갈아 끼웠다. 넷은 서로 다른
   * 종류가 아니라 같은 조달 건의 **단계**라, 탭으로 갈라 두면 "이 사업이 지금 어디까지 왔나"를
   * 보려고 탭을 세 번 옮겨 다녀야 했다. 단계는 이제 탭이 아니라 필터다.
   * 정렬 화이트리스트가 여섯 개(relevance·created·close·name·amount·updated)뿐이므로
   * 나머지 컬럼은 sortKey: null 로 못 누르게 막는다.
   */
  'notice-search': {
    kind: 'table',
    label: '공고 검색',
    endpoint: '/api/search/notices',
    /*
     * 표는 **간결하게** 둔다(SVG 목업 기준). 훑어보며 결정에 필요한 값 — 단계·구분·공고명·
     * 기관·지역·추정가격·공고일·마감(+D-DAY) — 만 컬럼으로 남기고, 나머지 상세(담당자·연락처·
     * 상태·사전규격 연결·색인 갱신 시각·공고 본문·첨부)는 **행을 눌러 여는 드로어**로 보낸다.
     *
     * 이렇게 나눈 이유: 계획·사전규격 단계는 담당자·연락처·상태·마감이 대부분 비어 온다.
     * 그 빈 칸들을 표에 그대로 두면 화면이 휑해 "데이터가 없다"로 오해된다. 상세는 드로어에서
     * 조건부로(값이 있을 때만 줄이 생기게) 그리므로 단계가 무엇이든 깔끔하다.
     * 상세용 필드는 여전히 같은 API(GET /api/search/notices/{id})가 채운다 — 배선은 그대로다.
     *
     * 정렬 화이트리스트가 여섯 개(relevance·created·close·name·amount·updated)뿐이므로
     * 나머지 컬럼은 sortKey: null 로 못 누르게 막는다.
     */
    columns: [
      { label: '단계', key: 'category', fmt: 'category-badge', sortKey: null },
      { label: '구분', key: 'businessDivision', fmt: 'type-badge', sortKey: null },
      // 공고명 셀이 공고번호·본문 미리보기를 서브텍스트로 함께 그린다(IndexCell 'notice-name').
      { label: '공고명 · 공고번호', key: 'noticeName', fmt: 'notice-name', sortKey: 'name' },
      // 공고기관과 수요기관이 다른 건이 흔하다(조달청 대행) — 다를 때 둘 다 보여준다.
      { label: '기관', key: 'noticeInstitutionName', fmt: 'institutions', sortKey: null },
      { label: '지역', key: 'region', fmt: 'region', sortKey: null },
      { label: '추정가격', key: 'estimatedPrice', fmt: 'money', sortKey: 'amount' },
      { label: '공고일', key: 'createdDate', fmt: 'datetime', sortKey: 'created' },
      // 마감일시 셀 안에 D-DAY 배지를 함께 그린다 — 별도 컬럼을 두면 계획 단계에서 둘 다 빈다.
      { label: '마감일시', key: 'closeDate', fmt: 'close-dday', sortKey: 'close' },
      // ★ 저장 — POST /api/saved-notices 로 바로 담는다(SVG 목업의 행 끝 별).
      { label: '', key: 'id', fmt: 'save-star', sortKey: null },
    ],
  },
  'bid-plan': {
    kind: 'table',
    label: '발주 계획',
    endpoint: '/api/bid-plan',
    columns: [
      { label: '수주기회', key: '_opportunitySummary', fmt: 'opportunity' },
      { label: '업무구분', key: 'bsnsDivNm' },
      { label: '조달요청명', key: 'bizNm' },
      { label: '발주기관', key: 'orderInsttNm' },
      { label: '품목', key: 'prdctClsfcNoNm' },
      { label: '품목번호', key: 'prdctClsfcNo' },
      { label: '예산금액', key: 'sumOrderAmt', fmt: 'money' },
      { label: '계약방식', key: 'cntrctMthdNm' },
      { label: '접수일', key: 'nticeDt', fmt: 'datetime' },
      { label: '나라장터', key: 'prcrmntReqInfoUrl', fmt: 'link' },
    ],
  },
  'pre-spec': {
    kind: 'table',
    label: '사전 규격',
    endpoint: '/api/pre-spec',
    columns: [
      { label: '수주기회', key: '_opportunitySummary', fmt: 'opportunity' },
      { label: '업무구분', key: 'bsnsDivNm' },
      { label: '규격번호', key: 'prdctClfcNo' },
      { label: '품명', key: 'prdctClsfcNoNm', fmt: 'spec-link' },
      { label: '품목번호', key: 'prdctClsfcNo' },
      { label: '발주기관', key: 'ntceInsttNm' },
      { label: '예산금액', key: 'asignBdgtAmt', fmt: 'money' },
      { label: '접수일', key: 'pstDt', fmt: 'datetime' },
      { label: '의견마감', key: 'opninRgstClseDt', fmt: 'datetime' },
      { label: 'D-DAY', key: 'opninRgstClseDt', fmt: 'd-day' },
      { label: '규격서', key: 'lnkUrl', fmt: 'link' },
      { label: '관련공고', key: 'bidNtceNoList', fmt: 'ntce-cross' },
    ],
  },
  'bid-announce': {
    kind: 'table',
    label: '입찰 공고',
    endpoint: '/api/bid-announce',
    columns: [
      { label: '수주기회', key: '_opportunitySummary', fmt: 'opportunity' },
      { label: '구분', key: '_type', fmt: 'type-badge' },
      { label: '공고번호', key: 'bidNtceNo' },
      { label: '공고명', key: 'bidNtceNm', fmt: 'ntce-link' },
      { label: '공고기관', key: 'ntceInsttNm' },
      { label: '추정가격', key: 'presmptPrce', fmt: 'money' },
      { label: '계약/낙찰', key: '_contractSummary', fmt: 'method-summary' },
      { label: '제한/자격', key: '_requirementSummary', fmt: 'requirement-tags' },
      { label: '공고일', key: 'bidNtceDt', fmt: 'date' },
      { label: '마감일시', key: 'bidClseDt', fmt: 'datetime' },
      { label: 'D-DAY', key: 'bidClseDt', fmt: 'd-day' },
      { label: '담당자', key: 'ntceInsttOfclNm' },
      { label: '전화', key: 'ntceInsttOfclTelNo', fmt: 'tel' },
      { label: '이메일', key: 'ntceInsttOfclEmailAdrs', fmt: 'email' },
      { label: '낙찰결과', key: 'bidNtceNo', fmt: 'result-cross' },
      { label: '출처', key: '_sourceLabel', fmt: 'source-badge' },
      { label: '상태', key: '_noticeStatus', fmt: 'status-badge' },
    ],
  },
  'bid-result': {
    kind: 'table',
    label: '입찰 결과',
    endpoint: '/api/bid-result',
    /*
     * 공고명을 누르면 바로 오른쪽 슬라이딩 패널(BidNoticeDrawer)이 뜬다 — 예전의 '공고보기 →'
     * 별도 컬럼을 없앴다. 행 어디를 눌러야 상세가 뜨는지 한 곳(공고명)으로 모으면, 오른쪽
     * 끝까지 눈을 옮겨 버튼을 찾을 필요가 없다. 링크는 'ntce-link' 가 openNotice 를 호출한다.
     */
    columns: [
      { label: '구분', key: '_type', fmt: 'type-badge' },
      { label: '공고번호', key: 'bidNtceNo' },
      { label: '공고명', key: 'bidNtceNm', fmt: 'ntce-link' },
      { label: '수요기관', key: 'dminsttNm' },
      { label: '낙찰업체', key: 'bidwinnrNm' },
      { label: '낙찰금액', key: 'sucsfbidAmt', fmt: 'money' },
      { label: '낙찰률', key: 'sucsfbidRate', fmt: 'rate' },
      { label: '참여업체수', key: 'prtcptCnum' },
      { label: '개찰일시', key: 'rlOpengDt', fmt: 'datetime' },
      { label: '낙찰확정일', key: 'fnlSucsfDate', fmt: 'date' },
    ],
  },
  // 공고 소스를 재사용한다 — 점수 게이트를 통과한 건만 딜 분석으로 넘긴다.
  'deal-radar': {
    kind: 'deal',
    label: 'AI 수주 데스크',
    endpoint: '/api/bid-announce',
  },
  // 담아 둔 공고. 여기서는 G2B API 를 부르지 않는다 — 저장된 것만 훑는다.
  'saved-notices': {
    kind: 'saved',
    label: '저장 공고',
    endpoint: '/api/saved-notices',
  },
  // 원본 버그 수정: renderSpecSearchPanel() 은 구현돼 있었지만 TABS 에 항목이 없어
  // 탭을 누르면 cfg === undefined 로 TypeError 가 났다. 여기서 정식 항목으로 등록한다.
  'spec-search': {
    kind: 'spec-search',
    label: '하드웨어 스펙 검색',
    endpoint: '/api/search/titles',
  },
  // 물품 시세 카탈로그. G2B 를 부르지 않고 price_catalog 만 조회·수정한다(Contract B).
  'price-db': {
    kind: 'price-db',
    label: '단가 DB',
    endpoint: '/api/price-catalog',
  },
  'product-trend': {
    kind: 'trend',
    label: '물품 구매 트렌드',
    endpoint: '/api/trends/product',
    trend: {
      title: '물품 구매 트렌드',
      itemLabel: '물품',
      quickKeywords: [
        'PC',
        '노트북',
        '모니터',
        '서버',
        'GPU',
        '프린터',
        '복합기',
        '태블릿',
        '전자칠판',
        '네트워크',
      ],
    },
  },
  'service-trend': {
    kind: 'trend',
    label: '용역 트렌드',
    endpoint: '/api/trends/service',
    trend: {
      title: '용역 트렌드',
      itemLabel: '용역',
      quickKeywords: ['AI', '데이터', '클라우드', '유지보수', '보안', '컨설팅', '교육', '플랫폼'],
    },
  },
  'construction-trend': {
    kind: 'trend',
    label: '공사 트렌드',
    endpoint: '/api/trends/construction',
    trend: {
      title: '공사 트렌드',
      itemLabel: '공사',
      quickKeywords: [
        '건축',
        '토목',
        '전기',
        '정보통신',
        '소방',
        '기계설비',
        '실내건축',
        '조경',
        '유지보수',
        '철거',
      ],
    },
  },
};

/**
 * 팬아웃 검색(요청마다 나라장터를 훑는 쪽) 표 화면.
 *
 * 통합 검색으로 갈아 끼운 뒤 실제로 라우팅되는 것은 `bid-result` 하나다. 나머지 셋은
 * 컬럼 정의와 엔드포인트가 남아 있다 — AI 수주 데스크가 `/api/bid-announce` 를 그대로 쓰고,
 * 되돌릴 때 다시 짜지 않기 위해서다.
 */
export type NoticeTableKind = 'bid-plan' | 'pre-spec' | 'bid-announce' | 'bid-result';

export function columnsFor(kind: ScreenKind): readonly ColumnDef[] {
  const cfg = SCREENS[kind];
  return cfg.kind === 'table' ? cfg.columns : [];
}

/**
 * 탭별 기본 정렬 — 원본 defaultSortForTab(app.js:160).
 * 정렬 없이 들어오면 표가 API 응답 순서를 그대로 보여 주는데, 그 순서는 안정적이지 않다.
 */
export interface SortSpec {
  key: string;
  dir: 'asc' | 'desc';
}

export function defaultSortForKind(kind: ScreenKind): SortSpec {
  if (kind === 'bid-plan') return { key: 'nticeDt', dir: 'desc' };
  if (kind === 'pre-spec') return { key: 'pstDt', dir: 'desc' };
  if (kind === 'bid-announce') return { key: 'bidNtceDt', dir: 'desc' };
  if (kind === 'bid-result') return { key: 'rlOpengDt', dir: 'desc' };
  return { key: '', dir: 'asc' };
}

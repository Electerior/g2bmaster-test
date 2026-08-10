# g2bmaster-frontend

나라장터(G2B) 입찰정보 웹앱 — React 18 + TypeScript + Vite.

모놀리스 [`g2bmastersopen`](https://github.com/Electerior/g2bmastersopen) 를 세 저장소로
나눈 것 중 하나다.

| 저장소 | 역할 | 스택 |
|---|---|---|
| **`g2bmaster-frontend`** (이 저장소) | 화면 | React 18 + TypeScript + Vite |
| [`g2bmaster-backend`](https://github.com/Electerior/g2bmaster-backend) | API·적재·영속 | Spring Boot 4.1 + MySQL 8 |
| [`g2bmaster-AI`](https://github.com/Electerior/g2bmaster-AI) | 추론 | (기존 Python/LLM 스택 유지) |

원본은 빌드 단계가 없는 단일 페이지였다 — `public/index.html` 437줄과
`public/app.js` 5786줄이 전역 `state` 객체를 직접 변형하고 템플릿 리터럴로
`innerHTML` 을 찍는 구조. 이 저장소는 그것을 라우팅·타입·서버 상태 캐시가 있는
SPA 로 다시 세운 것이다.

---

## 실행

Node 20 이상이 필요하다. **백엔드가 먼저 떠 있어야 한다** — 이 저장소에는 서버가 없고
화면은 전부 `/api` 를 부른다 (`g2bmaster-backend` 의 README 참고).

```bash
npm install
```

```bash
npm run dev
```

`http://localhost:5173`. `/api` 요청은 vite 프록시가 백엔드(기본 `http://localhost:8080`)로
넘긴다 — 다른 주소면 `.env` 에 `VITE_PROXY_TARGET` 을 준다.

| 명령 | 설명 |
|---|---|
| `npm run dev` | 개발 서버 (기본 포트 5173) |
| `npm run build` | 타입 검사 + 프로덕션 번들 (`dist/`) |
| `npm run preview` | 빌드한 결과를 그대로 띄워 확인 |
| `npm run typecheck` | 타입만 검사 |
| `npm run lint` | ESLint |
| `npm test` | Vitest |

### 환경 변수

`.env.example` 참고.

| 변수 | 설명 |
|---|---|
| `VITE_API_BASE_URL` | 백엔드 절대 주소. 비우면 같은 오리진의 `/api` 를 호출한다 |
| `VITE_PROXY_TARGET` | dev 서버가 `/api` 를 넘길 곳 (기본 `http://localhost:8080`) |

---

## 구조

```
src/
  routes/     화면 단위 컴포넌트
  components/ 표·배지·서랍·모달·드롭존·페이지네이션 등 공통 UI
  features/
    search/   검색 조건 훅 (URL 검색 파라미터가 단일 출처)
    deal/     수주 데스크 — 편집 가능한 품목·단가 표, 가격 조회 큐, 보고서
    export/   엑셀 내보내기 작업 폴링
    legal/    법령 검토·콜드메일 초안
  domain/     컬럼 정의, 셀 포매터, 순수 뷰모델 (프레임워크 비의존)
  api/        엔드포인트별 타입 붙은 클라이언트 + TanStack Query 훅
  lib/        axios 인스턴스
  styles/     디자인 토큰 + 전역 스타일
```

---

## 이식 현황

원본은 `app.js` 5786줄 + `style.css` 2988줄이다. 화면 14개 중 9개를 옮겼다.

| 화면 | 상태 |
|---|---|
| 검색 헤더 (1~13 공용) | ✅ 검색 모드 6종, 태그 입력 4종, 기간·빠른 선택, 즐겨찾기 |
| `/notices/*` 4종 (발주계획·사전규격·입찰공고·입찰결과) | ✅ 정렬·페이징·구분 필터. **첨부 전수조사는 대기** — 백엔드에 `POST /api/scan-attachments` 가 없다(첨부 파싱 미착수). 파일 발췌·불가 조항 행을 그리는 코드는 그대로 두고 `ATTACHMENT_SCAN_READY` 하나로 꺼 두었다 |
| 공고 상세 서랍 3종 | ✅ (문서 신호·법령 검토 영역은 분석 랩 단계로 미룸) |
| `/trends/*` 3종 | ✅ |
| `/saved` | ✅ |
| `/company`, `/officers` | ✅ (투찰률 SVG 차트 포함) |
| `/deal-radar` | ❌ 자리 표시자 — 편집 가능한 품목·단가 표가 원본에서 가장 복잡한 위젯이다 |
| `/spec-search` | ❌ 자리 표시자 |
| `/analysis-lab` | ❌ 자리 표시자 |
| `/system` | ❌ 자리 표시자 (백엔드 API 는 완성돼 있다) |
| 설정 모달 / 들러리 매트릭스 / 엑셀 작업 폴링 | ❌ (`Modal` 은 만들어 둠) |

`npm run typecheck`, `npm run lint`, `npm run build` 모두 통과하고,
실제 Spring 백엔드 + MySQL 에 붙여 화면이 뜨는 것까지 확인했다.

## 원본에서 달라진 점

이식하면서 의식적으로 바꾼 것들이다.

1. **탭·검색모드 → 라우트.** 원본은 `state.tab`(10개)과 `state.searchMode`(6개)라는
   직교하는 두 전역 선택자로 화면이 결정됐고, 그중 셋(`corp`/`officer`/`upload`)은
   탭과 무관하게 결과 영역을 가로챘다. 전부 URL 로 폈다.

2. **`searchSeq` 경쟁 가드 삭제.** 원본은 비동기 렌더마다
   `requestSeq !== searchSeq || requestTab !== state.tab` 를 손으로 확인해 늦게 온
   응답을 버렸다. TanStack Query 의 쿼리 키가 같은 일을 하므로 통째로 없앴다.

3. **검색 조건의 단일 출처가 URL.** 즐겨찾기·공유·뒤로가기가 공짜로 따라온다.
   원본의 앱 내 뷰 히스토리 스택(각 10칸)도 브라우저 히스토리로 대체했다.

4. **`innerHTML` → JSX.** `escHtml()` 이 사라졌다. 서버 데이터를 문자열로 이어붙이던
   자리가 전부 JSX 가 되면서 손으로 관리하던 XSS 위험 한 부류가 없어졌다.
   첨부 문서에서 뽑은 마크다운은 `react-markdown` + `rehype-sanitize` 로 렌더한다
   (원본은 CDN 의 `marked` + `DOMPurify` 를 쓰고, CDN 로드 실패 시 조용히 평문으로 떨어졌다).

5. **CDN → npm.** `xlsx`, `pdfjs-dist`, 마크다운 렌더러를 번들에 넣었다.

6. **`/spec-search` 를 살렸다.** 원본의 `renderSpecSearchPanel()` 은 완전히 구현돼
   있었지만 어디서도 호출되지 않았고, 해당 탭을 누르면 `TABS` 에 항목이 없어
   TypeError 가 났다.

7. **CORS.** 저장소가 갈리면서 프론트와 API 의 오리진이 달라졌다.
   백엔드가 `CORS_ALLOWED_ORIGINS` 로 이 오리진을 허용해야 한다.

---

## 유지한 것

- **한국어 UI 문구 전부.** 오류 메시지는 백엔드가 내려주는 한국어를 그대로 쓴다.
- **API 필드명.** 백엔드와의 계약이다 — `g2bmaster-backend/docs/api-contract.md` 참고.
- **디자인.** `style.css` 의 토큰과 레이아웃을 그대로 옮겼다.
- **localStorage 키.** `g2b_ui_state`, `g2b_presets`, `companyProfile`,
  `dealMinScore`, `dealActiveOnly`, `g2b-active-export-job` — 기존 사용자의 설정이 유지된다.
- **폴링 주기.** 내보내기 작업 1500ms(오류 시 3000ms 백오프), 작업 id 를
  localStorage 에 저장해 새로고침 후에도 이어받는 동작 포함.

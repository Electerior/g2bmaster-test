# g2bmaster — 시스템 구조 문서

나라장터(G2B) 입찰정보 서비스. 모놀리스 `g2bmastersopen`(`server.js` 5363줄 + `lib/` 52개 모듈
+ 프론트 6200줄 + 113개 테이블)을 **세 저장소**로 쪼갠 결과물이다.

작성 기준: 2026-08-09, `~/dev` 작업 트리 실제 코드.
각 저장소 안의 `docs/`·`README.md`·`PORTING_STATUS.md` 가 1차 출처이고, 이 문서는 그것을
가로질러 **세 저장소를 하나로 볼 때 필요한 것**만 모은 것이다.

---

## 1. 구성

| 저장소 | 역할 | 스택 | 포트 |
|---|---|---|---|
| `g2bmaster-frontend` | 화면 | React 18 + TypeScript 5.7 + Vite 6 | 5173 |
| `g2bmaster-backend` | API · 적재 · 영속 | Spring Boot 4.1 / Java 25 / MySQL 8 | 8080 |
| `g2bmaster-AI` | 추론 (LLM · 임베딩 · 법령 · 가격) | Python 3 + FastAPI | 8000 |

세 저장소 밖(`~/dev` 루트)에 오케스트레이션 파일 둘이 있다 — 어느 저장소에도 속하지 않기 때문이다.

- [docker-compose.yml](docker-compose.yml) — 개발용 MySQL 8.0 (호스트 **3307**, 컨테이너 3306).
  시스템 MySQL 3306 을 건드리지 않으려고 포트를 뺐다. 데이터는 이름 있는 볼륨 `g2b-mysql-data`.
- [start_all.sh](start_all.sh) — 셋을 한 번에 띄운다. 여기서만 정하는 값은 **세 저장소가 짝을
  맞춰야 하는 설정**이다: `AI_SERVICE_SECRET`(쌍), 데드라인 순서, `JAVA_HOME`(Java 25), `APP_API_KEY`.

### 의존 방향

```
브라우저 ──HTTP──▶ frontend(Vite dev / 정적 번들)
                      │ /api  (dev: vite proxy → :8080)
                      ▼
                  backend(Spring) ──▶ MySQL 8
                      │                나라장터 OpenAPI · D2B · 누리장터
                      │ AI_BASE_URL
                      ▼
                  AI(FastAPI) ──▶ LM Studio / OpenAI 호환 LLM
                                  다나와 · 에누리 · 아이티마야
                                  korean-law-mcp
```

**단방향이다.** AI 는 백엔드를 호출하지 않고 MySQL 도 모른다. 프론트는 AI 를 직접 부르지 않는다.

---

## 2. 핵심 설계 결정

이 프로젝트를 이해하려면 이 다섯 개를 먼저 알아야 한다. 나머지는 여기서 파생된다.

### 2-1. 백엔드는 추론하지 않는다

모델 호출·프롬프트 조립·임베딩 생성·법령 MCP 대화는 전부 AI 저장소 소유다. 백엔드가 갖는 것은
그 주변의 **내구성 있는 상태** — 작업 큐, 리스, 재시도, 중복 제거, 결과 이력, 내보내기.

원본에도 이미 이 이음매가 있었다(`lib/analysis-executor.js`, 23줄짜리 HTTP 클라이언트).
저장소를 나누면서 **호출 대상만 바뀌고 구조는 그대로**다.

### 2-2. AI 없이도 백엔드가 돈다

`AI_ENABLED=false` 로 두면 검색·트렌드·저장 공고·운영 화면·첨부 다운로드·수주기회 점수
(순수 규칙 기반)가 전부 정상 동작한다. 동작하지 않는 것: 심층 분석, 웹 가격, 위법성 판단,
서약서 수정본, 임베딩 유사도 재정렬.

### 2-3. 미구현은 200 이 아니라 501

가장 많이 반복되는 규칙이다. AI 서비스의 미이식 경로는 `501 NOT_PORTED` 로 응답한다.
"곧 됩니다"를 200 으로 위장하면 그 폴백 결과가 `analysis_history` 에 눌러앉고, 재사용 키
(입력해시 + 프롬프트버전)가 같으므로 **영원히 재분석되지 않는다.**

백엔드도 같은 규칙을 쓴다 — `POST /api/system/backfill` 은 `501 {"code":"NOT_PORTED"}`.

### 2-4. 통일된 응답 봉투는 없다 (의도적)

원본이 네 가지 모양을 쓰고 프론트가 그에 맞춰져 있다. 이식하면서 통일하지 않았다. → §5.1

### 2-5. 한국어 오류 문구는 사용자 대상 계약

화면에 그대로 렌더링된다. 임의로 바꾸지 않는다. AI 쪽도 같다 — `app/errors.py` 의 `message`
필드가 그대로 `aiError` 가 되어 사용자에게 간다.

---

## 3. 저장소별 구조

### 3-1. g2bmaster-backend (Spring Boot 4.1 / Java 25)

패키지는 **기술이 아니라 도메인**으로 자른다 (`controller/`·`service/` 가 아니다).

```
com.electerior.g2bmaster/
  analysis/     분석 작업 큐·리스·재시도·이력. AnalysisInputHasher(Node 고정값 대조), AnalysisJobRunner
  attachment/   첨부 다운로드 프록시(SSRF 가드)·문서 텍스트 추출(HWP/HWPX/PDF/DOCX/XLSX/ZIP)
  cache/        SearchResultCache
  common/       ApiException · GlobalExceptionHandler · PagedResponse · G2bDates · Numbers
  config/       G2bProperties · OpenApiConfig · WebConfig(CORS)
  index/        공고 검색 색인 — 적재기·쿼리빌더·동기화 스케줄러·NoticeSearchController
  integration/
    ai/         AiClient — AI 저장소로 나가는 유일한 문
    d2b/        국방전자조달
    g2b/        나라장터 OpenAPI (동시성 제한·재시도·오류봉투·창 분할·캐시)
  market/       시장정보 — 개찰결과·딜분석·낙찰자 이력·담당자·들러리
  notice/       검색 4종 팬아웃(bid-announce · bid-result · bid-plan · pre-spec)
  pricing/      단가 카탈로그·시세·딜 계산
  saved/        저장 공고 CRUD
  schedule/     동기화 스케줄러
  search/       순수 도메인 — SearchQuery(BM25) · OpportunityScoring
  security/     @RequireAppAuth · AppAuthInterceptor · DebugAccessGuard
  system/       운영 화면 API · 헬스
  trend/        트렌드 집계 3종
```

주요 의존성: `spring-boot-starter-webmvc`, `data-jpa`, `flyway` + `flyway-mysql`,
`mysql-connector-j`, `actuator`, `mail`, `validation`, `springdoc-openapi 3.1`,
`poi-ooxml`(XLSX), `pdfbox`(PDF), `hwplib`·`hwpxlib`(한글), `jsoup`, `icu4j`, `lombok`.
테스트는 JUnit + H2.

### 3-2. g2bmaster-AI (Python 3 + FastAPI)

```
server.py           진입점 — uvicorn app.main:app (HOST/PORT, AI_RELOAD)
app/
  main.py           HTTP 표면 전체(라우트 15개) · 예외 핸들러 4종 · 비밀값 미들웨어
  config.py         설정 우선순위 data/ai-config.json > 환경변수 > 기본값 (원본과 동일)
  errors.py         실패 분류의 단일 출처 — FAILURES 표
  prompts.py        프롬프트 본문 + 버전 (ITEM_SUMMARY_PROMPT_VERSION, PROMPT_VERSIONS)
  llm/client.py     LM Studio / OpenAI 호환 채팅 · 모델 탐지 (원본 lib/lms.js)
  llm/worker_pool.py 다중 GPU 부하 분산 · 쿨다운 · 페일오버 (원본 lib/llm-worker-pool.js)
  embedding.py      sentence-transformers (ML 스택 없으면 503)
  price.py          가격 애그리게이터 (다나와 · 에누리 · 아이티마야)
  enuri.py / itmaya.py / pricecommon.py / part_resolver.py
  estimate.py       규격서 → 부품 추출(LLM) → 부품별 단가 → 원가 추정
  prebuilt.py       완제품 판정 + 유사 완제품 탐색
  spec_parser.py / opportunity.py
  handlers/         item_summary_handler.py · bid_summary_handler.py (현재 NOT_PORTED 를 올린다)
module_a/           제목 의미검색 (sentence-transformers + rank-bm25)
module_b/           하드웨어 스펙 추출 (LLM extractor + 스키마)
module_server.py    구 모듈 서버(별도 표면, 9개 라우트) — §6-3
korean-law-mcp/     법령 MCP 서버 (Flask/Dockerfile 포함)
scripts/            test_worker_pool · test_errors · test_http_contract · smoke_llm · smoke_price
```

의존성이 세 겹으로 갈린다 — 최소 집합만 설치해도 서비스는 뜬다.

| 파일 | 내용 | 없을 때 |
|---|---|---|
| `requirements.txt` | fastapi · uvicorn · pydantic · httpx · python-dotenv · openpyxl | — |
| `requirements-ml.txt` | sentence-transformers · transformers 4.46.3 · rank-bm25 · numpy | `/api/embed` 만 503 |
| `requirements-tools.txt` | openpyxl (오프라인 분석 도구) | 서비스 무관 |

### 3-3. g2bmaster-frontend (React 18 + TS + Vite 6)

```
src/
  main.tsx        진입점 — QueryClient · BrowserRouter · ErrorBoundary
  routes/         화면 단위 컴포넌트 + router.tsx + routePaths.ts
  components/     표 · 배지 · 서랍 · 모달 · 페이지네이션 · 마크다운
  features/
    search/       검색 조건 훅 (URL 검색 파라미터가 단일 출처)
    notices/      공고 표·상세 서랍 4종·첨부 스캔
    deal/         수주 데스크 — 편집 가능한 품목·단가 표
    price/ trends/ company/ officer/ saved/ beta/
  domain/         컬럼 정의 · 셀 포매터 · 순수 뷰모델 (프레임워크 비의존)
  api/            엔드포인트별 타입 붙은 클라이언트 + TanStack Query 훅 (단일 진입점 index.ts)
  lib/apiClient   axios 인스턴스 — baseURL · 앱키 주입 · 오류 정규화
  styles/         디자인 토큰 + 전역 스타일
```

의존성: `@tanstack/react-query` 5, `react-router-dom` 6, `axios` 1.7,
`react-markdown` + `rehype-sanitize` + `remark-gfm`. 테스트는 Vitest + Testing Library + jsdom.

---

## 4. 엔트리 포인트

### 4-1. 실행 진입점

| 대상 | 명령 | 코드상 진입점 |
|---|---|---|
| 전체 | `bash start_all.sh` (`--force` / `--force-all` / `SKIP_AI=1`) | — |
| DB | `docker compose up -d db` | — |
| backend | `./mvnw spring-boot:run` | `G2bmasterBackendApplication` |
| AI | `make start` (개발: `make dev`, `AI_RELOAD=1`) | `server.py` → `app.main:app` |
| frontend | `npm run dev` | `src/main.tsx` → `AppRouter` |

`start_all.sh` 는 compose MySQL 헬스체크를 기다렸다가 백엔드를 띄운다. PID 는
`/tmp/g2bmaster_service_pids.txt`, 로그는 `/tmp/g2bmaster-logs`.

### 4-2. 검증 명령

| 저장소 | 명령 |
|---|---|
| backend | `./mvnw test` · `./mvnw -DskipTests package` |
| AI | `make check`(워커풀 + 오류계약 + HTTP 계약) · `make smoke`(실제 LLM, 없으면 SKIP) |
| frontend | `npm run typecheck` · `npm run lint` · `npm test` · `npm run build` |

### 4-3. 문서·탐색 진입점

| 주소 | 내용 |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI — **지금 뜬 프로세스가 실제로 제공하는 전부** |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3.1 (JSON) |
| `http://localhost:8000/docs` | AI 서비스 FastAPI 문서 |
| `http://localhost:8080/healthz` · `http://localhost:8000/health` | 헬스 |

Swagger 의 자물쇠(🔒)는 손으로 붙이지 않는다 — `OpenApiConfig` 가 인증 인터셉터와
**같은 판정 함수**를 쓰고, `OpenApiDocumentTest` 가 그 대응을 확인한다.

> 운영에서 문서를 닫을 때는 **하이픈이 빠진 이름**을 쓴다(스프링 완화 바인딩은 하이픈을
> 밑줄이 아니라 **삭제**한다): `SPRINGDOC_APIDOCS_ENABLED=false SPRINGDOC_SWAGGERUI_ENABLED=false`

### 4-4. 화면 라우트 (프론트)

`/` → `/notices` 로 리다이렉트. `/beta` 만 앱 셸 밖이다.

| 경로 | 화면 | 상태 |
|---|---|---|
| `/notices` | 공고 통합 검색 (계획·사전규격·입찰·마감 한 목록) | ✅ |
| `/notices/bid-result` | 입찰 결과 | ✅ |
| `/deal-radar` | AI 수주 데스크 | ✅ |
| `/saved` | 저장 공고 | ✅ |
| `/price-db` | 단가 DB | ✅ |
| `/trends/product` · `/service` · `/construction` | 트렌드 3종 | ✅ |
| `/company` · `/officers` | 낙찰자 이력 · 담당자 (투찰률 SVG 차트) | ✅ |
| `/spec-search` · `/analysis-lab` · `/system` | 자리 표시자 | ❌ |
| `/beta` | 베타 모집 랜딩 (셸 밖) | ✅ |
| `/notices/bid-plan` · `/pre-spec` · `/bid-announce` | 옛 주소 → 단계 필터 붙여 리다이렉트 | — |

원본 대비 의식적으로 바꾼 것: 탭×검색모드 직교 전역 상태 → **URL 라우트**,
`searchSeq` 경쟁 가드 → **TanStack Query 키**, `innerHTML`+`escHtml()` → **JSX**,
CDN(`marked`/`DOMPurify`/`xlsx`/`pdfjs`) → **npm 번들**.

---

## 5. API 형태

### 5-1. 응답 봉투 4종

| # | 모양 | 쓰는 곳 |
|---|---|---|
| 1 | `{ items, totalCount, pageNo, numOfRows }` | 페이징 검색. `pageNo=0` 은 "전부". 검색계는 `_cached` 추가, 일부는 `sourceCounts`·`sourceErrors`·`sourceStatus`·`_analysisQueue` |
| 2 | `{ error: "한국어 메시지" }` (+ `code`, `missing[]`) | 오류. 400/401/403/404/409/410/413/500/502/503 |
| 3 | `{ ok: true, ... }` | 단순 확인 |
| 4 | 봉투 없는 평평한 큰 객체 | AI 분석. **LLM 실패도 HTTP 200** + `aiFallback:true` + `aiError` |

4번이 의도된 계약인 이유: 첨부에서 뽑은 문서 태그·규격 원문은 LLM 과 무관하게 유효하고,
사용자는 그것만으로도 판단을 이어갈 수 있다. `GlobalExceptionHandler` 가 이 계약을 삼키지
않도록 AI 컨트롤러는 `AiUnavailableException` 을 직접 잡아 폴백 응답으로 바꾼다.

### 5-2. 인증 세 갈래 (브라우저 → 백엔드)

| 방식 | 헤더 | 적용 |
|---|---|---|
| 앱 API 키 | `Authorization: Bearer <key>` 또는 `X-API-Key: <key>` | 쓰기·비용 발생 경로 (`@RequireAppAuth`) |
| 디버그 비밀값 | `X-Debug-Secret` | 없으면 401 이 아니라 **404**(경로 은닉) |
| 알림 비밀값 | `X-Alert-Secret` | `POST /api/run-alert` 전용 |

**`APP_API_KEY` 를 설정하지 않고 띄우면 인증이 통째로 꺼진다**(개발 모드). 프론트는
`VITE_APP_API_KEY` 가 있을 때만 헤더를 붙인다 — 빈 문자열 Bearer 는 운영에서 401 이 된다.

> 이 키는 브라우저 번들에 그대로 들어간다. 조직 내부 배포 기준의 저강도 게이트일 뿐이고,
> 공개 서비스라면 사용자별 토큰으로 대체해야 한다(주입 지점은 `apiClient` 인터셉터 하나다).

### 5-3. 인증 (백엔드 → AI)

`AI_SERVICE_SECRET` 는 **양쪽이 한 쌍**이다. AI 는 `X-Internal-Secret` 또는
`Authorization: Bearer` 둘 다 받는다(원본 `INTERNAL_SECRET` 호환). **한쪽만 설정하면
AI 호출이 전부 401.** 비밀값을 설정해도 `/health`·`/healthz`·`/docs`·`/openapi.json`·`/redoc`
는 통과한다(`OPEN_PATHS`) — 외부 노출 환경이면 그 경로들을 꺼야 한다.

### 5-4. 검색 공통 질의 파라미터 (팬아웃 계열)

`andTerms`/`orTerms`/`notTerms`(공백·콤마), `pageNo`(기본 1, `0`이면 전부),
`perPage`(1..500 클램프, `'all'`→99999), `fromDate`/`toDate`(`YYYY-MM-DD`→`YYYYMMDDHHmm`,
기본 최근 7일), `insttNm`, `sortKey`/`sortDir`, `searchField=item`,
`bidType`(`물품|용역|공사`), `simOr`/`simFile`(임베딩 재정렬 — AI 필요).

### 5-5. AI 실패 응답 계약

전 표면의 실패가 `app/errors.py` 한 곳에서 나온다 — `{code, error, retryable, requestId}`.
**백엔드는 status 가 아니라 `code`/`retryable` 로 판단해야 한다** — status 는 프록시·LB·
모니터링이 읽는 값이고 분류의 진실은 본문에 있다.

| code | status | retryable | 문구 |
|---|---|---|---|
| `BAD_REQUEST` | 400 | ✗ | 요청 형식이 올바르지 않습니다. |
| `INPUT_TOO_LARGE` | 400 | ✗ | 문서 분량이 너무 많아 분석할 수 없습니다. |
| `TAG_MISSING` | 400 | ✗ | 문서 태그가 확인되지 않아 서약서 검토를 시작할 수 없습니다. |
| `UNSUPPORTED_SOURCE` | 400 | ✗ | 지원하지 않는 가격 조회 주소입니다. |
| `UNAUTHORIZED` | 401 | ✗ | AI 서비스 호출 권한이 없습니다. |
| `NOT_PORTED` | 501 | ✗ | AI 분석 기능이 아직 준비되지 않았습니다. |
| `EMBEDDING_UNAVAILABLE` | 503 | ✗ | 임베딩 기능을 사용할 수 없습니다. |
| `LLM_UNAVAILABLE` | 503 | ✓ | AI 분석 서버에 연결할 수 없어 … |
| `LLM_TIMEOUT` | 504 | ✓ | AI 분석이 제한 시간 내에 끝나지 않았습니다. |
| `LLM_MALFORMED` | 502 | ✓ | AI 응답을 해석하지 못했습니다. |
| `PRICE_SOURCE_BROKEN` | 502 | ✓ | 가격 정보를 가져오지 못했습니다. |
| `INTERNAL` | 500 | ✓ | 분석 처리 중 오류가 발생했습니다. |

`NOT_PORTED` 의 status(501)와 `retryable`(false)이 갈리는 것은 의도된 것이다 — 지금 다시
불러도 결과가 같고, 워커가 재시도 예산을 태우면 큐의 다른 작업이 굶는다.

`detail` 은 **로그 전용이고 응답 본문에 넣지 않는다** — 넣으면 파일 경로·엔드포인트 주소가
그대로 사용자 화면에 나간다.

> 현재 백엔드 `AiClient` 는 `catch (RestClientException)` 으로 4xx·5xx 를 뭉뚱그리고
> `e.getMessage()` 만 남긴다 — **이 본문을 아직 읽지 않는다.** 계약을 먼저 세워 둔 상태다.

---

## 6. 엔드포인트

### 6-1. 백엔드 (현재 실제로 등록된 것 — 41개)

컨트롤러 코드에서 직접 뽑았다. 🔒 = `@RequireAppAuth`.

**공고 통합 검색 (로컬 색인 단독)** — `NoticeSearchController`

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/api/search/notices` | 봉투 1번. 색인(`bid_notice`)만 조회 → `sourceErrors`·`_cached` **없음** |
| GET | `/api/search/notices/facets` | `category`/`division`/`region`/`state` 별 건수 |
| GET | `/api/search/notices/status` | 출처별 워터마크 · 분류별 색인 건수 |
| GET | `/api/search/notices/{id}` | 상세. 목록의 `bodyPreview`(300자) 대신 `noticeBody` 전문 |
| POST | 🔒 `/api/search/notices/sync` | 수동 적재. 나라장터 쿼터를 태운다. 진행 중이면 409 |

질의: `q`, `andTerms`/`orTerms`/`notTerms`, `category`(`계획|사전규격|입찰|마감`),
`state`(`취소|재|다시|정정`), `division`(`물품|용역|공사|외자`), `region`, `insttNm`,
`insttCd`, `dmndInsttCd`, `detailProductCode`(접두 일치), `beforeSpecRgstNo`, `officerName`,
`fromDate`/`toDate`, `closeFrom`/`closeTo`, `activeOnly`, `minAmount`/`maxAmount`,
`sort`(`relevance|created|close|name|amount|updated`), `dir`, `page`, `perPage`(≤500).
**정렬 기본값이 조건에 따라 다르다** — 검색어가 있으면 `relevance`, 없으면 `created`.

**팬아웃 검색** — `NoticeController`

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/api/bid-announce` | 나라장터 물품/용역/공사 + D2B + 누리장터 팬아웃 |
| GET | `/api/bid-result` | `ScsbidInfoService`. `bidNtceNo` 로 중복 제거 |
| GET | `/api/bid-plan` | `PrcrmntReqInfoService` |
| GET | `/api/pre-spec` | `HrcspSsstndrdInfoService`. `PRE_SPEC_SOURCE` 로 DB우선/카나리/API 선택 |

**트렌드** — `TrendController`: `GET /api/trends/{kind}`, `GET /api/trends/{kind}/keyword-groups`

**시장 정보** — `MarketIntelController`

| 메서드 | 경로 | 요청 |
|---|---|---|
| POST | `/api/bid-opening-results` | `{bidNtceNo!, bidNtceSqNo?, type?}` — 미공개면 오류가 아니라 `[]` |
| POST | `/api/deal-analysis` | `{item!, bidPrice?, unitCost?, quantity?, deep?, include?, awards?, forceRefresh?}` |
| POST | 🔒 `/api/deal-analysis/backfill` | |
| POST | `/api/prebuilt-comparables` | |
| POST | `/api/company-history` | `{corpNm?, brnNo?, fromDate?, toDate?}` (둘 중 하나 필수) |
| POST | `/api/officer-search` | `{insttNm!, fromDate?, toDate?}` |
| POST | `/api/collusion-analysis` | `{bids[]}` 최대 20건 |

`deal-analysis` 의 `deep` 은 **기본 켜짐**. `include` 로 갈래별 on/off(`{spec, parts, market, opening}`).
계산은 순수 `DealAnalysisService` 가 하고 컨트롤러는 바깥 세계(개찰결과 조회·첨부 파싱)를 붙인다.
하나가 실패해도 나머지는 낸다.

**단가 카탈로그** — `PriceCatalogController`

| 메서드 | 경로 |
|---|---|
| GET | `/api/price-catalog` |
| POST | 🔒 `/api/price-catalog` |
| PUT | 🔒 `/api/price-catalog/{id}` |
| DELETE | 🔒 `/api/price-catalog/{id}` |
| POST | 🔒 `/api/price-catalog/ingest` — AI 의 가격 봉투(§7-2)를 읽어 적재 |
| GET | `/api/price-catalog/history` |

**저장 공고** — `SavedNoticeController`, 클래스 전체 🔒:
`POST /api/saved-notices`, `GET /api/saved-notices`, `GET|DELETE /api/saved-notices/{no}`.
복합 PK `(bid_ntce_no, bid_ntce_ord)`. 쓰기 시 파생: `real_estimate = round(amount × 1.1)`,
`search_text` = 제목+기관+요약+메모+견적 품목명 (20만자 절단).

**분석 작업** — `POST 🔒 /api/analysis-jobs/status` — `{items[]}` 최대 500.

**첨부** — `GET /api/download-attachment` — 바이너리 스트림. SSRF 가드: http(s) 만,
IP 리터럴 금지, 호스트가 `g2b.go.kr|data.go.kr|d2b.go.kr|naramarket.go.kr` 계열,
**리다이렉트 홉마다 재검증**(최대 5회).

**시스템·운영** — `SystemController` + `HealthController`

| 메서드 | 경로 |
|---|---|
| GET | `/healthz` |
| GET | `/api/system/status` · `/calls` · `/operations` · `/tables` · `/schedules` · `/backfill` |
| POST | 🔒 `/api/system/schedules` · 🔒 `/api/system/backfill` (501 `NOT_PORTED`) |
| DELETE | 🔒 `/api/system/schedules/{id}` · 🔒 `/api/system/backfill` (501 `NOT_PORTED`) |

> backend README 에 적힌 "현재 27개"는 낡았다. 색인 검색·단가 카탈로그·시장정보가 붙으면서
> 41개가 됐다. **지금 뜬 프로세스가 제공하는 전부는 언제나 Swagger 가 진실이다.**

### 6-2. AI 서비스 (`app/main.py` — 15개)

| 메서드 | 경로 | 상태 |
|---|---|---|
| GET | `/health` · `/healthz` | ✅ |
| GET | `/api/ai/config` | ✅ 키 마스킹 |
| GET | `/api/ai/prompt-version` | ✅ `{promptVersion, versions}` |
| GET | `/api/ai/capacity` | ✅ 헬스체크 후 `{capacity, workers}` |
| GET | `/api/llm/models` | ✅ 모델 목록·도달 여부 |
| POST | `/api/embed` | ✅ ML 스택 없으면 503 `EMBEDDING_UNAVAILABLE` |
| POST | `/api/price/resolve` | ✅ 다나와·에누리·아이티마야 애그리게이터 |
| POST | `/api/price/url` | ✅ 다나와 `pcode`·에누리 `modelno` 화이트리스트, 그 밖은 `UNSUPPORTED_SOURCE` |
| POST | `/api/estimate-unit-cost` | ✅ 규격서 → 부품 추출 → 단가 → 원가 추정 |
| POST | `/api/prebuilt-comparables` | ✅ 완제품 판정 + 유사 완제품 |
| POST | `/api/item-summary` | ❌ 501 `NOT_PORTED` |
| POST | `/api/bid-summary` | ❌ 501 `NOT_PORTED` |
| POST | `/api/legal/review-clauses` | ❌ 501 `NOT_PORTED` |
| POST | `/api/legal/outreach-draft` | ❌ 501 `NOT_PORTED` |
| POST | `/api/pledge/revision-workflow` | ❌ 501 `NOT_PORTED` |

`estimate-unit-cost` 는 못 찾아도 에러가 아니라 `{matched:false, reason}` **200** 이다 —
규격서에 부품이 없을 수 있다.

### 6-3. AI 모듈 서버 (`module_server.py` — 별도 표면 9개)

`app/main.py` 와 **다른 프로세스/표면**이다. 하드웨어 스펙·의미검색 계열:
`GET /health`, `POST /api/embed`, `POST /api/rank/titles`, `POST /api/search/titles`,
`POST /api/extract/specs`, `GET /api/specs/cpu`, `GET /api/specs/gpu`,
`POST /api/specs/fetch-notices`, `POST /api/specs/search-documents`.

원본에서는 백엔드가 이것을 프록시했고(계약 §J, 전부 앱 키, 연결 실패 시 **502**
`{error:'Module server unavailable: …'}`), 프론트 `src/api/specs.ts` 가 그 경로를 부른다.
현재 Spring 백엔드에는 해당 프록시 컨트롤러가 **없다**.

### 6-4. 프론트가 부르지만 백엔드에 아직 없는 경로

`src/api/*.ts` 는 원본 계약 65개 기준으로 먼저 배선돼 있다. 아래는 호출부는 있고
백엔드 컨트롤러가 없는 것들 — 화면이 플래그로 꺼 두거나 자리 표시자 상태다.

| 경로 | 프론트 파일 | 가드 |
|---|---|---|
| `GET|POST /api/ai-config`, `GET /api/llm/models` | `api/config.ts` | `VITE_AI_ENABLED` (기본 꺼짐) |
| `POST /api/item-summary` · `/api/bid-summary` · `/api/parse-file` | `api/analysis.ts` | `isAiEnabled()` |
| `POST /api/scan-attachments` | `api/notices.ts`, `useAttachmentScan` | `ATTACHMENT_SCAN_READY` 상수 |
| `GET /api/web-price`, `/api/web-price-url` | `api/price.ts` | — |
| `POST /api/legal-outreach`, `/api/pledge-revision(/upload)` | `api/legal.ts` | 화면 미이식 |
| 내보내기 6종 (`/api/export-jobs*`) | `api/export.ts` | 화면 미이식 |
| 스펙 6종 (§6-3) | `api/specs.ts` | 화면 자리 표시자 |
| `POST /api/system/search-compare` | `api/system.ts` | `/system` 자리 표시자 |
| `GET /api/beta/status`, `POST /api/beta/signups` | `api/beta.ts` | **`VITE_BETA_SHEET_URL` 있으면 Google Apps Script 로 직행** (현재 이쪽) |

---

## 7. 계약

### 7-1. 백엔드 ↔ AI — `AiClient` 가 부르는 것

`integration/ai/AiClient` 가 **AI 로 나가는 유일한 문**이다. 기본 주소 `g2b.ai.base-url`.

| Java 메서드 | 경로 |
|---|---|
| `itemSummary` | POST `/api/item-summary` |
| `bidSummary` | POST `/api/bid-summary` |
| `reviewClauses` | POST `/api/legal/review-clauses` |
| `outreachDraft` | POST `/api/legal/outreach-draft` |
| `pledgeRevision` | POST `/api/pledge/revision-workflow` |
| `resolvePrice` | POST `/api/price/resolve` |
| `resolvePriceByUrl` | POST `/api/price/url` |
| `estimateUnitCost` | POST `/api/estimate-unit-cost` |
| `prebuiltComparables` | POST `/api/prebuilt-comparables` |
| `embed` | POST `/api/embed` |
| `promptVersion` | GET `/api/ai/prompt-version` |
| `capacity` | GET `/api/ai/capacity` |
| `models` | GET `/api/llm/models` |

`itemSummary` 는 `aiDisabled` 또는 `aiFallback` 이 `true` 면 **성공으로 치지 않고** 예외를 올린다.

### 7-2. Contract A — 가격 봉투 (2026-08 확장)

`price/resolve` 는 여러 소스를 합쳐 `quotes[]` 를 돌려주는 **단일 애그리게이터**다.

- `quotes[].source ∈ { danawa, enuri, itmaya, index }`
- `itmaya` 는 정형 카탈로그라 `basis="stale"` · `stale=true`(수집시각 = xlsx mtime)
- 한 소스가 실패하고 다른 소스가 성공 → `degraded=true` + `degradedReasons[]` + `searchInfo.misses[]`
- **전부 실패해야** `PRICE_SOURCE_BROKEN`
- 켜진 소스는 `PRICE_SOURCES`(기본 `danawa,enuri,itmaya`)로 정한다

경계는 그대로다 — **AI 는 `quotes[]` 만 주고 선택·검증은 백엔드**. AI 는 `result=null` 을
만들지 않는다. `POST /api/price-catalog/ingest` 가 이 봉투를 읽는다.

> 보존해야 할 규칙: 규격→모델 해석이 일어났는데 검색이 질의를 완화했다면 `result` 를 강제로
> `null` 로 만든다. **틀린 단가는 없는 단가보다 나쁘다.**

### 7-3. 두 저장소가 반드시 합의해야 하는 것

1. **프롬프트 버전** — 현재 `item-summary-2026-08-04-v4`. 분석 결과 재사용 키의 일부다.
   **백엔드가 하드코딩하면 안 된다** (`g2b.analysis.prompt-version` 은 비워 두는 것이 정상).
   AI 가 프롬프트를 고쳤는데 백엔드가 모르면 낡은 결과를 계속 재사용한다.
   프롬프트가 **엔드포인트 × 문서종류**로 갈리므로 `versions` 맵을 함께 낸다.

2. **`analysisInputHash` 는 백엔드만 계산한다.** 입력 payload 정규화(키 정렬, `__rowId`·
   `_cached`·`_opportunityScore` 같은 휘발성 필드 제거) 후 SHA. 초안에서는 두 언어가 같은
   값을 내야 했지만, 같은 로직을 두 언어로 유지하는 것 자체가 어긋날 위험이라 한쪽으로 몰았다
   (`AnalysisInputHasher` + Node 고정값 대조 테스트).

3. **`_analysisHistoryId` 는 계약에서 뺐다.** `analysis_history` 의 PK 는 백엔드 소유이고
   AI 는 MySQL 을 모른다. 그대로 두면 어떤 분석 작업도 완료되지 않는다. 적재는
   `AnalysisJobRunner` 가 한다.

4. **`aiDisabled`/`aiFallback` 은 성공이 아니다.** 폴백 결과가 캐시에 눌러앉으면 영원히
   재분석되지 않는다. `aiError` 는 사용자에게 보여 줄 한국어 사유.

5. **적재용 메타는 소수만 꺼내 쓴다** — `source`, `summary`, `specName`, `specConfidence`,
   `nestedTables`, `documentSignals.bidBlockingClauses.excluded`, `llmModel`. 나머지는
   `analysis_history.result` 에 원문 그대로. AI 가 필드를 늘려도 백엔드를 다시 배포하지 않는다.

### 7-4. 데드라인 순서 (어기면 LLM 비용이 두 배)

```
LLM_TIMEOUT_SECONDS (AI, 기본 100초)
    <  AI_TIMEOUT_MS (backend, 기본 120000)
        <  ANALYSIS_LEASE_MS (backend, 기본 300000)
```

뒤집히면 백엔드가 먼저 포기한 작업을 다른 워커가 다시 집어 **같은 공고를 두 번 추론한다.**

### 7-5. 어디에 무엇이 남았나 (반씩 갈리는 모듈)

| 원본 모듈 | AI 저장소 | 백엔드 |
|---|---|---|
| `procurement-analysis.js` | `analyzeProcurementMarkdown` | `normalizeEvidence` + **근거 인용 검증 규칙** |
| `local-price-db.js` | `inferSystemFromParts`, `formatSystemsForLlm` | 엑셀 카탈로그 적재, `findBestMatch` |
| `market-price-resolver.js` | `resolveMarketPrice` | `deriveProductIdentity`, `quoteMatchesIdentity` |
| `prebuilt-comparables.js` | 주입되는 검색 fetcher | `classifyPrebuiltBundle`, `rankPrebuilts`, `mergePrebuiltResults` |
| `attachment-ingest.js` | `embed(texts)` | 적재 루프, `rankByEmbedding`, `cosine`, 모든 DB 쓰기 |
| `alerter.js` | `generateSalesEmail` | 조회·점수화·렌더링·발송·Slack |

> **근거 검증은 AI 기능이 아니라 정확성 보증이다.** LLM 은 사실을 *제안*할 뿐이고, 채택 여부는
> `evidence.quote` 가 원문에 문자 그대로 존재하는지로 결정된다. 그래서 반드시 백엔드에 둔다 —
> **AI 응답을 검증하는 쪽이 AI 자신이면 검증이 아니다.**

---

## 8. 데이터베이스

MySQL 8 (하한 **8.0.13**, `ON DUPLICATE KEY UPDATE` 행 별칭 때문에 **8.0.19+ 권장**).
`utf8mb4_0900_ai_ci` + `ROW_FORMAT=DYNAMIC` 전 테이블 적용.

**스키마의 유일한 출처는 Flyway 다.** Hibernate 는 `ddl-auto: validate` — DDL 을 절대 건드리지 않는다.

| 마이그레이션 | 내용 |
|---|---|
| `V1__baseline_dimensions` | `dm_*` 차원, `api_call_log`, `sync_state`, `g2b_sync_coverage` |
| `V2__facts` | `dwt_*` 팩트 (`dwt_bid_notice` 149컬럼 등) |
| `V3__raw_tables` | `raw_*` 원문 7종 |
| `V4__staging_tables` | `stg_*` 75종 — 손이 아니라 `tools/convert-stg-tables.js` 로 생성(재실행 시 바이트 동일) |
| `V5__ops_tables` | `analysis_history`, `analysis_job`, `export_job(_item)`, `saved_notice`, `sync_schedule`, `attachment_cache`, `spec_resolution` … |
| `V6__generated_columns` | 부분/표현식 UNIQUE 를 생성 컬럼으로 대체 |
| `V7__bid_notice_search_index` | `bid_notice`, `bid_notice_sync_state` |
| `V8__bid_notice_institution_name` | 기관명 색인 내 저장(조인 제거) |
| `V9__deal_analysis_result` | 딜 분석 결과 캐시 |
| `V10__price_catalog` | `price_catalog`, `price_history` |

검증된 함정들:

- **행 크기** — `dwt_bid_notice` 149컬럼이 VARCHAR 최대 1776바이트로 안전. 전부 `VARCHAR(255)`
  로 바꿨다면 약 150KB 로 **생성 자체가 실패**한다.
- **`analysis_history` 재사용 키 3종은 VIRTUAL 이어야 한다** — MySQL 은 `ON DELETE SET NULL`
  FK 컬럼을 STORED 생성 컬럼의 기반으로 쓰지 못한다.
- 원본 113개 테이블과 컬럼 단위 대조 — 누락 0, 추가 1(`spec_resolution.id`, 의도한 대리키).
  114번째는 `attachment_cache`(원본은 런타임 생성).

> 검증에 쓴 MySQL 은 9.6 이다. 목표는 8.0.19+ 이고 9.x 는 상위 호환이라 문법은 통과하지만,
> **8.0 실기동 검증은 아직 하지 않았다.**

---

## 9. 나라장터 연동에서 반드시 살려야 하는 동작

원본에 주석으로 이유가 적혀 있거나 과거 장애로 추가된 것들이다. `integration/g2b/` 에 살아 있다.

1. **전역 동시 호출 제한 4** — 없애면 429 폭풍이 재현된다.
2. **날짜 창 이분 분할** — 범위 오류·타임아웃·6페이지 초과 시 조회 기간을 절반씩 재귀 분할(깊이 5).
   30일 창은 API 상한을 넘는다.
3. **오류 봉투 함정** — 나라장터는 오류를 `{"nkoneps.com.response.ResponseError":{...}}` 라는
   **다른 루트 키**로 준다. `data.response` 만 읽으면 오류가 "데이터 없음"으로 보인다.
   측정치로 **240건 중 89건**이 이렇게 새고 있었다.
4. **재시도 3회** — 레이트리밋·타임아웃만 재시도, 인증/파라미터 오류는 즉시 실패. 선형 백오프.
5. **캐시** — 검색 2시간/500건, G2B 호출 6시간/2000건, 진행 중 요청 합치기(coalescing).
6. **서비스키가 로그·예외 메시지·응답 스니펫 어디에도 남지 않는다.**
7. **SSRF 허용목록 한 벌 + 리다이렉트 홉마다 재검증.**
8. **업로드 파일명 latin1→UTF-8 복원** — 한글 파일명이 깨진다.
9. **`SPEC_MAX_CHARS = 50000` 과 `clampToContext()` 산술**(문자/토큰 1.8, 프롬프트 여유
   1500 토큰) — 상수만 옮기지 말고 계산식을 옮길 것.
10. **내보내기 취소 시 25행마다 취소 확인** — 작업 등록이 취소된 작업을 되살리기 때문에,
    이 확인이 없으면 취소가 무력화된다(과거 수정된 버그).

---

## 10. 설정

### 백엔드 (`application.yml` + 환경변수)

| 변수 | 기본값 | 비고 |
|---|---|---|
| `MYSQL_HOST` / `PORT` / `DATABASE` / `USER` / `PASSWORD` / `POOL_MAX` | `localhost`/`3306`/`g2b`/`g2b`/—/`10` | compose 를 쓰면 포트는 **3307** |
| `PORT` | `8080` | |
| `G2B_SERVICE_KEY` | — | 없으면 검색이 503 |
| `D2B_SERVICE_KEY` | — | 키 없이도 일부 동작 |
| `APP_API_KEY` | — | **비우면 앱 인증이 꺼진다**(개발 모드) |
| `DEBUG_SECRET` / `ALERT_SECRET` | — | 없으면 해당 경로는 404 |
| `AI_BASE_URL` / `AI_ENABLED` / `AI_TIMEOUT_MS` / `AI_SERVICE_SECRET` | `http://localhost:8000` / `true` / `120000` / — | §7-4 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | |
| `SYNC_ENABLED` | `false` | 운영 인스턴스에서만 |
| `INDEX_SYNC_ENABLED` / `INDEX_SYNC_INTERVAL_MS` / `INDEX_SWEEP_INTERVAL_MS` / `INDEX_BACKFILL_DAYS` | `false`/`600000`/`300000`/`7` | ⚠ **켜는 인스턴스는 하나여야 한다** (워터마크에 인스턴스 간 잠금 없음) |
| `ANALYSIS_RUNNER_ENABLED` / `_CONCURRENCY` / `_POLL_MS` / `_LEASE_MS` / `_RETRY_BASE_MS` | `false`/`1`/`500`/`300000`/`1000` | 여러 인스턴스에서 켜도 `FOR UPDATE SKIP LOCKED` 가 중복 실행을 막는다 |
| `ANALYSIS_PROMPT_VERSION` | — | **비워 두는 것이 정상** (§7-3.1) |
| `SMTP_*` / `ALERT_EMAIL` / `ALERT_KEYWORDS` | — | 알림 메일 |

### AI (`.env`)

`HOST`/`PORT`(8000), `LMS_BASE`(LM Studio), `LLM_API_KEY`, `LMS_MODEL`,
`LLM_TEMPERATURE`(0) / `LLM_TOP_P`(0.95) / `LLM_MAX_TOKENS`(40960) / `LLM_CONTEXT_WINDOW`(40960),
`LLM_TIMEOUT_SECONDS`(100), `AI_SERVICE_SECRET`, `SEARCH_PROVIDER`/`STUDYWEB_URL`,
`PRICE_SOURCES`(`danawa,enuri,itmaya`).

**`LLM_WORKERS`** 가 이 저장소에서 가장 중요한 설정이다 — 콤마로 엔드포인트를 나열하고
`@뒤`가 그 엔드포인트의 동시 처리 용량이다.

```
LLM_WORKERS=http://localhost:1234@1,http://localhost:1235@1,http://localhost:1236@1,http://localhost:1237@1
```

배치 작업(공고 분류·부품 추출)은 `gather` 로 한꺼번에 던지고 워커 풀이 여유율 기준으로
흩뿌린다. **순차로 돌면 GPU 네 대여도 한 대만 일한다.** 한 대가 죽으면 쿨다운(30초) 후 다른
대로 페일오버한다. 읽기 타임아웃 재시도는 기본 **꺼짐** — LM Studio 는 클라이언트가 끊겨도
생성을 계속할 수 있어 재시도하면 같은 프롬프트가 두 장에서 돈다(비용 2배). 재시도는 백엔드
작업 큐가 이미 갖고 있다.

설정 우선순위: `data/ai-config.json` > 환경변수 > 기본값 (원본과 동일).

### 프론트 (`.env`)

| 변수 | 설명 |
|---|---|
| `VITE_API_BASE_URL` | 백엔드 절대 주소. 비우면 같은 오리진 `/api` |
| `VITE_PROXY_TARGET` | dev 서버가 `/api`·`/healthz` 를 넘길 곳 (기본 `http://localhost:8080`) |
| `VITE_APP_API_KEY` | 있을 때만 `Authorization: Bearer` 를 붙인다 |
| `VITE_AI_ENABLED` | AI 요약·분석 호출 스위치 (기본 꺼짐) |
| `VITE_BETA_SHEET_URL` | 있으면 베타 접수를 Google Apps Script 로 직행 |
| `VITE_ALLOWED_HOSTS` | Tailscale Funnel 등 외부 노출 시 추가 호스트 (`.ts.net` 은 기본 허용) |

---

## 11. 이식 현황 요약

| 영역 | 상태 |
|---|---|
| MySQL 스키마 (114 테이블 / 2991 컬럼 / 120 FK / 364 인덱스 / 21 CHECK) | ✅ 실기동 검증 |
| 나라장터 OpenAPI 연동 | ✅ 동시성·재시도·오류봉투·창 분할·캐시 |
| 공통 계층 (설정·인증·예외·응답·CORS) | ✅ |
| 검색 4종 + 색인 검색 5종 + 트렌드 3종 + 시장정보 7종 | ✅ |
| 저장 공고 CRUD · 단가 카탈로그 · 운영 화면 API | ✅ |
| 분석 작업 큐 (리스·재시도·중복제거) | ✅ 워커는 기본 꺼짐 |
| AI — LLM 연동·워커 풀·실패 계약·프롬프트 버전·임베딩 | ✅ |
| AI — 가격 조회 4종 (`price/resolve`·`price/url`·`estimate-unit-cost`·`prebuilt-comparables`) | ✅ |
| AI — 분석·법령·서약서 5종 | ❌ 501 `NOT_PORTED` |
| 백엔드 — 첨부 파싱 / 엑셀 내보내기 / 적재기(backfill) | ❌ 미착수 (backfill 은 501) |
| 프론트 — 화면 14개 중 9개 | 🟡 `/spec-search`·`/analysis-lab`·`/system` 자리 표시자 |

**백엔드 단위 테스트 258개 통과.** 프론트는 `typecheck`·`lint`·`build` 통과 + 실제 Spring
백엔드 + MySQL 연동 확인. AI 는 로컬 LM Studio(모델 4종)에 붙여 모델 목록·용량·헬스체크 확인.

> 각 저장소의 `docs/porting-status.md`(backend, 2026-08-05) 와 `PORTING_STATUS.md`(AI,
> 2026-08-07) 는 갱신일 이후 코드가 앞서 나갔다. 위 표는 2026-08-09 작업 트리 기준으로
> 다시 확인한 것이다. 예: backend porting-status 는 `deal-analysis` 를 미착수로 적지만
> `MarketIntelController` 에 구현돼 있다.

---

## 12. 문서 지도

| 문서 | 내용 |
|---|---|
| `g2bmaster-backend/docs/api-contract.md` | **원본 65개 라우트 전수 계약** — 프론트와의 계약 원본 |
| `g2bmaster-backend/docs/ai-boundary.md` | 백엔드 ↔ AI 경계 전문 |
| `g2bmaster-backend/docs/notice-search-index.md` | 색인 스키마·적재 파이프라인·운영 |
| `g2bmaster-backend/docs/migration-notes.md` · `porting-status.md` | 스키마 이관 · 진도 |
| `g2bmaster-AI/docs/decisions.md` | 결정 기록 (D-*, F-*) |
| `g2bmaster-AI/docs/failure-modes.md` | 실패 분류표 — `scripts/test_errors.py` 가 코드와 대조 |
| `g2bmaster-AI/docs/backend-price-api.md` · `price-search.md` | 가격 봉투 계약 |
| `g2bmaster-AI/docs/ai-boundary.md` · `api-contract.md` | 백엔드 문서의 사본 |
| `g2bmaster-AI/docs/Principles.md` · `plan.md` · `opportunity-eval.md` | 원칙 · 계획 |
| `g2bmaster-AI/CLAUDE.md` | AI 저장소 작업 규칙 |

문서가 코드와 갈리면 **코드가 진실**이고, 백엔드 표면에 대해서는 **Swagger 가 진실**이다.

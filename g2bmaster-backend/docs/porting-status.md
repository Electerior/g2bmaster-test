# 이식 현황

원본 `g2bmastersopen` 은 `server.js` 5363줄 + `lib/` 52개 모듈 + 113개 테이블 +
프론트 6200줄이다. 한 번에 전부 옮기지 않았다. 이 문서는 **어디까지 왔고 무엇이 남았는지**를
숨김 없이 적는다. "곧 됩니다"는 쓰지 않는다 — 되는 것과 안 되는 것만 적는다.

마지막 갱신: 2026-08-05

---

## 요약

| 영역 | 상태 |
|---|---|
| MySQL 스키마 (114 테이블) | ✅ 완료 — 실기동 검증 |
| 나라장터 OpenAPI 연동 | ✅ 완료 — 동시성 제한·재시도·오류봉투·창 분할·캐시 |
| 공통 계층 (설정·인증·예외·응답) | ✅ 완료 |
| AI 경계 (HTTP 위임) | ✅ 클라이언트 완료 / AI 저장소 구현은 미착수(의도적) |
| 검색 API 4종 + 트렌드 3종 + 시장정보 4종 | ✅ 완료 |
| 저장 공고 CRUD · 운영 화면 API | ✅ 완료 |
| 분석 작업 큐 (리스·재시도·중복제거) | ✅ 완료 — 워커는 기본 꺼짐 |
| 첨부 파싱 (HWP·PDF·ZIP) | ❌ 미착수 |
| 엑셀 내보내기 | ❌ 미착수 |
| 적재기(backfill) | ❌ 미착수 — 해당 경로는 **501 NOT_PORTED** 로 정직하게 응답 |
| `POST /api/deal-analysis` | ❌ 미착수 (첨부 파싱 의존) |
| 프론트엔드 | 🟡 화면 14개 중 9개 완료 (자세한 내용은 프론트 README) |

**단위 테스트 258개 통과.** 그리고 실제로 띄워서 확인했다:

```
Flyway V1~V6 적용 → Hibernate ddl-auto=validate 통과 → 기동 성공
GET  /healthz                → {"ok":true,"platform":"onprem"}
POST /api/saved-notices      → 200, real_estimate 1,000,000 → 1,100,000 (×1.1) 확인
GET  /api/saved-notices?q=   → search_text 에 견적 품목명("RTX 5090")까지 들어간 것 확인
GET  /api/bid-announce       → 503 "나라장터 API 인증에 실패했습니다…" (키 미설정 시 정상 동작)
GET  /api/download-attachment?url=https://evil.com/x → 403 "허용되지 않은 주소입니다."
POST /api/system/schedules   → 400 "시각은 HH:MM (24시간) 형식이어야 합니다."
POST /api/system/backfill    → 501 {"code":"NOT_PORTED"}
```

---

## ✅ 완료

### MySQL 스키마

Flyway `V1`~`V6`, **114개 테이블 / 2991개 컬럼 / 120개 FK / 364개 인덱스 /
21개 CHECK**. 격리된 MySQL 인스턴스에 V1→V6 순서로 적용해 **오류 0건**을 확인했다.

원본 113개 테이블을 파싱해 `information_schema` 와 컬럼 단위로 대조했다 —
**누락된 컬럼 0개**, 추가된 컬럼 1개(`spec_resolution.id`, 의도한 대리키).
114번째 테이블은 `attachment_cache` 로, 원본에서 런타임에 만들어지던 것을 옮긴 것이다.

`stg_*` 75개는 손으로 옮기지 않고 `tools/convert-stg-tables.js` 로 생성했다
(재실행 시 바이트 동일, 행 크기 단언 내장).

검증한 것:
- 행 크기 함정 — `dwt_bid_notice` 149컬럼이 VARCHAR 최대 1776바이트로 안전
  (전부 `VARCHAR(255)` 로 바꿨다면 약 150KB 로 생성 자체가 실패한다)
- 부분/표현식 UNIQUE 인덱스 5종을 생성 컬럼으로 대체했고, UNIQUE 강제가 실제로 동작
- `analysis_history` 재사용 키 3종은 **VIRTUAL** 이어야 한다 — MySQL 은
  `ON DELETE SET NULL` FK 컬럼을 STORED 생성 컬럼의 기반으로 쓰지 못한다.
  VIRTUAL 로 두면 UNIQUE 도 걸리고 `ON DELETE SET NULL` 도 정상 동작한다(실측 확인)
- `utf8mb4_0900_ai_ci` + `ROW_FORMAT=DYNAMIC` 전 테이블 적용

> 검증에 쓴 MySQL 은 9.6 이다. 목표는 8.0.19+ 이고 9.x 는 상위 호환이므로 문법은 통과하지만,
> **8.0 실기동 검증은 아직 하지 않았다.**

### 나라장터 OpenAPI 연동

`integration/g2b/` — 원본에서 가장 위험했던 동작들을 전부 살렸다:

- 전역 동시 호출 제한 4 (없애면 429 폭풍이 재현된다)
- 재시도 3회, **레이트리밋·타임아웃만** 재시도(인증/파라미터 오류는 즉시 실패), 선형 백오프
- **오류 봉투 함정** — 나라장터는 오류를 `{"nkoneps.com.response.ResponseError":{...}}`
  라는 다른 루트 키로 준다. 원본 측정치로 240건 중 89건이 "데이터 없음"으로 오독되고 있었다
- 날짜 창 이분 분할(깊이 5), 6시간 응답 캐시 + 진행 중 요청 합치기
- 서비스키가 로그·예외 메시지·응답 스니펫 어디에도 남지 않는다

### 공통 계층

설정 프로퍼티, CORS(저장소 분리로 새로 필요해짐), 앱 API 키 인증(`@RequireAppAuth`),
디버그 게이트, 전역 예외 처리, 페이징 응답, 첨부 다운로드 프록시(SSRF 가드 +
**리다이렉트 홉마다 재검증**), `GET /healthz`.

### 순수 도메인 로직

`lib/search.js` → `search/SearchQuery` (BM25 포함),
`lib/scoring.js` → `search/OpportunityScoring`,
`lib/notice-search.js` → `search/NoticeSearchSupport`,
`lib/dates.js` → `common/G2bDates`, `lib/num.js` → `common/Numbers`.

원본 모듈 하단의 self-check 블록을 JUnit 으로 옮겼다.

---

## ❌ 미착수 — 이유와 함께

### 첨부 파싱 (`lib/files.js`, `lib/hwp.js`)

**HWP 5.0 바이너리 파서가 이 코드베이스에서 Java 이식 난이도가 가장 높다.** 440줄의
OLE/CFB 레코드 워킹 + 인라인 제어코드 건너뛰기 + 표 재구성이고, 같은 출력을 내는
Java 라이브러리가 없다(`hwplib` 은 표 추출 결과가 다르다).

원본 모듈은 이미 **버퍼 in / 텍스트 out 의 순수 함수**다. 통째로 Java 로 옮기기보다
**사이드카 서비스로 남기는 편**을 권한다 — AI 저장소와 같은 방식으로 HTTP 호출하면 된다.

`files.js` 쪽은 라이브러리를 세 개 갈아끼워야 한다(pdf-parse→PDFBox,
adm-zip→java.util.zip, iconv-lite→MS949 Charset). 특히 Java 의 ZIP 리더는 기본 설정에서
**CP949 한글 파일명을 깨뜨린다**.

다만 이 모듈의 **선택 휴리스틱 절반**(`isSpecFile`, `specFilenameRank`,
`specContentScore`, `chooseSpec`)은 순수 함수라 쉽게 옮길 수 있다 — 먼저 분리할 것.

### 엑셀 내보내기 (`lib/export-workbook.js`)

exceljs 스트리밍 라이터 → Apache POI `SXSSFWorkbook` 은 API 가 전혀 다르다.
살려야 할 비자명한 제약이 여럿이다:
- 30,000자 초과 값을 여러 컬럼으로 쪼개되(`"AI 요약 (2/3)"`) **UTF-16 서러게이트 쌍은 자르지 않는다**
- 16,384 컬럼 상한 가드
- `.tmp` 로 쓰고 rename 하는 원자적 게시
- `isExportPathAllowed` 경로 탈출 가드 (보안)

작업 상태 기계(`export-job-service.js`)는 6개 상태 × 취소 경쟁 × 비동기 팬아웃이다.
**25행마다 취소를 확인하는 코드는 과거에 고쳐진 실제 버그**이므로 반드시 살려야 한다.

### 적재기 (`lib/g2b-loader.js` 의 DB 절반)

런타임 `information_schema`/`pg_catalog` 리플렉션 + 동적 다중행 upsert +
FK 스텁 합성 + 65,535 바인드 파라미터 상한 청킹. 정적 타입 Java 와 상극이다.

**MySQL 로 옮길 때의 함정**: `coerce()` 가 `data_type` 문자열을 `/numeric/`·`'boolean'`
으로 매칭하는데 MySQL 은 `decimal`·`tinyint` 를 돌려준다. 오류가 아니라
**잘못된 값이 조용히 들어간다.** (자세한 내용은 `docs/migration-notes.md` §11)

Spring 이 JPA 엔티티로 적재를 넘겨받으면 이 리플렉션 전체가 사라진다 —
그래서 적재기는 "나중에"가 아니라 "설계를 바꿔서" 옮기는 편이 낫다.

### 검색에서 원본보다 좁은 부분 (정확히 어디인지)

1. **`searchField=item` 의 재현율이 원본보다 낮다.** 품목 모드로 바꾸면 검색 대상
   문자열(haystack)이 바뀌고 8/10자리 분류번호는 통과하지만,
   **키워드 → 물품분류번호 확장(`resolveProductClasses`, `ThngListInfoService02`)을
   이식하지 않았다.** 원본은 "메모리"를 공식 품명·분류번호로 넓혀 조회하므로
   그만큼 더 찾는다.
2. **`simOr` / `simFile` 임베딩 재정렬 없음.** 항목에 `_simScore` 가 붙지 않는다.
   임베딩과 저장된 제목 벡터가 둘 다 필요하고, 둘 다 아직 없다.
3. **`_analysisQueue` 와 항목별 `_analysis` 상태가 검색 응답에 실리지 않는다.**
   작업 큐 자체는 완성됐지만 검색 결과를 큐에 자동 등록하는
   `prepareSearchAnalysis` 는 연결하지 않았다. `X-Export-Snapshot` 게이트도 같은 이유로 미이식.
4. **사전규격 DB 우선/카나리 라우팅 미구현** — `PreSpecSource` + `LivePreSpecSource` 가
   그 자리를 표시하는 이음매다. 지금은 항상 live API 를 쓴다. 섀도 비교
   (`recordPreSpecShadow`)도 미이식.
5. **D2B 신 게이트웨이**(`apis.data.go.kr/1690000` 상세 + 품목명세서) 미이식 —
   레거시 목록 API 만 옮겼고, 그것으로 `bid-announce` 는 충분하다.

### AI 저장소 (`g2bmaster-AI`)

**지시에 따라 손대지 않았다.** 백엔드가 기대하는 계약은
`docs/ai-boundary.md` 에 적어 두었다. 그때까지 `g2b.ai.enabled=false` 로 두면
AI 없는 기능만으로 정상 동작한다(§7 에 그 범위가 있다).

---

## 원본에 있었으나 옮기지 않기로 한 것

| 대상 | 이유 |
|---|---|
| `GET /api/debug/corp-test` | 엔드포인트 탐색용 일회성 프로브. 필요하면 개발 프로필로 되살릴 것 |
| `ws://…/api/test-dashboard/ws` + `lib/test-runner.js` | Vitest 실행 대시보드 — Node 스택 개발 도구다. Java 는 JUnit + Maven 을 쓴다 |
| 머티리얼라이즈드 뷰 3종, PL/pgSQL 함수 3종 | 어디서도 호출되지 않는다 (`migration-notes.md` §12) |
| `lib/map-limit.js` | `ThreadPoolTaskExecutor` + `CompletableFuture` 로 대체 |

## 원본에서 고친 것

| 문제 | 원본 | 이식본 |
|---|---|---|
| `GET /api/system/tables` 가 `information_schema` 테이블명을 **인용 없이 문자열 연결**해 `UNION ALL` 을 만든다 (SQL 인젝션 형태) | server.js:4871 | 통계 테이블 조회로 대체 |
| multer 오류 미들웨어가 **라우트 사이**에 등록돼 뒤에 선언된 업로드 경로를 못 잡는다 | server.js:2250 | 전역 `@ControllerAdvice` |
| `requireDebugAccess(res)` — 인자를 하나만 넘겨 운영에서 항상 401 | server.js:2759 | 인터셉터/가드로 대체 |
| SSRF 허용목록이 두 벌로 갈려 사설 IP 처리가 서로 다름 | server.js:176, 2711 | 하나로 통합 + 리다이렉트 홉마다 재검증 |
| 프론트 `/spec-search` 탭이 구현돼 있는데 연결되지 않아 TypeError | app.js:1384 | 정상 라우트로 연결 |

## 이식하면서 드러난 것 — 기록해 둘 가치가 있는 것들

### 분석 입력 해시는 ICU 대조가 필요했다

`analysisInputHash` 는 첨부파일 목록을 `String.prototype.localeCompare` 로 정렬한다.
그것은 곧 **ICU 대조(collation)**이고, `java.text.Collator` 로는 재현되지 않는다 —
실제 나라장터 첨부파일명 24개(하이픈·밑줄·공백·대소문자·한자 혼재)로 실측했을 때
`java.text.Collator` 는 14곳에서 순서가 갈렸고, ICU4J 루트 대조는 Node 와 정확히 일치했다.
순서가 하나만 어긋나도 해시가 달라져 기존 분석 캐시가 통째로 고아가 된다.
그래서 `com.ibm.icu:icu4j` 의존성을 이 한 곳 때문에 추가했다.

Node 구현을 직접 실행해 뽑은 **고정값 4종을 테스트에 박아 두었다.** 전부 일치한다.

> ⚠️ **원본의 잠재 결함**: `localeCompare` 는 <b>호스트 로케일</b>을 쓴다.
> `LANG=ko_KR` 호스트에서 돌린 원본은 파일 정렬이 달라져 해시도 달라진다.
> 이식본은 `ULocale.ROOT` 로 고정했으므로 결정적이다 — 다만 이관해 오는 캐시가
> root/en 로케일 호스트에서 쓰인 것이어야 한다는 전제가 생겼다.

### MySQL 번역에서 걸린 두 가지

- **`ON DUPLICATE KEY UPDATE` 는 대입을 왼쪽부터 순서대로 평가한다.** 모든 절이
  갱신 전 행을 보는 Postgres `DO UPDATE SET` 과 다르다. 작업 등록 upsert 가 제대로
  도는 것은 `status` 와 `analysis_history_id` 를 <b>그 값을 읽는 모든 절 뒤에</b>
  대입했기 때문이다. 순서를 바꾸면 조용히 다른 상태 기계가 된다.
- **`SUM(조건)` 은 빈 테이블에서 NULL 을 돌려준다** — `count(*) FILTER` 는 0 이다.
  `COALESCE` 로 감싸지 않으면 대시보드에 0 대신 빈칸이 뜬다.

### `GET /api/system/tables`

원본의 SQL 인젝션 형태(테이블명 문자열 연결)를 `information_schema.TABLES.TABLE_ROWS`
한 방으로 바꿨다. 이름이 SQL 로 되돌아가지 않으므로 주입 표면이 사라지고,
테이블 수와 무관하게 O(1) 이다. 대신 InnoDB 의 `TABLE_ROWS` 는 통계 추정치라
응답에 `approximate: true` 를 넣어 화면이 그렇게 표시할 수 있게 했다.

## 알려진 미해결

- `llmModel(deep)` 이 호출부 6곳에서 인자를 넘기지만 선언은 무인자다(server.js:210) —
  심층/빠른 구분이 지금도 조용히 무시되고 있다. AI 저장소를 만들 때 의식적으로 결정할 것.
- 원본 다크 모드는 officer/corp 패널만 부분 지원하고 루트 토큰을 재정의하지 않는다.
  제대로 하거나 아예 빼야 한다.
- `analysisInputHash` 의 Node ↔ Java 바이트 동일성 — 어긋나면 이관 순간 분석 캐시가
  통째로 고아가 된다. 교차 언어 고정값 테스트가 이 이식의 최우선 안전장치다.

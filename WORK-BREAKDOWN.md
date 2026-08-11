# 작업 분해 — 문제별 담당 영역과 고치는 법

기준: 2026-08-10, `~/dev` 작업 트리 + 기동 중인 프로세스 실측.
구조 설명은 [ARCHITECTURE.md](ARCHITECTURE.md) 를 본다. 이 문서는 **할 일만** 적는다.
`증상 / 근거 / 고치는 법 / 완료 조건` 

우선순위 : 사용자가 모르고 넘어갈 수 있는 문제

| | 뜻 |
|---|---|
| **P0** | 조용히 틀리는 문제 |
| **P1** | 기능이 미구현 |
| **P2** | 계약 갱신 필요 |

규모: **S** 반나절 이내 · **M** 1~3일 · **L** 그 이상.

---

## 1. 한눈에 보기

| ID | 문제 | 영역 | 우선 | 규모 |
|---|---|---|---|---|
| [B-1](#b-1) | 분석 작업 큐에 **아무도 작업을 넣지 않는다** | 백엔드 | P0 | M |
| [B-2](#b-2) | 프롬프트 버전을 AI 에서 안 읽어 **재사용 캐시가 영구 미스** | 백엔드 | P0 | S |
| [B-3](#b-3) | AI 실패 본문(`code`/`retryable`)을 안 읽어 **재시도 판단이 없다** | 백엔드 | P0 | S |
| [B-4](#b-4) | 색인 적재기에 **인스턴스 간 잠금이 없다** | 백엔드 | P0 | M |
| [O-1](#o-1) | 앱 키·AI 비밀값이 **둘 다 비어 있다**(인증 꺼짐) | 운영 | P0 | S |
| [A-1](#a-1) | AI 분석·법령·서약서 **5종이 501** | AI | P1 | L |
| [B-5](#b-5) | 첨부 스캔 API 없음 → 프론트 기능이 꺼져 있다 | 백엔드 | P1 | M |
| [B-6](#b-6) | 엑셀 내보내기 6종 없음 → 프론트 코드가 고아 | 백엔드 | P1 | L |
| [B-7](#b-7) | 모듈 서버 프록시 6종 없음 → 프론트가 404 를 받는다 | 백엔드 | P1 | S |
| [B-8](#b-8) | 가격·법령·서약서·설정 위임 경로 6종 없음 | 백엔드 | P1 | M |
| [B-9](#b-9) | 적재기(backfill) 501 | 백엔드 | P1 | L |
| [F-1](#f-1) | 화면 3개가 자리 표시자 | 프론트 | P1 | L |
| [F-2](#f-2) | 베타 접수가 백엔드를 안 거치고 외부로 나간다 | 프론트 | P1 | S |
| [D-1](#d-1) | AI 경계 계약표가 실제 표면과 다르다 (11 vs 16) | 교차 | P2 | S |
| [D-2](#d-2) | 진척 문서 3종이 코드보다 뒤처졌다 | 각 영역 | P2 | S |
| [D-3](#d-3) | Contract A 의 `degraded` 문구가 실제 동작과 어긋나 읽힌다 | AI | P2 | S |
| [D-4](#d-4) | `prompt-version` 의 `versions` 맵을 아무도 안 쓴다 | 교차 | P2 | S |
| [C-1](#c-1) | 저장소 잔여물 (보일러플레이트 · 81M orphan) | 잡무 | P2 | S |
| [C-2](#c-2) | MySQL 8.0 실기동 미검증 (9.6 으로만 확인) | 백엔드 | P2 | S |

---

## 2. 백엔드

### B-1
### 분석 작업 큐에 아무도 작업을 넣지 않는다 — P0 / M

**증상.** 큐가 완성돼 있는데 영원히 비어 있다. 분석이 한 건도 실행되지 않지만
`/api/analysis-jobs/status` 는 정상 응답하고 화면도 오류를 띄우지 않는다.

**근거.**
```
AnalysisJobRepository.enqueue()  프로덕션 호출부 0건 (claim/heartbeat/complete/fail 은 러너가 사용)
```
`claim()`·`heartbeat()`·`complete()`·`fail()`·`recoverExpired()` 는 전부 배선돼 있고
`AnalysisJobRunner` 가 돌지만, 넣는 쪽이 없다. `ai-boundary.md` §3 이 백엔드로 오기로 한
`lib/search-analysis.js`(검색 결과 → 분석 작업 일괄 등록)가 이식되지 않았다.

**고치는 법.** 검색 응답을 만들 때 `_analysisQueue` 를 채우는 자리에서 일괄 등록한다.
`api-contract.md` §A 가 `GET /api/bid-announce` 응답에 `_analysisQueue` 가 붙는다고
적어 둔 그 지점이다. 등록 시 `EnqueueRequest` 의 `inputHash`(`AnalysisInputHasher`),
`promptVersion`([B-2](#b-2) 선행), `analysisMode`, `deepMode` 를 채운다.

**완료 조건.**
- 검색 한 번에 N건이 `analysis_job` 에 들어가고, 같은 검색을 두 번 해도 중복 등록되지 않는다
- `ANALYSIS_RUNNER_ENABLED=true` 로 띄우면 작업이 소진되고 `analysis_history` 에 결과가 쌓인다
- 등록 → 실행 → 재사용까지 한 번 도는 통합 테스트

**선행.** [B-2](#b-2). 순서를 뒤집으면 빈 프롬프트 버전으로 등록돼 캐시가 안 걸린다.
분석 결과 자체가 나오려면 [A-1](#a-1) 도 필요하지만, 큐 동작 검증은 501 로도 가능하다.

---

### B-2
### 프롬프트 버전을 AI 에서 안 읽어 재사용 캐시가 영구 미스 — P0 / S

**증상.** 분석 결과 재사용이 **한 번도 일어나지 않는다.** 같은 공고를 다시 분석해도
캐시를 못 찾아 LLM 을 다시 태운다. 결과는 맞으므로 화면에서는 티가 안 나고, 비용만 샌다.

**근거.**
```
AiClient.promptVersion()  프로덕션 호출부 0건 (AiClientSecretTest 에서만 호출)
application.yml:168       prompt-version: ${ANALYSIS_PROMPT_VERSION:}   ← 기본 빈 문자열
AnalysisHistoryRepository:44   if (... || isBlank(promptVersion)) return null;
```
설정은 "비워 두는 것이 정상 — AI 서비스에서 읽는다"고 주석까지 달려 있는데, **읽는 코드가 없다.**
그래서 값은 계속 `""` 이고 `findReusable` 은 빈값 가드에서 곧장 `null` 을 반환한다.

**고치는 법.** `AnalysisProperties.promptVersion()` 이 비어 있으면 `AiClient.promptVersion()`
으로 채우도록 배선한다. 매 호출마다 AI 를 두드리지 않게 짧은 TTL 캐시를 두고, AI 가 죽어
있으면 **빈 값으로 진행하지 말고 등록·실행을 미룬다** — 빈 버전으로 적재된 행은 나중에
올바른 버전으로 조회해도 영영 안 잡힌다.

**완료 조건.**
- `ANALYSIS_PROMPT_VERSION` 미설정 상태에서 `analysis_job.prompt_version` 이 `item-summary-2026-08-04-v4` 로 들어간다
- 같은 입력 2회 분석 시 두 번째가 재사용으로 끝난다(LLM 호출 0)
- AI 가 꺼져 있을 때 빈 버전 행이 생기지 않는다

---

### B-3
### AI 실패 본문을 안 읽어 재시도 판단이 없다 — P0 / S

**증상.** 재시도해도 소용없는 실패(`NOT_PORTED`·`BAD_REQUEST`)에 워커가 재시도 예산을
태운다. 그동안 큐의 다른 작업이 굶는다.

**근거.** `AiClient.post()` 가 `catch (RestClientException e)` 로 4xx·5xx 를 뭉뚱그리고
`e.getMessage()` 만 남긴다. AI 는 이미 `{code, error, retryable, requestId}` 를 주고 있다
— 실측으로 확인했다:
```json
{"code":"NOT_PORTED","error":"AI 분석 기능이 아직 준비되지 않았습니다.","retryable":false,"requestId":"..."}
```
AI 저장소가 이걸 `docs/plan.md` 의 **R0** 으로 요청해 둔 상태다.

**고치는 법.** 실패 본문을 파싱해 `AiUnavailableException` 에 `code`·`retryable`·`requestId`
를 싣는다. 러너는 **status 가 아니라 `retryable` 로** 재시도를 판단한다 — status 는 프록시가
읽는 값이고 분류의 진실은 본문에 있다. `requestId` 는 로그에 남겨 AI 로그와 맞춰볼 수 있게 한다.

**완료 조건.**
- 501 `NOT_PORTED` 를 받은 작업이 즉시 실패 처리되고 재시도되지 않는다
- 503 `LLM_UNAVAILABLE` 은 백오프 후 재시도된다
- 본문이 없거나 깨진 응답도 안전하게 처리된다(파싱 실패 시 재시도 가능으로 간주)

---

### B-4
### 색인 적재기에 인스턴스 간 잠금이 없다 — P0 / M

**증상.** 색인 적재를 켠 인스턴스가 둘 이상이면 같은 구간을 중복 조회해 **나라장터 쿼터를
두 배로 태우고**, 워터마크가 서로를 덮어써 구간이 새 나갈 수 있다.

**근거.** `BidNoticeSyncScheduler` 에 잠금·청구 코드가 0건이다. 분석 큐는
`FOR UPDATE SKIP LOCKED` 로 막고 있고 `schedule/SyncScheduleClaimRepository` 라는
청구 장치도 이미 있는데, 색인 쪽만 없다. `application.yml` 에 경고만 적혀 있다:
```
⚠ 운영에서 이걸 켜는 인스턴스는 하나여야 한다 — 워터마크에 인스턴스 간 잠금이 없다.
```

**고치는 법.** `bid_notice_sync_state` 에 리스 컬럼(소유자·만료)을 더하고 분석 큐와 같은
방식으로 청구한다. 이미 있는 `SyncScheduleClaimRepository` 패턴을 재사용하면 새로 설계할
게 없다.

**완료 조건.**
- 두 인스턴스를 동시에 켜도 한쪽만 적재하고, 다른 쪽은 조용히 건너뛴다
- 적재 중 프로세스가 죽으면 리스 만료 후 다른 인스턴스가 이어받는다
- 설정의 ⚠ 경고 문구를 지운다

---

### B-5
### 첨부 스캔 API 없음 — P1 / M

**증상.** 공고 목록의 첨부 전수조사가 통째로 꺼져 있다. 프론트는 그리는 코드를 다 갖고
있으면서 `ATTACHMENT_SCAN_READY` 상수 하나로 막아 두었다.

**근거.** `POST /api/scan-attachments` 가 백엔드에 없다. 프론트 `api/notices.ts` ·
`features/notices/useAttachmentScan.ts` 가 그 경로를 부른다.

**고치는 법.** 첨부 텍스트 추출은 이미 있다(`DocumentTextExtractor`·`AttachmentFetcher`
— [최근 커밋](https://github.com/Electerior/g2bmaster-backend/tree/feat/price-catalog)에서
들어왔다). 남은 것은 **대량 스캔의 상태 기계**다: 즉시 처리분 + 백그라운드 워밍업 큐
(`api-contract.md` §E).

**완료 조건.** 프론트의 `ATTACHMENT_SCAN_READY` 를 켜도 화면이 정상 동작하고, 파일 발췌·
불가 조항 행이 그려진다. **가드 상수 제거가 이 작업의 DoD 다** — 백엔드만 만들고 끝내면 안 된다.

---

### B-6
### 엑셀 내보내기 6종 없음 — P1 / L

**증상.** 프론트 `api/export.ts` 전체가 호출부 없는 고아 코드다.

**근거.** `POST /api/export-jobs`, `GET /{id}`, `POST /{id}/retry`, `POST /{id}/cancel`,
`GET /{id}/download`, `GET /{id}/items` 가 전부 없다. `export_job`·`export_job_item`
테이블은 V5 에 이미 있다.

**고치는 법.** 상태 기계 `pending → analyzing → generating → completed`(+`failed`·`cancelled`).
`api-contract.md` §H. **살려야 하는 동작 둘:**
- `Idempotency-Key` 헤더 (같은 요청 두 번에 작업 두 개가 생기면 안 된다)
- **취소 시 25행마다 취소 확인** — 작업 등록이 취소된 작업을 되살리기 때문이다. 과거에
  고쳐진 버그이므로 이 확인이 빠지면 취소가 무력화된다

**선행.** AI 열을 채우려면 [A-1](#a-1)·[B-1](#b-1). 다만 AI 없는 열만으로 먼저 낼 수 있다.

---

### B-7
### 모듈 서버 프록시 6종 없음 — P1 / S

**증상.** 프론트 `api/specs.ts` 의 6개 호출이 전부 백엔드에서 404 다.

**근거.** `module_server.py` 는 살아서 9개 라우트를 열고 있는데(별도 표면, `app/main.py`
와 다른 프로세스), 백엔드에 프록시 컨트롤러가 없다:
`POST /api/search/titles`, `POST /api/extract/specs`, `GET /api/specs/cpu`,
`GET /api/specs/gpu`, `POST /api/specs/fetch-notices`, `POST /api/specs/search-documents`.

**고치는 법.** 얇은 프록시. 전부 앱 키 필요, 연결 실패 시 **502**
`{error:'Module server unavailable: …'}` (`api-contract.md` §J). 모듈 서버 주소는
`AI_BASE_URL` 과 별개 설정으로 뺀다 — 다른 프로세스다.

**완료 조건.** `/spec-search` 화면([F-1](#f-1))이 실데이터를 그린다.

---

### B-8
### 위임 경로 6종 없음 — P1 / M

전부 AI 에 이미 있거나([A-1](#a-1) 이후) 곧 생기는 것들인데, **백엔드에 문이 없다.**

| 없는 경로 | 프론트 호출부 | AI 쪽 상태 |
|---|---|---|
| `GET /api/web-price` | `api/price.ts` | ✅ `POST /api/price/resolve` |
| `GET /api/web-price-url` | `api/price.ts` | ✅ `POST /api/price/url` |
| `POST /api/legal-outreach` | `api/legal.ts` | ❌ 501 |
| `POST /api/pledge-revision` | `api/legal.ts` | ❌ 501 |
| `POST /api/pledge-revision/upload` | `api/legal.ts` | ❌ 501 |
| `GET`·`POST /api/ai-config` | `api/config.ts` | ✅ `GET /api/ai/config` |

**가격 둘은 지금 당장 가능하다** — AI 쪽이 실동작한다(실측: 다나와·에누리 30건).

**보존해야 할 규칙 (가격).** 규격→모델 해석이 일어났는데 검색이 질의를 완화했다면
`result` 를 강제로 `null` 로 만든다. **틀린 단가는 없는 단가보다 나쁘다.**
`quotes[]` 에서 고르고 검증하는 것은 백엔드 몫이다(`ai-boundary.md` §4) — AI 는 후보만 준다.

**서약서 가드.** 문서 태그가 확인되기 전에는 첨부를 내려받지 않는다(`{status:'tag_missing'}`
로 400). 불필요한 다운로드와 LLM 호출을 막는 가드다.

**`ai-config` 를 먼저 하면 [F-3] 이 딸려 해결된다** — 프론트가 빌드타임 플래그
`VITE_AI_ENABLED` 를 쓰는 이유가 "그 엔드포인트가 백엔드에 없어서"라고 코드 주석에 적혀 있다.

---

### B-9
### 적재기(backfill) 501 — P1 / L

`POST`·`DELETE /api/system/backfill` 이 `501 NOT_PORTED` 다(실측 확인). 정직하게 응답하고
있으므로 조용히 틀리지는 않는다. `SyncSchedulerService.NOT_PORTED` 도 같은 상태다.

**살려야 하는 동작.** 나라장터 동시 호출 제한 4 · 날짜 창 이분 분할(깊이 5) ·
오류 봉투 함정(`nkoneps.com.response.ResponseError`) — 이 셋은 `integration/g2b/` 에 이미
구현돼 있으므로 재사용한다. 새로 만들 것은 진행률·중단·재개다.

---

### C-2
### MySQL 8.0 실기동 미검증 — P2 / S

스키마 검증은 MySQL **9.6** 으로 했다. 목표는 8.0.19+ 이고 9.x 는 상위 호환이라 문법은
통과하지만 실기동은 확인하지 않았다. 개발용 compose 는 `mysql:8.0` 을 쓰므로
**이미 8.0 에서 돌고 있을 가능성이 높다** — 컨테이너 버전을 확인하고 Flyway V1~V10 을
빈 DB 에 처음부터 적용해 보면 끝난다.

**완료 조건.** 8.0 빈 DB 에 V1→V10 적용 오류 0건 + `ddl-auto=validate` 통과 로그를
`porting-status.md` 에 남긴다.

---

## 3. AI

### A-1
### 분석·법령·서약서 5종이 501 — P1 / L

실측으로 확인한 현재 응답:
```
POST /api/item-summary            501 NOT_PORTED  blockedBy: 백엔드 첨부 파싱 — documents[].text 가 넘어와야 한다
POST /api/bid-summary             501 NOT_PORTED
POST /api/legal/review-clauses    501 NOT_PORTED  korean-law-mcp 는 저장소에 들어와 있다
POST /api/legal/outreach-draft    501 NOT_PORTED
POST /api/pledge/revision-workflow 501 NOT_PORTED
```

**권장 순서** (AI 저장소 `CLAUDE.md §5` 의 순서를 실측 상태에 맞춰 갱신):

1. **`bid-summary`** — 첨부 파싱에 안 걸린다. 프롬프트 버전도 이미 발급돼 있다
   (`bid-summary-2026-08-07-v1`). 가장 먼저 낼 수 있다.
2. **`item-summary`** — 4스텝(clamp → facts → summary → items). `blockedBy` 가
   "백엔드 첨부 파싱"이라고 적혀 있는데 **그 전제는 이제 풀렸다** — `DocumentTextExtractor`
   가 들어왔다. 차단 사유를 다시 확인하고 `PORTING_STATUS.md` 를 갱신할 것.
3. **`legal/*`** — `korean-law-mcp` 가 저장소 안에 있다.
4. **`pledge/revision-workflow`** — 태그 없으면 첨부 접근 전에 400 `TAG_MISSING`.

**절대 어기지 말 것 (AI `CLAUDE.md §2`).**
- 실패를 200 으로 포장하지 않는다. 미구현은 501 이다 — 200 폴백이 `analysis_history` 에
  눌러앉으면 **영원히 재분석되지 않는다**
- 원본 텍스트 좌표계를 훼손하지 않는다. 프롬프트용 정규화를 하면 LLM 이 정규화본을 인용하고
  백엔드는 원본과 대조해 **모든 인용이 기각된다**
- 프롬프트를 고치면 버전을 올린다. 안 올리면 낡은 결과가 계속 재사용된다

**완료 조건 (각 표면 공통).** `scripts/test_http_contract.py` 통과 +
`PORTING_STATUS.md` 갱신 + 실패 응답이 `docs/failure-modes.md` 의 모양을 지킬 것.

---

### D-3
### Contract A 의 `degraded` 문구가 실제 동작과 어긋나 읽힌다 — P2 / S

**동작은 맞고 문구가 틀렸다.** `ai-boundary.md` 는 이렇게 적었다:

> 한 소스가 실패해도 다른 소스가 성공하면 `degraded=true` + `degradedReasons[]`·
> `searchInfo.misses[]` 가 채워진다

실측에서는 `itmaya` 가 빠졌는데도 이렇게 나왔다:
```json
"searchInfo":{"misses":[{"site":"itmaya","reason":"not-found"}]}, "degraded":false, "degradedReasons":[]
```

**"검색은 됐는데 0건"은 실패가 아니기 때문**이고, 이는 같은 문서가 `PRICE_SOURCE_BROKEN`
을 설명하며 그은 구분("파서가 깨져 0건"과는 완전히 다른 사건)과 일치한다. 코드가 옳다.

**고치는 법.** 문구를 "한 소스가 **실패**하면"에서 "not-found 는 degraded 가 아니다"까지
명시하도록 고친다. 지금 문구대로 백엔드가 `degraded` 로 분기를 짜면 조용히 틀린다.

---

## 4. 프론트

### F-1
### 화면 3개가 자리 표시자 — P1 / L

| 화면 | 막고 있는 것 |
|---|---|
| `/spec-search` | [B-7](#b-7) 모듈 서버 프록시 |
| `/analysis-lab` | [A-1](#a-1) + [B-8](#b-8) |
| `/system` | **없다 — 백엔드 API 10개가 이미 완성돼 있다** |

**`/system` 을 먼저 한다.** 백엔드가 `status`·`calls`·`operations`·`tables`·`schedules`·
`backfill` 을 전부 제공하고 실측에서 실데이터가 나온다. 다른 팀을 기다릴 필요가 없는
유일한 화면이다.

`POST /api/system/search-compare` 하나만 백엔드에 없으니 그 위젯은 빼거나
`NotReady` 로 둔다.

**미이식 위젯도 같이 본다.** 설정 모달 · 들러리 매트릭스 · 엑셀 작업 폴링
(`Modal` 컴포넌트는 만들어져 있다).

---

### F-2
### 베타 접수가 백엔드를 안 거치고 외부로 나간다 — P1 / S

**증상.** `VITE_BETA_SHEET_URL` 이 설정돼 있으면 신청 폼이 Spring 이 아니라 **Google Apps
Script 웹앱으로 직행**한다. 지금 그쪽으로 돌고 있다.

**근거.** `api/beta.ts` 상단 주석 — "랜딩만 먼저 띄워야 하는데 백엔드 배포가 아직 없기
때문"이라고 적혀 있다.

**고치는 법.** 백엔드에 `GET /api/beta/status` · `POST /api/beta/signups` 를 만들고
(공개 경로 — `@RequireAppAuth` 를 붙이지 않는다. 로그인 전 방문자용이다),
프론트 `.env` 에서 `VITE_BETA_SHEET_URL` 을 지운다. **파일 밖 코드는 어느 쪽이든 똑같이
동작하도록 이미 짜여 있다.**

봇 방지 히든 필드(`website`)가 차 있으면 조용히 버리는 동작을 백엔드에서도 살린다.

---

### F-3
### AI 스위치가 빌드타임 플래그다 — P2 / S

`isAiEnabled()` 가 `VITE_AI_ENABLED` 를 읽는다. 런타임으로 묻지 않는 이유가 주석에
적혀 있다 — "`GET /api/ai-config` 자체가 아직 백엔드에 없다".

[B-8](#b-8) 에서 `ai-config` 가 생기면 런타임 조회로 바꾼다. 그 전에는 손대지 않는다
(**켜져 있다고 잘못 알리면 매번 500 이 뜬다** — 기본값 꺼짐이 맞다).

---

## 5. 교차 · 운영 · 잡무

### O-1
### 앱 키·AI 비밀값이 둘 다 비어 있다 — P0 / S

**실측.** 자물쇠(🔒)가 달린 `GET /api/saved-notices` 를 **키 없이 호출했더니 200 + 실데이터**
가 왔다. AI 도 `AI_SERVICE_SECRET` 이 비어 무인증으로 열려 있다.

이것은 문서화된 개발 모드 동작이라 **버그가 아니다.** 다만:

- Swagger 의 자물쇠 15개는 "이 경로는 인증 대상"이라는 표시일 뿐, **지금 인증이 걸려
  있다는 뜻이 아니다**
- `AI_SERVICE_SECRET` 는 백엔드와 AI 가 **한 쌍**이다. 한쪽만 설정하면 AI 호출이 전부 401
- AI 는 비밀값을 설정해도 `/docs`·`/openapi.json`·`/redoc` 이 열린 채다(`OPEN_PATHS`).
  외부 노출 환경이면 그 경로들을 꺼야 한다

**고치는 법.** 노출되는 환경에 올리기 전 `APP_API_KEY` · `AI_SERVICE_SECRET` ·
`DEBUG_SECRET` 을 설정한다. `start_all.sh` 가 지금 `APP_API_KEY` 를 일부러 비워 두는데,
**그 주석의 근거가 이미 낡았다** — "프론트에 인증 헤더 배선이 없다"고 적혀 있지만
`apiClient.ts:25` 가 `VITE_APP_API_KEY` 를 읽어 Bearer 를 붙인다. 주석과 함께 정리한다.

> 프론트의 앱 키는 브라우저 번들에 그대로 들어간다. 조직 내부 배포 기준의 저강도
> 게이트일 뿐이고, 공개 서비스라면 사용자별 토큰으로 대체해야 한다.

---

### D-1
### AI 경계 계약표가 실제 표면과 다르다 — P2 / S · 교차

`ai-boundary.md` §5 의 표는 **11개**인데 AI 는 **16 operations** 를 연다.
특히 `POST /api/estimate-unit-cost` 는 **계약표에 아예 없는데 `AiClient.estimateUnitCost()`
가 부른다.** 계약 문서를 보고 작업하는 사람은 이 경로의 존재를 모른다.

표에 없는 것: `/api/estimate-unit-cost`, `/api/prebuilt-comparables`, `/api/ai/config`,
`/health`, `/healthz`.

**이 문서는 "경계 계약. 최상위 권위"로 선언돼 있다**(AI `CLAUDE.md §1`). 권위 문서가
현실과 다르면 그 선언이 무의미해진다. **백엔드 저장소 소유 문서이므로 백엔드가 고치고
AI 가 리뷰한다.**

---

### D-2
### 진척 문서 3종이 코드보다 뒤처졌다 — P2 / S

| 문서 | 틀린 내용 | 실제 |
|---|---|---|
| `g2bmaster-backend/README.md` | "현재 27개" | **41 operations** (Swagger 실측) |
| `g2bmaster-backend/docs/porting-status.md` (2026-08-05) | `deal-analysis` 미착수 · 첨부 파싱 미착수 | 둘 다 구현됨 |
| `g2bmaster-AI/PORTING_STATUS.md` (2026-08-07) | enuri·itmaya·estimate-unit-cost·prebuilt-comparables 언급 없음 | 전부 구현·실동작 |

**각 저장소가 자기 문서를 고친다.** 진척 문서는 "곧 됩니다"를 쓰지 않는다는 규칙으로
운영돼 왔으므로, 낙후된 채 두면 그 규칙 자체가 신뢰를 잃는다.

---

### D-4
### `prompt-version` 의 `versions` 맵을 아무도 안 쓴다 — P2 / S · 교차

AI 는 이미 엔드포인트별로 갈린 값을 낸다:
```json
{"promptVersion":"item-summary-2026-08-04-v4",
 "versions":{"item-summary":"item-summary-2026-08-04-v4","bid-summary":"bid-summary-2026-08-07-v1"}}
```
백엔드는 단일 `promptVersion` 키만 읽도록 설계돼 있다(그나마 [B-2](#b-2) 때문에 실제로는
안 읽는다). **`bid-summary` 가 큐를 타게 되는 순간 item-summary 의 버전으로 키가 잡힌다.**

**결정이 필요하다** — 두 저장소 합의 사항이다. `analysisMode` 별로 `versions` 맵에서
고르게 할지, `bid-summary` 는 큐를 안 타게 할지. [B-2](#b-2) 를 할 때 같이 정하는 것이
가장 싸다.

---

### C-1
### 저장소 잔여물 — P2 / S

| 항목 | 상태 | 처리 |
|---|---|---|
| `g2bmaster-backend/package.json` · `package-lock.json` | untracked. 의존성 0개짜리 `npm init -y` 보일러플레이트 (`"main":"index.js"`, 실패하는 test 스크립트, ISC) | Maven 저장소라 삭제 권장. 단 **`g2bmaster-test` 스냅샷에는 이미 들어가 있으니** 그쪽도 같이 지운다 |
| `g2bmaster-AI/node_modules` (81M) | 매니페스트 없는 잔여 트리 | 삭제. `.gitignore` 규칙은 이미 넣었다 |
| `g2bmaster-AI/coverage` (31파일) | 커버리지 산출물인데 추적 중 | 추적 해제 + `.gitignore` 검토 |

---

## 6. 권장 순서

```
1주차 ── B-2 ─→ B-1        큐가 실제로 돌게 만든다. B-2 를 먼저 해야 캐시가 걸린다
        └ B-3              같은 영역이라 함께. 재시도 판단이 서야 큐가 안 굶는다
        └ O-1              설정만 하면 끝. 노출 전 필수
        └ A-1(bid-summary) AI 는 병렬로 시작. 첨부 파싱에 안 걸리는 것부터

2주차 ── B-4               색인 잠금. 운영 인스턴스를 늘리기 전에
        └ F-1(/system)     프론트는 백엔드를 기다릴 필요가 없다 — API 가 이미 있다
        └ B-8(가격 2종)     AI 가 실동작하므로 백엔드 문만 내면 된다
        └ A-1(item-summary) blockedBy 전제가 풀렸는지 먼저 확인

3주차~ ─ B-5 → B-7 → B-6   첨부 스캔 → 스펙 프록시 → 내보내기
        └ F-1(나머지 화면)  각자 선행이 끝나는 대로

상시 ─── D-1 · D-2 · D-3 · D-4 · C-1
        건드리는 김에 같이. 특히 D-4 는 B-2 와 한 묶음이다
```

**독립적으로 지금 시작할 수 있는 것** (다른 팀을 안 기다린다):
`B-2` · `B-3` · `B-4` · `O-1` · `F-1(/system)` · `A-1(bid-summary)` · `D-2` · `C-1`

---

## 7. 확인하지 못한 것

1. **AI `item-summary` 의 `blockedBy` 가 아직 유효한지.** 첨부 파싱(`DocumentTextExtractor`)
   이 들어왔으므로 전제가 풀렸을 수 있는데, `documents[].text` 를 실제로 넘겨주는 배선이
   있는지는 안 봤다.
2. **`AnalysisInputHasher` 의 Node 고정값 대조 테스트가 현재 통과하는지.** 테스트 파일의
   존재는 문서로만 확인했고 실행하지 않았다.
3. **백엔드 단위 테스트 258개가 지금도 통과하는지.** 이번 세션에서 `./mvnw test` 를 돌리지
   않았다 — `feat/price-catalog` 로 33개 파일이 바뀐 뒤의 상태는 미확인이다.
4. **프론트 `npm test` · `typecheck` · `build`.** 마찬가지로 32개 파일이 바뀐 뒤 미확인.
5. **compose MySQL 의 실제 서버 버전** ([C-2](#c-2)). 이미지는 `mysql:8.0` 이지만
   컨테이너 안에서 `SELECT VERSION()` 을 찍어보지 않았다.

# 공고 검색 색인 (`bid_notice`)

나라장터 공고를 **주기적으로 내려받아 로컬에 쌓고, 사용자 검색은 그 색인만 조회**하는 계통.
스키마의 출처는 ERD `Bid Notice Information (입찰공고정보)` 이고, 구현은
`src/main/resources/db/migration/V7__bid_notice_search_index.sql` 부터다.

---

## 1. 왜 별도 계통인가

기존 검색 4탭(`/api/bid-announce` 등)은 **요청마다 나라장터를 팬아웃**한다. 그 구조에는
피할 수 없는 비용이 있다.

- 물품/용역/공사/외자가 각각 다른 오퍼레이션이다 → 검색 한 번이 최소 3~4 호출
- 기간 상한(31일)으로 날짜창이 쪼개진다 → N배
- 공고명·품명을 한 요청에 OR 로 못 넣는다 → 2배
- 한 출처라도 죽으면 그만큼 결과가 빈다(`sourceErrors`)

그래서 응답 시간이 초 단위로 흔들리고, 일일 쿼터가 사용자 검색량에 비례해 마른다.
**색인은 이 관계를 끊는다** — 호출량은 공고 발생량에만 비례하고, 검색은 인덱스 질의 한 번이다.

두 계통은 **대체가 아니라 공존**한다. 넓게 훑어야 할 때(D2B·누리장터 포함)는 팬아웃이,
빠르게 좁혀야 할 때는 색인이 맞다.

### 생애주기를 한 테이블에

ERD 의 `category` 가 `{계획, 사전규격, 입찰, 마감}` 인 것이 이 설계의 핵심이다. 넷은 서로 다른
종류가 아니라 **같은 조달 건의 단계**다.

```
계획(발주계획) → 사전규격 → 입찰(공고 게시) → 마감(입찰 마감)
                    └── before_spec_rgst_no 로 이어진다 ──┘
```

원본에서는 이 넷이 각각 다른 오퍼레이션·다른 테이블이라 한 번에 훑을 수 없었다. 색인은 한
테이블이므로 "이 사업이 지금 어느 단계인가"를 조인 하나로 볼 수 있다.

---

## 2. 스키마

`bid_notice` 는 ERD 22개 컬럼 + **추가 3개**다. 추가한 이유는 각각 분명하다.

| 컬럼 | ERD | 비고 |
|---|---|---|
| `id` | 공고번호 (PK) | 계획은 조달요청번호, 사전규격은 사전규격등록번호 |
| `notice_order` | 차수번호 | 세 자리로 정규화(`000`) — 아래 §2.1 |
| `notice_name` | **추가** | 제목 없는 검색 결과 목록은 성립하지 않는다 |
| `category` | 공고 분류 | `ENUM('계획','사전규격','입찰','마감')` |
| `state` | 공고 상태 | `ENUM('취소','재','다시','정정')`, 평시 `NULL` |
| `business_division` | 업종코드 | `ENUM('물품','용역','공사','외자')` |
| `region` | 지역 | 복수는 콤마 구분. **빈 문자열 = 전국(제한 없음)** |
| `demand_institution_code` | 수요기관코드 | 사전규격은 `NULL`(원본이 코드를 안 준다) |
| `demand_institution_name` | **추가** | §2.2 |
| `notice_institution_code` | 공고기관코드 | |
| `notice_institution_name` | **추가** | §2.2 |
| `before_spec_rgst_no` | 사전규격등록번호 | 사전규격 행에서는 자기 `id` 와 같다 |
| `product_list` | 물품목록 | `JSON [{seq,code,name}]` — 원본은 캐럿 문자열 |
| `detail_product_code` | 세부품명번호 | 접두 검색 지원 |
| `lowest_bid_rate` | 낙찰하한율 | `DECIMAL(5,3)`, **백분율 그대로**(88.000 = 88%) |
| `price_detail` | 세부 가격 표 | `JSON {assignedBudget,estimatedPrice,unitPrice,quantity,unit,vat}` |
| `created_date` | 생성일자 | 공고일시 / 접수일시 |
| `close_date` | 마감일자 | 입찰마감 / 의견등록마감 |
| `updated_at` | 업데이트된 시각 | **색인에 반영된 때**(원본 변경일시가 아니다) |
| `officer_name` / `officer_contact` | 담당자명·연락처 | |
| `notice_body` | 공고 본문 | 검색 대상 통합 텍스트 — 아래 §2.3 |
| `ai_summary` | AI 요약 | **적재기가 절대 덮어쓰지 않는다** |
| `attachment_urls` | 첨부파일 URL | `JSON [{name,url}]` |
| `source_url` | 원본 공고 URL | |

### 2.1 PK 가 공고번호 하나인 것의 결과

ERD 의 PK 는 `id` 뿐이고 차수는 PK 가 아니다. 따라서 **한 공고에 행은 언제나 하나**이고,
그 행이 최신 차수를 담는다. 정정공고가 여러 번 나도 검색 결과에 같은 공고가 여러 줄로
뜨지 않는 것이 이 선택의 이득이다.

대가는 **차수 역행** 위험이다. 적재는 날짜창을 나눠 도는 탓에 000차가 001차보다 나중에
도착하는 일이 실제로 있고, 그대로 두면 정정 전 내용이 정정 후를 덮어써 화면이 낡은
마감일시를 보여준다. upsert 가 모든 갱신에 가드를 건다.

```sql
notice_name = IF(new.notice_order >= bid_notice.notice_order, new.notice_name, bid_notice.notice_name),
...
-- 반드시 마지막
notice_order = IF(new.notice_order >= bid_notice.notice_order, new.notice_order, bid_notice.notice_order)
```

`notice_order` 대입이 **맨 마지막**이어야 한다. MySQL 은 `ON DUPLICATE KEY UPDATE` 의 대입을
왼쪽부터 차례로 수행하고 뒤 대입이 앞 결과를 보므로, 차수를 먼저 올리면 그 뒤 컬럼들의
가드가 전부 '항상 참'이 되어 무력해진다. `BidNoticeUpsertSqlTest` 가 이 순서를 고정한다.

### 2.2 기관명을 색인에 담는 이유

ERD 는 기관을 **코드로만** 담고 이름은 `dm_institution` 에서 조인해 오기로 했다. 그 설계가
성립하지 않는다는 것이 실측으로 드러났다.

1. `dm_institution` 은 비어 있고(0행), 채울 수 있었던 조달청 API
   `UsrInfoService/getDminsttInfo` 는 **폐기됐다**(`returnReasonCode 12`).
2. **사전규격은 애초에 기관코드가 없다.** 그 오퍼레이션은 기관을 이름으로만 준다.
   색인의 약 1/4 이 여기 해당하므로, `dm_institution` 을 채워도 조인할 키가 없다.
3. 반면 기관명은 **공고 응답에 매번 같이 온다.** 지금까지 버리고 있었을 뿐이다.

정규화를 포기하는 대가(개명 시 재적재)는 실질적으로 없다 — 적재기가 몇 분마다 같은 공고를
다시 읽으며 이름도 함께 갱신한다. `dm_institution` 자체는 다른 화면이 쓰므로 그대로 둔다.

> **공고기관 ≠ 수요기관.** 조달청 대행 공고는 공고기관이 `조달청 강원지방조달청`,
> 수요기관이 `강원대학교` 다. 기관명 검색은 둘을 OR 로 보고, 화면은 다를 때 둘 다 보여준다.

### 2.3 `notice_body` 는 원문이 아니다

목록 API 는 공고 본문 HTML 을 주지 않는다. `notice_body` 는 응답의 서술형 필드
(공고명·기관명·품명·규격·계약방법·업종제한 등)를 이어 붙인 **검색 대상 텍스트**이고,
FULLTEXT 가 실제로 훑는 것이 이 칸이다. 원문 전문이 필요하면 상세 페이지 수집이 별도로 필요하다.

품명은 중복을 없애고 넣는다 — 단가계약 공고는 같은 품명을 수십 줄 반복하는데, 그대로 두면
단어 빈도가 부풀어 관련도 순위를 독차지한다(표시용 `product_list` 는 원본 그대로 둔다).

### 2.4 한국어 전문검색

```sql
FULLTEXT KEY ft_bid_notice_text (notice_name, notice_body) WITH PARSER ngram
```

`ngram` 파서가 **필수**다. 기본 파서는 공백으로만 끊어서 `서버` 로 `노트북서버구매` 를 못 찾는다.
단, ngram 은 토큰 크기(`ngram_token_size`, 기본 2) 미만의 낱말을 통째로 버린다 — 한 글자
검색어는 0건이 아니라 **조용히 무시**된다. 그래서 검색 계층이 한 글자 낱말만 골라
`LIKE` 로 떨어뜨린다.

---

## 3. 적재

`BidNoticeIngestService` 가 **유일하게** 나라장터를 두드린다. 검색 계층에는 API 클라이언트가
주입되지 않아 상류 호출이 구조적으로 불가능하다.

### 3.1 출처 12개

| 계열 | 오퍼레이션 | 결과 `category` |
|---|---|---|
| 입찰공고 4 | `BidPublicInfoService/getBidPblancListInfo{Thng,Servc,Cnstwk,Frgcpt}PPSSrch` | `입찰` 또는 `마감` |
| 발주계획 4 | `PrcrmntReqInfoService/getPrcrmntReqInfoList{Thng,GnrlServc,Cnstwk,Frgcpt}` | `계획` |
| 사전규격 3 | `HrcspSsstndrdInfoService/getPublicPrcureThngInfo{Thng,Servc,Cnstwk}` | `사전규격` |
| 참가가능지역 1 | `BidPublicInfoService/getBidPblancListInfoPrtcptPsblRgn` | (`region` 보강) |

업종 이름이 계열마다 어긋난다 — 입찰공고는 `Servc`, 발주계획은 `GnrlServc` 다. 규칙으로
접지 않고 하나씩 적는다(접으면 언젠가 한 오퍼레이션이 404 로 조용히 빠진다).
사전규격에는 외자 오퍼레이션이 없다.

지역은 **입찰공고를 적재한 뒤에** 돌아야 붙을 대상이 있으므로 순서가 의미를 갖는다.

### 3.2 워터마크와 겹침

출처마다 "어디까지 받았는가"를 `bid_notice_sync_state` 에 남기고 다음 회차는 그 뒤부터 읽는다.
다만 워터마크에서 **30분 뒤로 물러나** 시작한다 — 등록 직후의 공고가 목록 API 에 즉시
나타나지 않는 일이 있어, 딱 이어 붙이면 그 틈에 들어온 건이 영영 색인되지 않는다.
겹쳐 읽은 건은 upsert 가 흡수한다.

조회는 `inqryDiv=1`(**등록일시 기준**)이다. 공고일시 기준으로 두면 과거 공고의 정정이
조회되지 않아 색인이 낡는다 — 쫓는 것은 '변경'이지 '게시'가 아니다.

**실패한 출처의 워터마크는 전진시키지 않는다.** 전진시키면 그 구간이 영원히 빈다.
한 출처가 실패해도 나머지 열한 개는 계속한다.

### 3.3 마감 전이

`입찰` 과 `마감` 은 같은 오퍼레이션에서 오고 **마감일시가 지났는지로만** 갈린다. 즉 적재
시점의 판정은 시간이 흐르면 저절로 틀려진다. 스위퍼가 주기적으로 민다.

```sql
UPDATE bid_notice SET category = '마감', updated_at = NOW(6)
 WHERE category = '입찰' AND close_date IS NOT NULL AND close_date < NOW(6)
```

적재(무거움)와 스위퍼(인덱스 타는 UPDATE 하나)는 **주기가 따로** 돈다. 같이 묶으면 방금
마감된 공고가 최대 한 주기 동안 '입찰'로 검색된다.

### 3.4 설정

```yaml
g2b:
  index:
    enabled: ${INDEX_SYNC_ENABLED:false}        # 주기 적재 on/off
    interval-ms: ${INDEX_SYNC_INTERVAL_MS:600000}
    sweep-ms: ${INDEX_SWEEP_INTERVAL_MS:300000}
    backfill-days: ${INDEX_BACKFILL_DAYS:7}     # 워터마크가 없는 첫 회차만
```

기본값이 꺼짐인 것은 의도다 — **여러 인스턴스가 동시에 켜면 일일 쿼터만 배로 태운다**
(워터마크에 인스턴스 간 잠금이 없다). 운영 인스턴스 하나만 켠다. 꺼져 있어도 검색은
그대로 동작하고 색인이 낡을 뿐이며, 마감 전이는 계속 돈다.

---

## 4. 검색 API

전부 `/api/search/notices` 아래. 봉투는 `docs/api-contract.md` §1.1 의 1번이고,
이 계열에는 `sourceErrors`·`sourceCounts`·`_cached` 가 **없다**(부분 실패 개념이 없다).

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/search/notices` | — | 검색. `{items,totalCount,pageNo,numOfRows}` |
| GET | `/api/search/notices/facets` | — | 같은 조건의 분류·업종·지역·상태별 건수 |
| GET | `/api/search/notices/status` | — | 출처별 워터마크·결과, 분류별 색인 건수 |
| GET | `/api/search/notices/{id}` | — | 상세(본문 전문 포함). 없으면 404 |
| POST | `/api/search/notices/sync` | **앱 키** | 수동 적재. 진행 중이면 409 |

조회는 로컬 DB 만 보므로 비용이 없어 잠그지 않는다. 적재만 쿼터를 태우므로 앱 키를 요구한다.

### 4.1 질의 파라미터

| 파라미터 | 설명 |
|---|---|
| `q` | 검색창 한 줄. 공백으로 나눈 낱말을 **모두 포함**(AND) |
| `andTerms` / `orTerms` / `notTerms` | 3단 태그 입력. `q` 와 함께 오면 합쳐서 건다 |
| `category` | `계획\|사전규격\|입찰\|마감` |
| `state` | `취소\|재\|다시\|정정` |
| `division` | `물품\|용역\|공사\|외자` |
| `region` | 포함 검색. **지역 제한 없는 공고(전국)가 함께 나온다** — §4.3 |
| `insttNm` | 기관명. 공고기관·수요기관 둘 다에서 찾는다 |
| `insttCd` / `dmndInsttCd` | 기관코드 정확일치 |
| `detailProductCode` | 세부품명번호 **접두** 일치(`4110` 으로 상위 분류 훑기) |
| `beforeSpecRgstNo` | 사전규격 → 입찰공고 교차 이동 |
| `officerName` | 담당자명 |
| `fromDate` / `toDate` | 공고일 구간(`YYYY-MM-DD`). 종료일은 그 날 끝까지 포함 |
| `closeFrom` / `closeTo` | 마감일 구간 |
| `activeOnly` | `true` 면 마감 전만 |
| `minAmount` / `maxAmount` | 추정가격 구간 |
| `sort` / `dir` | 아래 §4.2 |
| `page` / `perPage` | `perPage` 는 1..500 로 클램프 |

읽을 수 없는 날짜는 조용히 무시한다(필터 없음) — 400 을 던지면 화면이 통째로 멈춘다.

### 4.2 정렬

`relevance` · `created` · `close` · `name` · `amount` · `updated`.
화이트리스트 밖의 값은 기본값으로 떨어진다(사용자 입력이 `ORDER BY` 에 그대로 들어가지 않는다).

**기본값이 조건에 따라 다르다** — 검색어가 있으면 `relevance`, 없으면 `created`.
검색어 없이 관련도로 정렬하면 전부 0점이라 순서가 사실상 무작위가 되고, 검색어가 있는데
최신순이면 정확히 맞는 공고가 뒤로 밀린다.

모든 정렬 뒤에 `n.id DESC` 가 타이브레이커로 붙는다. 같은 날 올라온 공고가 수백 건이라,
없으면 MySQL 이 페이지마다 다른 순서를 줘서 2페이지에 1페이지 공고가 또 나오거나 건너뛴다.

### 4.3 지역 필터가 전국 공고를 함께 주는 이유

`region` 으로 좁혀도 지역 제한이 없는 공고(빈 문자열)는 함께 나온다. 서울 업체가 참가할 수
있는 전국 공고가 빠지는 편이 훨씬 큰 손해이기 때문이다.

이 규칙은 **화면 표기와 짝**이다. 프론트가 빈 지역을 `전국` 으로 그리지 않으면 사용자는
그 결과를 필터 오작동으로 오해한다.

### 4.4 응답 항목

ERD 컬럼 + 추가분 + 서버 계산분이다.

- JSON 은 **문자열이 아니라 값으로** 펴서 내려준다 — `productList`, `priceDetail`,
  `attachmentUrls`. 문자열로 주면 프론트가 컴포넌트마다 `JSON.parse` 를 부르고,
  그중 한 곳이 빠지면 화면에 원시 JSON 이 뜬다.
- `dday` — 남은 **일수**(시각이 아니라). 마감이 없는 계획 단계는 `null`.
  `D-1` 을 본 사용자가 기대하는 것은 '내일까지'이지 '24시간 남음'이 아니다.
- `estimatedPrice` — `priceDetail.estimatedPrice` 를 꺼내 둔 것(정렬·표시용)
- `bodyPreview` — 목록은 300자, 상세는 `noticeBody` 전문
- `relevance` — 전문검색일 때만

---

## 5. 운영

```bash
# 색인 현황 — 출처별 워터마크·마지막 결과, 분류별 건수
curl -s localhost:8080/api/search/notices/status | jq

# 평시 증분 수동 실행
curl -s -X POST localhost:8080/api/search/notices/sync -H "X-API-Key: $APP_API_KEY"

# 스키마가 바뀌어 기존 행을 다시 채워야 할 때 — 워터마크를 무시하고 N일치 재적재
curl -s -X POST "localhost:8080/api/search/notices/sync?backfillDays=3" -H "X-API-Key: $APP_API_KEY"
```

`backfillDays` 는 **0 이면 평시 증분**이고, 양수면 워터마크를 무시하고 그만큼 거슬러 올라가
다시 읽는다(구간을 넓히는 쪽으로만 작동). 주기 실행은 언제나 0을 넘긴다 — 매 회차가 며칠씩
되읽으면 증분의 의미가 사라진다.

`last_result` 에 가짜 `ok` 를 남기지 않는다. 실패는 사유를 그대로 적는다 — 돌지 않은 적재를
돌았다고 믿게 만드는 것이 운영에서 가장 비싼 거짓말이다.

---

## 6. 알려진 제약

- **`notice_body` 는 공고 원문이 아니다**(§2.3). 원문 전문·첨부 본문이 필요하면 별도 수집이 필요하다.
- **한 글자 검색어는 `LIKE` 로 처리**되어 전문검색 인덱스를 타지 못한다(ngram 토큰 크기).
- **금액 구간 필터는 인덱스를 못 탄다** — `price_detail` JSON 안에 있다. 다른 필터가 먼저
  좁히면 문제없지만, 금액만 지정한 검색은 전체 훑기가 된다. 흔해지면 생성 컬럼으로 승격할 자리다.
- **`region` 은 `VARCHAR(40)`** 이라 지역이 아주 많은 공고는 낱말 경계에서 잘린다(중간에서
  자르면 존재하지 않는 지역명이 만들어져 필터가 영영 안 걸리므로).
- **사전규격의 기관코드는 없다**(원본 미제공). 코드 기준 필터(`insttCd`)에는 걸리지 않고
  이름 기준(`insttNm`)에만 걸린다.
- **적재 인스턴스는 하나여야 한다**(§3.4).
- 한 출처·한 회차의 상한은 20,000행이다. 상한에 걸리면 그 회차는 거기까지만 색인하고
  워터마크가 조금씩 전진하며 다음 회차들이 따라잡는다.

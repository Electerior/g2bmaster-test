# PostgreSQL → MySQL 8 이관 노트

원본 스키마는 PostgreSQL 전용이었다 — `db/schema.sql`(1338줄),
`db/schema_spec_tables.sql`(2650줄), `db/migrations/001..017`. 합계 **113개 테이블**,
약 2770개 컬럼, 149개 인덱스.

이 문서는 MySQL 로 옮기면서 **의미가 달라졌거나, 흉내를 내야 했던 지점**만 적는다.
기계적으로 대응되는 것(`BIGSERIAL` → `AUTO_INCREMENT` 등)은 생략한다.

Flyway 마이그레이션은 `src/main/resources/db/migration/` 에 있고,
원본 마이그레이션 17개는 **재생하지 않고 베이스라인에 접어 넣었다** — MySQL 스키마는
새 출발이지 이력 재현이 아니다.

---

## 1. TEXT 를 VARCHAR 로 일괄 변환하면 테이블이 만들어지지 않는다

MySQL 의 행 정의 상한은 65,535바이트이고 `VARCHAR` 는 전액이 계산에 들어간다
(`TEXT`/`BLOB` 은 9~12바이트 포인터만 차지한다).

`dwt_bid_notice` 는 **147개 컬럼**이다. 전부 `VARCHAR(255) utf8mb4`(1022바이트)로 바꾸면
약 150KB — `ERROR 1118 Row size too large` 로 생성 자체가 실패한다.
같은 문제가 `stg_m_a_s_cntrct_prdct_info_list`(94컬럼), `dwt_order_plan`(72),
`dwt_contract`(71) 등 7개 테이블에 있다.

**적용한 규칙:** 업무 컬럼은 기본 `TEXT`(대용량은 `MEDIUMTEXT`)로 두고,
**PK·UNIQUE·FK·인덱스에 등장하는 컬럼만** `VARCHAR(n)` 으로 승격했다.
크기는 나라장터 도메인에서 가져왔다 — 공고번호류 64, 기관코드 20, 분류번호 20,
사업자번호 32, 해시 `VARCHAR(64) COLLATE ascii_bin`.

## 2. `spec_resolution` 의 기본키를 바꿨다

원본 PK 는 `spec_text TEXT` — 자유 서술 한국어 규격 문장이고 길이 제한이 없다.
MySQL 의 인덱스 키 상한은 utf8mb4 기준 768자라 **그대로는 불가능**하다.

```sql
id             BIGINT AUTO_INCREMENT PRIMARY KEY,
spec_text      MEDIUMTEXT NOT NULL,
spec_text_hash BINARY(32) AS (UNHEX(SHA2(spec_text, 256))) STORED,
UNIQUE KEY uk_spec_resolution_text (spec_text_hash)
```

**따라서 앱 쪽 질의 3곳을 반드시 함께 고쳐야 한다** — `server.js:2048/2053/2069` 의
`WHERE spec_text = $1` 은 `WHERE spec_text_hash = UNHEX(SHA2(?, 256))` 가 된다.
고치지 않으면 조회가 조용히 0건을 돌려준다(오류가 아니라서 더 나쁘다).

## 3. 부분 인덱스 15개 — 3개는 흉내를 내야 한다

MySQL 에는 부분 인덱스(`CREATE INDEX ... WHERE ...`)가 없다.
대부분은 술어가 `IS NOT NULL` 뿐이라 **그냥 떼면 된다**
(MySQL 은 unique 인덱스에서 NULL 을 서로 다른 값으로 보므로 술어가 사실상 공짜다).

**그러나 셋은 떼면 기능이 깨진다.** 저장 생성 컬럼(stored generated column) + UNIQUE 로 대체했다:

1. **`export_job_active_request_idx`** — "요청 해시당 활성 작업 하나"를 강제하던 것.
   없으면 **내보내기 멱등성이 사라진다**(같은 요청이 중복 작업을 만든다).
   → `active_request_hash` 생성 컬럼(활성 상태일 때만 값, 아니면 NULL) + UNIQUE.

2. **`analysis_history_{procurement,pre_spec,bid_notice}_reuse_uidx`**
   (마이그레이션 016, UNIQUE + 부분 + 표현식 `coalesce(analysis_mode,'')`) —
   없으면 `lib/analysis-history.js:131` 의 `ON CONFLICT DO NOTHING` 이
   **조용히 중복 생성기가 된다**. → 세 개의 `reuse_key_*` 생성 컬럼 + UNIQUE.

3. **표현식 인덱스** `(COALESCE(rgst_dt, rcpt_dt) DESC)` 등 — 생성 컬럼으로 펴서
   JPA 가 `@Column(insertable=false, updatable=false)` 로 읽을 수 있게 했다.

## 4. `sync_state` 의 PK

원본은 `(service_id, operation, inqry_div)` 인데 `inqry_div` 가 **nullable** 이다.
MySQL 은 PK 컬럼에 NOT NULL 을 강제한다.
→ `VARCHAR(64) NOT NULL DEFAULT ''`. 원본 마이그레이션 017 이 이미
`COALESCE(inqry_div,'')` 로 정규화해 두어서 앱 쪽 변경은 없다.

## 5. 시각은 `DATETIME(6)` + UTC 저장 (`TIMESTAMP` 아님)

`TIMESTAMPTZ` 255개를 `DATETIME(6)` 으로 옮기고 **UTC 로 저장**한다.

`TIMESTAMP` 를 쓰지 않은 이유는 **2038년 상한** 때문이다. 스키마에
`9999-12-31` 센티넬이 실제로 들어 있고(`dm_shopping_mall_product.end_date`),
계약 만료·유효기간 컬럼이 그 값을 쓴다.

- `spring.jpa.properties.hibernate.jdbc.time_zone` 과 JDBC `connectionTimeZone` 을 맞춘다.
- `AT TIME ZONE 'Asia/Seoul'`(7곳) → `CONVERT_TZ(col,'+00:00','Asia/Seoul')`
- `to_char(x,'YYYY-MM-DD HH24:MI:SS')`(9곳) → `DATE_FORMAT(CONVERT_TZ(...), '%Y-%m-%d %H:%i:%s')`
- MySQL 서버에 시간대 테이블을 적재해야 `CONVERT_TZ` 이 동작한다(`mysql_tzinfo_to_sql`).

## 6. 임베딩 — MySQL 8 에는 대응이 없다

`dwt_notice_scan.title_emb REAL[]`, `spec_emb REAL[]`.

**MySQL 8.0 에는 pgvector 도, `VECTOR` 타입도, ANN 인덱스도 없다.**
(MySQL 9.x / HeatWave 에는 있으나 8.0 Community 에는 없다 — 전제로 삼지 말 것.)

다행히 원본도 pgvector 를 못 써서 **코사인 유사도를 애플리케이션에서 계산**하고 있었다.
SQL 쪽에 옮길 유사도 연산자가 애초에 없다(`<->`, `<=>`, `ivfflat` 사용처 0건).
→ 컬럼은 `JSON` 으로 두고 유사도는 Java 에 남긴다. 말뭉치가 커지면
외부 인덱스(pgvector 사이드카, Qdrant, OpenSearch kNN)가 탈출구지 MySQL 이 아니다.

## 7. `RETURNING` 18곳

MySQL 에 없다. 삽입 후 `LAST_INSERT_ID()` 또는 같은 트랜잭션 안에서 자연키로 재조회한다.
JPA `save()` 로 가면 자연스럽게 사라지는 문제이기도 하다.

주로 작업 큐(`analysis-job-queue.js`)와 내보내기(`export-job-service.js`) 경로에 몰려 있다.

## 8. 작업 청구(claim) 쿼리의 2단계화

원본:

```sql
WITH candidate AS (
  SELECT id FROM analysis_job WHERE status='queued'
  ORDER BY priority DESC, available_at, id
  FOR UPDATE SKIP LOCKED LIMIT 1
) UPDATE analysis_job SET ... FROM candidate ... RETURNING *;
```

`FOR UPDATE SKIP LOCKED` 자체는 MySQL 8 이 지원한다. 문제는 **CTE 를 UPDATE 소스로
쓸 수 없다**는 것. 같은 트랜잭션 안에서 `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1` →
`UPDATE ... WHERE id=?` 2단계로 나눈다(`RETURNING` 문제도 같이 해결된다).

## 9. UPSERT

```sql
-- Postgres
INSERT ... ON CONFLICT (pk) DO UPDATE SET a = EXCLUDED.a;
-- MySQL 8.0.19+
INSERT ... AS new ON DUPLICATE KEY UPDATE a = new.a;
```

의미 차이가 하나 있다: `ON DUPLICATE KEY` 는 **어떤 unique 키든** 걸리면 발동한다
(대상을 지정할 수 없다). 현재는 대상 테이블마다 unique 제약이 하나뿐이라 안전하다.

- `ON CONFLICT DO NOTHING` 에 **`INSERT IGNORE` 를 쓰지 않았다** — FK 위반·값 잘림·NULL
  오류까지 전부 삼켜서 적재기에는 위험하다. `ON DUPLICATE KEY UPDATE id = id` 로 무연산 처리.
- `attachment-cache.js:121` 의 **조건부 upsert**(`WHERE status NOT IN ('done','skip')`)는
  MySQL 이 `ON DUPLICATE KEY UPDATE` 에 `WHERE` 를 못 붙이므로 컬럼마다
  `IF(status NOT IN ('done','skip'), new.x, x)` 로 전개했다.

## 10. 배열과 GIN 인덱스

`api_call_id_list UUID[]` 두 곳 → `JSON` 배열.
`array_append` + `= ANY` 가드는
`IF(JSON_CONTAINS(col, JSON_QUOTE(?)), col, JSON_ARRAY_APPEND(col,'$',?))` 로 바꾼다.

GIN 인덱스 2개는 MySQL 에 대응이 없어 **삭제했다**. 계보(lineage) 컬럼이라 실제로
조회되는 일이 거의 없다.

> **남은 과제**: 제대로 하려면 접합 테이블로 정규화하는 것이 정답이다
> (`pre_spec_api_call(bf_spec_rgst_no, call_id)`) — JPA `@OneToMany` 가 되고
> 읽고-고쳐-쓰기도 사라진다. 다만 그것은 번역이 아니라 설계 변경이라 이번 이관 범위에
> 넣지 않았다. 두 컬럼의 DDL 주석에 이 사실을 적어 두었다.

### 생성 컬럼 표현식에서 걸린 것 두 가지

`CONCAT_WS(0x1f, …)` 대신 `CONCAT_WS(CHAR(31 USING utf8mb4), …)` 를 썼다.
`0x1F` 리터럴은 **바이너리 문자열**이라 표현식 전체를 바이너리로 승격시켜 콜레이션을 흐린다.

그리고 `input_hash` 만 `ascii_bin` 이고 형제 컬럼은 utf8mb4 라
`CONVERT(input_hash USING utf8mb4)` 가 필요하다 — 없으면
"Illegal mix of collations" 로 실패한다.

또 하나: `CONCAT_WS` 는 NULL 인자를 **빈 문자열이 아니라 통째로 건너뛴다.**
`COALESCE` 가 빠진 필드가 하나라도 있으면 서로 다른 키가 조용히 같은 값으로 뭉개진다.
가드 없는 필드는 전부 감쌌다.

## 11. ⚠️ 적재기의 숨은 함정: 카탈로그 리플렉션

`lib/g2b-loader.js` 는 런타임에 `information_schema`/`pg_catalog` 를 읽어
컬럼 타입에 따라 값을 변환한다(`coerce()`).

**MySQL 은 `data_type` 문자열이 다르다.** 원본은 `/numeric/` 과 `'boolean'` 을 매칭하는데
MySQL 은 `decimal` 과 `tinyint` 를 돌려준다 → **숫자·불리언 변환이 조용히 깨진다.**
(오류가 아니라 잘못된 값이 들어간다는 점이 나쁘다.)

Spring 이 JPA 엔티티로 적재를 넘겨받으면 이 리플렉션 전체가 사라진다.
그때까지 남겨 두는 경로가 있다면 정규식을 반드시 고칠 것.

## 12. 삭제한 것

- **머티리얼라이즈드 뷰 3개** (`mv_bid_contract_funnel`, `mv_supplier_performance`,
  `mv_monthly_procurement`) — MySQL 에 MV 가 없다. 그리고 **`lib/`·`server.js`
  어디서도 참조되지 않는다.** 필요해지면 실테이블 + 스케줄 `INSERT ... SELECT` +
  `RENAME TABLE` 원자 교체로 만든다(= `REFRESH CONCURRENTLY` 대체).
- **PL/pgSQL 함수 3개** (`refresh_materialized_views`, `get_next_sync_window`,
  `upsert_institution`) — JS 에서 호출하지 않는다. 로직은 이미 `lib/` 안에 있다.
- **`CREATE EXTENSION "uuid-ossp"`, `"pgcrypto"`** — UUID 는 애플리케이션에서 생성한다.

## 13. 옮긴 것 (원본에 없던 자리에서)

`attachment_cache` 의 DDL 은 원본에서 **런타임에**(`lib/attachment-cache.js:65`)
만들어지고 있었다. Flyway 로 옮겼다. 시각 컬럼이 `BIGINT` epoch-millis 인 것은
**의도적 설계이므로 그대로 뒀다** — better-sqlite3 시절부터의 결정이고,
시간대 해석을 아예 배제하려는 것이다. `timestamptz` 로 "개선"하지 말 것.

## 14. 전문검색은 옮길 것이 없었다

원본이 의도적으로 쓰지 않는다. 마이그레이션 008 주석에 "한국어 형태소 사전이 없어
`tsvector` 를 쓰지 않았고 `pg_trgm` 은 향후 과제"라고 적혀 있다.
`tsvector`/`to_tsquery`/`similarity(`/`gin_trgm_ops` 사용처는 **0건**이다.

현재 검색은 `ILIKE '%term%'` 이고, MySQL 기본 콜레이션 `utf8mb4_0900_ai_ci` 가
이미 대소문자를 무시하므로 **`LIKE` 로 바꾸기만 하면 된다.**
나중에 속도가 문제되면 `FULLTEXT ... WITH PARSER ngram` 이 올바른 대체재다.

## 15. 식별자

DDL 에 인용된 혼합 케이스 식별자는 **0건**이다 — 전부 소문자 snake_case 라
`lower_case_table_names` 는 쟁점이 아니다(리눅스에서 `1` 로 두고 개발 장비도 맞출 것).

다만 `lib/pre-spec-store.js` 등의 **`AS "camelCase"` 결과 별칭 21종**은 주의해야 한다 —
MySQL 은 `"..."` 를 식별자가 아니라 **문자열 리터럴**로 파싱한다(`ANSI_QUOTES` 미설정 시).
Java DTO 프로젝션으로 옮기면 문제가 사라진다.

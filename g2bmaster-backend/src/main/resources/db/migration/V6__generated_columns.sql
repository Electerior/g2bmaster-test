-- ============================================================
-- V6__generated_columns.sql
-- 부분 인덱스 / 표현식 인덱스를 생성 컬럼(generated column)으로 흉내낸다.
--
-- 기본은 STORED 다. 단 analysis_history 의 재사용 키 3종만 VIRTUAL 이며,
-- 그 이유는 §2 머리말에 적어 두었다(FK 참조 동작 제한 — 되돌리면 마이그레이션이 죽는다).
--
-- 부분 인덱스 15종 중 대부분은 술어만 떼고 일반 인덱스로 바꿔도 의미가 유지되며,
-- 그것들은 V1~V5 에서 이미 처리했다. 이 파일에는 **술어를 떼면 의미가 깨지는 것만** 담는다.
--
--   1) export_job_active_request_idx        — 없애면 내보내기 멱등성 보장이 사라진다
--   2) analysis_history_*_reuse_uidx (3종)  — 없애면 ON CONFLICT DO NOTHING 이 중복 생성기가 된다
--   3) 표현식 인덱스 COALESCE(...) 2종       — MySQL 함수 인덱스보다 저장 생성 컬럼이 JPA 친화적이다
--
-- 왜 생성 컬럼인가:
--   MySQL 에는 부분 인덱스가 없다. 하지만 UNIQUE 인덱스에서 NULL 을 서로 다른 값으로 보므로,
--   "술어를 만족할 때만 값을, 아니면 NULL 을" 내는 컬럼을 만들어 UNIQUE 를 걸면
--   Postgres 의 부분 UNIQUE 와 정확히 같은 제약이 된다.
--
-- 구현 주의:
--   * 구분자는 0x1F(unit separator)다. 여기서는 CHAR(31 USING utf8mb4) 로 쓴다 —
--     0x1F 리터럴은 binary 문자열이라 utf8mb4 컬럼과 섞이면 결과 타입이 binary 로 승격되어
--     콜레이션이 흐려진다. 의미는 동일하고 콜레이션만 깨끗해진다.
--   * CONCAT_WS 는 NULL 인수를 **건너뛴다**(빈 문자열로 넣지 않는다). 그래서 필드를 하나
--     빠뜨리면 서로 다른 키가 같은 문자열로 뭉개진다. IF 가드가 보장하지 않는 컬럼은
--     반드시 COALESCE 로 감싸 두었다 — 원본 migration 016 의 coalesce 와 같은 자리다.
--   * input_hash 는 ascii_bin 이고 나머지는 utf8mb4 라 그냥 이으면 "Illegal mix of collations"
--     가 난다. CONVERT(... USING utf8mb4) 로 맞춘다.
--   * 생성 컬럼 자체는 utf8mb4_bin 으로 둔다. Postgres text 비교는 정확 비교(대소문자 구분)이고,
--     기본 콜레이션 utf8mb4_0900_ai_ci 는 대소문자를 무시하므로 유일성 범위가 넓어져 버린다.
-- ============================================================


-- ============================================================
-- 1. export_job — "요청 해시당 활성 작업 하나" (015_export_job_runtime.sql)
--
-- 원본:
--   CREATE UNIQUE INDEX export_job_active_request_idx ON export_job (request_hash)
--     WHERE request_hash IS NOT NULL
--       AND status IN ('queued', 'analyzing', 'generating');
--
-- 술어를 떼고 그냥 UNIQUE (request_hash) 로 만들면 완료된 과거 작업이 같은 요청의
-- 재실행을 영구히 막아 버린다. 반대로 인덱스를 없애면 같은 요청이 동시에 여러 번
-- 큐에 들어간다. 활성 상태일 때만 값을 내는 컬럼에 UNIQUE 를 걸어야 둘 다 피한다.
-- ============================================================
ALTER TABLE `export_job`
  ADD COLUMN `active_request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      IF(`status` IN ('queued', 'analyzing', 'generating'), `request_hash`, NULL)
    ) STORED
    COMMENT '활성 상태일 때만 request_hash, 아니면 NULL — 부분 UNIQUE 대체';

ALTER TABLE `export_job`
  ADD UNIQUE KEY `uk_export_job_active_request` (`active_request_hash`);


-- ============================================================
-- 2. analysis_history — 재사용 유일키 3종 (016_analysis_history_uniqueness.sql)
--
-- 검색과 내보내기가 같은 deep 분석을 동시에 끝내도 이력은 하나만 남아야 한다.
-- 원본은 부분 + 표현식 UNIQUE 인덱스 3개였다. 예:
--   CREATE UNIQUE INDEX analysis_history_procurement_reuse_uidx ON analysis_history (
--     prcrmnt_req_no, input_hash, prompt_version, (coalesce(analysis_mode,'')), deep_mode)
--     WHERE prcrmnt_req_no IS NOT NULL AND input_hash IS NOT NULL AND prompt_version IS NOT NULL;
--
-- ⚠ 이것이 없으면 analysis-history.js:131 의 ON CONFLICT DO NOTHING
--   (MySQL 에서는 ON DUPLICATE KEY UPDATE id = id)이 아무것도 막지 않아
--   조용히 중복 생성기가 된다. 분석 캐시가 통째로 무력화된다.
--
-- 세 엔티티(조달요청 / 사전규격 / 입찰공고)가 서로 다른 자연키를 쓰므로 컬럼도 3개다.
-- 한 행은 셋 중 최대 하나에만 값을 갖는다(나머지는 IF 가드가 NULL 을 낸다).
--
-- ⚠⚠ 이 세 컬럼만 STORED 가 아니라 VIRTUAL 이다. 되돌리지 말 것.
--   MySQL 은 STORED 생성 컬럼의 기반(base) 컬럼에 ON DELETE/UPDATE 가
--   CASCADE / SET NULL / SET DEFAULT 인 FK 를 허용하지 않는다.
--   analysis_history 에는 그런 FK 가 둘 있고, 하필 재사용 키의 기반 컬럼이다:
--     fk_analysis_history_prcrmnt_req (prcrmnt_req_no) ... ON DELETE SET NULL
--     fk_analysis_history_pre_spec    (bf_spec_rgst_no) ... ON DELETE SET NULL
--   STORED 로 두면 ALTER 가 ERROR 1215 "Cannot add foreign key constraint" 로 죽는다.
--   같은 제한이 VIRTUAL 에는 ON UPDATE 에만 걸리므로 ON DELETE SET NULL 은 통과한다.
--
--   VIRTUAL 이어도 제약은 그대로다 — InnoDB 는 가상 생성 컬럼에 UNIQUE 보조 인덱스를
--   지원한다. 실측 확인: 중복 삽입은 ERROR 1062 로 거부되고, 부모 행이 지워져
--   ON DELETE SET NULL 이 발동하면 이 컬럼도 NULL 로 정확히 재계산된다.
--   (STORED 가 필요했다면 대안은 마이그레이션 안에서 FOREIGN_KEY_CHECKS 를 끄는 것인데,
--    스키마 마이그레이션에서 FK 검사를 끄는 쪽이 훨씬 나쁜 거래다.)
-- ============================================================

-- 2-1. 조달요청 (procurement_request)
--      최대 길이: 64×4 + deep_mode + 구분자 4 = 약 264자 -> VARCHAR(320) (인덱스 1282바이트 < 3072)
ALTER TABLE `analysis_history`
  ADD COLUMN `reuse_key_pr` VARCHAR(320) COLLATE utf8mb4_bin
    GENERATED ALWAYS AS (
      IF(`prcrmnt_req_no` IS NOT NULL AND `input_hash` IS NOT NULL AND `prompt_version` IS NOT NULL,
         CONCAT_WS(CHAR(31 USING utf8mb4),
                   `prcrmnt_req_no`,
                   CONVERT(`input_hash` USING utf8mb4),
                   `prompt_version`,
                   COALESCE(`analysis_mode`, ''),
                   `deep_mode`),
         NULL)
    ) VIRTUAL
    COMMENT '조달요청 분석 재사용 키 — 부분+표현식 UNIQUE 대체 (migration 016)';

ALTER TABLE `analysis_history`
  ADD UNIQUE KEY `uk_analysis_history_reuse_pr` (`reuse_key_pr`);


-- 2-2. 사전규격 (pre_spec)
ALTER TABLE `analysis_history`
  ADD COLUMN `reuse_key_ps` VARCHAR(320) COLLATE utf8mb4_bin
    GENERATED ALWAYS AS (
      IF(`bf_spec_rgst_no` IS NOT NULL AND `input_hash` IS NOT NULL AND `prompt_version` IS NOT NULL,
         CONCAT_WS(CHAR(31 USING utf8mb4),
                   `bf_spec_rgst_no`,
                   CONVERT(`input_hash` USING utf8mb4),
                   `prompt_version`,
                   COALESCE(`analysis_mode`, ''),
                   `deep_mode`),
         NULL)
    ) VIRTUAL
    COMMENT '사전규격 분석 재사용 키 — 부분+표현식 UNIQUE 대체 (migration 016)';

ALTER TABLE `analysis_history`
  ADD UNIQUE KEY `uk_analysis_history_reuse_ps` (`reuse_key_ps`);


-- 2-3. 입찰공고 (bid_notice) — 차수(bid_ntce_ord)가 키에 하나 더 들어간다.
--      원본도 (coalesce(bid_ntce_ord,'')) 로 표현식 컬럼이었다.
--      최대 길이: 64×5 + deep_mode + 구분자 5 = 약 329자 -> VARCHAR(384) (인덱스 1538바이트 < 3072)
ALTER TABLE `analysis_history`
  ADD COLUMN `reuse_key_bn` VARCHAR(384) COLLATE utf8mb4_bin
    GENERATED ALWAYS AS (
      IF(`bid_ntce_no` IS NOT NULL AND `input_hash` IS NOT NULL AND `prompt_version` IS NOT NULL,
         CONCAT_WS(CHAR(31 USING utf8mb4),
                   `bid_ntce_no`,
                   COALESCE(`bid_ntce_ord`, ''),
                   CONVERT(`input_hash` USING utf8mb4),
                   `prompt_version`,
                   COALESCE(`analysis_mode`, ''),
                   `deep_mode`),
         NULL)
    ) VIRTUAL
    COMMENT '입찰공고 분석 재사용 키 — 부분+표현식 UNIQUE 대체 (migration 016)';

ALTER TABLE `analysis_history`
  ADD UNIQUE KEY `uk_analysis_history_reuse_bn` (`reuse_key_bn`);


-- ============================================================
-- 3. 표현식 인덱스 2종 -> 저장 생성 컬럼 + 인덱스
-- ============================================================

-- 3-1. dwt_pre_specification (010_pre_spec_search.sql)
--      원본: CREATE INDEX dwt_pre_spec_registered_idx
--              ON dwt_pre_specification (COALESCE(rgst_dt, rcpt_dt) DESC);
--      사전규격 DB 검색은 G2B 등록일 기준이다. rgst_dt 가 없는 과거 행만 rcpt_dt 를 쓴다.
--      MySQL 8 도 함수 인덱스를 지원하지만, 저장 생성 컬럼으로 두면 JPA 가
--      읽기 전용 필드로 그대로 매핑할 수 있어(@Column(insertable=false, updatable=false))
--      정렬 기준을 엔티티에서 이름으로 부를 수 있다.
ALTER TABLE `dwt_pre_specification`
  ADD COLUMN `registered_at` DATETIME(6)
    GENERATED ALWAYS AS (COALESCE(`rgst_dt`, `rcpt_dt`)) STORED
    COMMENT 'G2B 등록일 (rgst_dt 없으면 rcpt_dt) — 표현식 인덱스 대체';

ALTER TABLE `dwt_pre_specification`
  ADD KEY `dwt_pre_spec_registered_idx` (`registered_at` DESC);


-- 3-2. dwt_procurement_request (014_procurement_request_provenance.sql)
--      원본: CREATE INDEX dwt_procurement_request_received_idx
--              ON dwt_procurement_request (COALESCE(rcpt_dt, inpt_dt) DESC);
--      ⚠ 컬럼명이 received_at 인 것은 의도다 — raw_* 의 received_at 과 이름은 같지만
--        다른 테이블이고, 조달요청의 "접수" 시각을 뜻한다.
ALTER TABLE `dwt_procurement_request`
  ADD COLUMN `received_at` DATETIME(6)
    GENERATED ALWAYS AS (COALESCE(`rcpt_dt`, `inpt_dt`)) STORED
    COMMENT '접수일시 (rcpt_dt 없으면 inpt_dt) — 표현식 인덱스 대체';

ALTER TABLE `dwt_procurement_request`
  ADD KEY `dwt_procurement_request_received_idx` (`received_at` DESC);

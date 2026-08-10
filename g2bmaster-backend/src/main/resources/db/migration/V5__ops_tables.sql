-- ============================================================
-- V5__ops_tables.sql
-- 운영/애플리케이션 테이블 11종.
-- 원본: db/migrations/004~008, 011, 012, 013, 014, 015
--       + lib/attachment-cache.js:65 (런타임에 만들던 DDL 을 Flyway 로 옮긴다)
--
-- Postgres CREATE TYPE ... AS ENUM 은 이 스키마에 없다. 전부 TEXT + CHECK (col IN (...)) 이고,
-- MySQL 8.0.16+ 는 CHECK 를 실제로 강제하므로 VARCHAR(n) + CHECK 로 그대로 옮긴다.
-- (JPA @Enumerated(EnumType.STRING) 과도 맞는다.)
--
-- 부분 인덱스는 전부 술어를 떼고 일반 인덱스로 바꿨다. 술어를 떼면 의미가 깨지는
-- 3종(export_job 활성 요청 UNIQUE, analysis_history 재사용 UNIQUE 3개)만
-- V6 에서 저장 생성 컬럼으로 흉내낸다.
-- ============================================================


-- ============================================================
-- 공고별 첨부 판정 결과 (004_scan_and_schedule.sql + 005_embeddings.sql)
--
-- 첨부 전수조사를 '화면 열 때'에서 '적재할 때'로 옮기기 위한 저장소.
-- 지금까지는 브라우저가 결과 목록을 50건씩 끊어 /api/scan-attachments 를 반복 호출했다.
-- 실제 연산은 서버가 했지만 진행을 브라우저가 몰았기 때문에, 창을 닫으면 멈추고
-- 사용자마다 같은 공고를 다시 훑었으며 목록이 크면 볼 때마다 느렸다.
-- 적재 때 미리 판정해 여기에 넣어두면 화면은 조회만 하면 된다.
--
-- 추출한 본문 자체는 attachment_cache 에 이미 있으므로 여기에는 '판정'만 남긴다
-- (본문을 두 곳에 두면 갱신이 어긋난다).
-- ============================================================
CREATE TABLE `dwt_notice_scan` (
  `bid_ntce_no`       VARCHAR(64) NOT NULL,
  `bid_ntce_ord`      VARCHAR(64) NOT NULL DEFAULT '',
  `bid_tab`           VARCHAR(64) COMMENT 'pre-spec / bid-announce 등 어느 화면에서 온 공고인지',
  `scanned_at`        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `file_count`        INT NOT NULL DEFAULT 0,
  `text_chars`        INT NOT NULL DEFAULT 0,
  `spec_name`         TEXT COMMENT '규격서로 고른 파일명',
  `spec_confidence`   TEXT COMMENT 'confirmed / heuristic / estimated / user-selected',
  `nested_tables`     INT NOT NULL DEFAULT 0 COMMENT '표 안의 표 개수(품목 오독 위험 신호)',
  `blocking_excluded` TINYINT(1) NOT NULL DEFAULT 0,
  `blocking_reasons`  JSON NOT NULL DEFAULT (JSON_ARRAY()),
  `scan_error`        TEXT,

  -- ---- 005_embeddings.sql -----------------------------------------------
  -- 유사도 검색용 임베딩을 적재 때 미리 계산해 둔다. 조회할 때마다 색인을 새로 세우면
  -- 공유 서버에서 낭비다(문서 인코딩이 비용의 대부분). 조회 시에는 질의문 1건만 인코딩한다.
  --
  -- ⚠ Postgres 쪽도 pgvector 를 못 써서 real[] 로 두고 코사인을 애플리케이션에서 계산했다.
  --   MySQL 8.0 Community 에는 pgvector 도, VECTOR 타입도, ANN 인덱스도 없다.
  --   따라서 JSON(float 배열)으로 두고 유사도 계산은 Java 에 그대로 남긴다 —
  --   SQL 로 옮길 유사도 연산자가 애초에 존재하지 않는다.
  `title_emb`         JSON COMMENT '공고 제목 임베딩 (원본 REAL[])',
  `spec_emb`          JSON COMMENT '규격서 본문 임베딩 (원본 REAL[])',
  `emb_model`         TEXT COMMENT '어느 모델로 만들었는지 — 모델이 바뀌면 재계산해야 한다',
  `embedded_at`       DATETIME(6),

  PRIMARY KEY (`bid_ntce_no`, `bid_ntce_ord`),
  KEY `idx_notice_scan_blocking` (`blocking_excluded`),
  KEY `idx_notice_scan_at` (`scanned_at`),
  -- 아직 임베딩이 없는 공고를 고르는 조회가 잦다.
  -- 원본은 WHERE embedded_at IS NULL 부분 인덱스. 술어를 떼고 일반 인덱스로 둔다.
  KEY `idx_notice_scan_needs_emb` (`embedded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 자동 갱신 예약 (004_scan_and_schedule.sql)
-- 하루에 여러 번 가능하도록 '시각' 단위로 여러 행을 둔다.
-- cron 표현식을 쓰지 않은 이유: 필요한 건 '하루 중 몇 시 몇 분'뿐이고,
-- cron 문법은 화면에서 편집하기도 검증하기도 번거롭다.
-- ============================================================
CREATE TABLE `sync_schedule` (
  `id`               BIGINT NOT NULL AUTO_INCREMENT,
  `time_of_day`      CHAR(5) NOT NULL COMMENT 'HH:MM 24시간, 서버 로컬 시각',
  `scope`            VARCHAR(64) NOT NULL DEFAULT 'important' COMMENT 'curated / important / all',
  `lookback_days`    INT NOT NULL DEFAULT 3 COMMENT '실행 시점 기준 최근 N일 적재',
  `with_attachments` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '첨부 판정까지 함께',
  `enabled`          TINYINT(1) NOT NULL DEFAULT 1,
  `last_run_at`      DATETIME(6),
  `last_result`      TEXT,
  `created_at`       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_sync_schedule_slot` (`time_of_day`, `scope`),
  -- Postgres 의 `time_of_day ~ '...'` 정규식 연산자를 REGEXP_LIKE 로 옮긴다.
  -- 이 패턴에는 \d 가 없어 ICU 정규식으로도 그대로 통한다.
  CONSTRAINT `sync_schedule_time_chk`
    CHECK (REGEXP_LIKE(`time_of_day`, '^([01][0-9]|2[0-3]):[0-5][0-9]$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 분석 결과 이력 (006 + 012 + 013)
--
-- 지금까지는 분석을 돌려도 결과가 응답으로만 나가고 어디에도 남지 않아,
-- 같은 공고를 다시 보려면 처음부터 다시 돌려야 했다(첫 분석은 보통 1~4분 걸린다).
-- 서버가 연산을 소유하는 구조이므로 결과도 서버에 남는 것이 맞다 —
-- 그래야 다른 사람이 같은 공고를 열었을 때 바로 볼 수 있다.
-- ============================================================
CREATE TABLE `analysis_history` (
  `id`                BIGINT NOT NULL AUTO_INCREMENT,
  `bid_ntce_no`       VARCHAR(64),
  `bid_ntce_ord`      VARCHAR(64) DEFAULT '',
  `title`             TEXT,
  `instt_nm`          TEXT,
  `amount`            BIGINT,
  `source`            TEXT COMMENT 'auto-file / provided-spec / meta / upload',
  `spec_name`         TEXT COMMENT '근거로 삼은 문서',
  `spec_confidence`   TEXT,
  `nested_tables`     INT NOT NULL DEFAULT 0,
  `blocking_excluded` TINYINT(1) NOT NULL DEFAULT 0,
  `summary`           TEXT COMMENT '요약 본문(목록에서 미리보기)',
  `result`            JSON COMMENT '분석 응답 전문 — 다시 열 때 그대로 복원',
  `created_at`        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  -- ---- 012_pre_spec_analysis_history.sql --------------------------------
  -- 기존 입찰공고 이력을 유지하면서 사전규격 분석을 별도 자연키로 연결한다.
  `bf_spec_rgst_no`   VARCHAR(64),
  `deep_mode`         TINYINT(1) NOT NULL DEFAULT 0,
  `analysis_mode`     VARCHAR(64),
  `llm_model`         TEXT,

  -- ---- 013_ai_analysis_export_jobs.sql ----------------------------------
  -- 발주계획 자연키와 재사용 판정 필드.
  `prcrmnt_req_no`    VARCHAR(64),
  `input_hash`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin COMMENT 'hex 해시',
  `prompt_version`    VARCHAR(64),

  PRIMARY KEY (`id`),
  KEY `idx_analysis_history_at` (`created_at` DESC),
  KEY `idx_analysis_history_notice` (`bid_ntce_no`, `bid_ntce_ord`),
  -- 아래 3종은 원본이 부분 인덱스였다(WHERE ... IS NOT NULL). 술어를 떼도 의미가 같다.
  KEY `analysis_history_pre_spec_idx` (`bf_spec_rgst_no`, `created_at` DESC),
  KEY `analysis_history_procurement_request_idx` (`prcrmnt_req_no`, `created_at` DESC),
  KEY `analysis_history_reuse_idx` (`input_hash`, `prompt_version`, `analysis_mode`, `created_at` DESC),

  CONSTRAINT `fk_analysis_history_pre_spec` FOREIGN KEY (`bf_spec_rgst_no`)
    REFERENCES `dwt_pre_specification` (`bf_spec_rgst_no`) ON DELETE SET NULL,
  CONSTRAINT `fk_analysis_history_prcrmnt_req` FOREIGN KEY (`prcrmnt_req_no`)
    REFERENCES `dwt_procurement_request` (`prcrmnt_req_no`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
-- ⚠ 016_analysis_history_uniqueness.sql 의 부분+표현식 UNIQUE 3종은 V6 에서 만든다.
--   그것이 없으면 analysis-history.js:131 의 ON CONFLICT DO NOTHING
--   (MySQL 에서는 ON DUPLICATE KEY UPDATE id = id) 이 조용히 중복 생성기가 된다.


-- ============================================================
-- 영속 AI 작업 큐 (013_ai_analysis_export_jobs.sql)
-- 검색과 엑셀 내보내기에서 공통으로 사용한다.
-- ============================================================
CREATE TABLE `analysis_job` (
  `id`                  BIGINT NOT NULL AUTO_INCREMENT,
  `entity_type`         VARCHAR(64) NOT NULL,
  `entity_id`           VARCHAR(64) NOT NULL,
  `entity_ord`          VARCHAR(64) NOT NULL DEFAULT '',
  `input_hash`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `prompt_version`      VARCHAR(64) NOT NULL,
  `analysis_mode`       VARCHAR(64) NOT NULL DEFAULT 'deep',
  `payload`             JSON NOT NULL DEFAULT (JSON_OBJECT()),
  `status`              VARCHAR(64) NOT NULL DEFAULT 'queued',
  `priority`            SMALLINT NOT NULL DEFAULT 0,
  `attempt_count`       INT NOT NULL DEFAULT 0,
  `max_attempts`        INT NOT NULL DEFAULT 3,
  `worker_id`           TEXT,
  `available_at`        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lease_expires_at`    DATETIME(6),
  `heartbeat_at`        DATETIME(6),
  `started_at`          DATETIME(6),
  `completed_at`        DATETIME(6),
  `last_error`          TEXT,
  `analysis_history_id` BIGINT,
  `created_at`          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`id`),
  -- 키 길이: VARCHAR(64) utf8mb4 × 5 + ascii(64) = 약 1354바이트 < 3072 ✔
  UNIQUE KEY `analysis_job_identity_idx`
    (`entity_type`, `entity_id`, `entity_ord`, `input_hash`, `prompt_version`, `analysis_mode`),
  -- 원본은 (priority DESC, available_at, id) WHERE status='queued' 부분 인덱스였다.
  -- 술어 컬럼을 선두에 붙여 같은 선택도를 얻는다. MySQL 8 은 내림차순 인덱스 컬럼을 네이티브 지원한다.
  KEY `analysis_job_claim_idx` (`status`, `priority` DESC, `available_at`, `id`),
  -- 원본은 (lease_expires_at) WHERE status='running'.
  KEY `analysis_job_lease_idx` (`status`, `lease_expires_at`),

  CONSTRAINT `fk_analysis_job_history` FOREIGN KEY (`analysis_history_id`)
    REFERENCES `analysis_history` (`id`) ON DELETE SET NULL,
  CONSTRAINT `analysis_job_entity_type_chk`
    CHECK (`entity_type` IN ('procurement_request', 'pre_spec', 'bid_notice')),
  CONSTRAINT `analysis_job_status_chk`
    CHECK (`status` IN ('queued', 'running', 'completed', 'failed', 'cancelled')),
  CONSTRAINT `analysis_job_attempt_count_chk` CHECK (`attempt_count` >= 0),
  CONSTRAINT `analysis_job_max_attempts_chk` CHECK (`max_attempts` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 비동기 내보내기 작업 (013 + 015_export_job_runtime.sql)
-- 015 는 요청 멱등성, 재시작 복구, 원문 스냅샷을 보강한다.
-- ============================================================
CREATE TABLE `export_job` (
  `id`                    CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL
                          COMMENT 'gen_random_uuid() 대체 — 애플리케이션이 생성',
  `entity_type`           VARCHAR(64) NOT NULL,
  `filters`               JSON NOT NULL DEFAULT (JSON_OBJECT()),
  `status`                VARCHAR(64) NOT NULL DEFAULT 'queued',
  `total_count`           INT NOT NULL DEFAULT 0,
  `analyzed_count`        INT NOT NULL DEFAULT 0,
  `failed_count`          INT NOT NULL DEFAULT 0,
  `exported_count`        INT NOT NULL DEFAULT 0,
  `eta_seconds`           INT,
  `requested_by`          TEXT,
  `result_path`           TEXT,
  `result_name`           TEXT,
  `last_error`            TEXT,
  `started_at`            DATETIME(6),
  `completed_at`          DATETIME(6),
  `expires_at`            DATETIME(6),
  `created_at`            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  -- ---- 015_export_job_runtime.sql ---------------------------------------
  `request_key`           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin COMMENT 'hex 해시',
  `request_hash`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin COMMENT 'hex 해시',
  `source_path`           TEXT,
  `heartbeat_at`          DATETIME(6),
  `snapshot_completed_at` DATETIME(6),
  `skipped_count`         INT NOT NULL DEFAULT 0,

  PRIMARY KEY (`id`),
  KEY `export_job_status_idx` (`status`, `created_at`),
  -- 원본은 WHERE request_key IS NOT NULL 부분 UNIQUE 였다.
  -- MySQL 은 UNIQUE 인덱스에서 NULL 을 서로 다른 값으로 보므로 술어가 사실상 공짜다.
  UNIQUE KEY `export_job_request_key_idx` (`request_key`),

  CONSTRAINT `export_job_entity_type_chk`
    CHECK (`entity_type` IN ('procurement_request', 'pre_spec', 'bid_notice')),
  CONSTRAINT `export_job_status_chk`
    CHECK (`status` IN ('queued', 'analyzing', 'generating', 'completed', 'failed', 'cancelled', 'expired')),
  CONSTRAINT `export_job_total_count_chk` CHECK (`total_count` >= 0),
  CONSTRAINT `export_job_analyzed_count_chk` CHECK (`analyzed_count` >= 0),
  CONSTRAINT `export_job_failed_count_chk` CHECK (`failed_count` >= 0),
  CONSTRAINT `export_job_exported_count_chk` CHECK (`exported_count` >= 0),
  CONSTRAINT `export_job_skipped_count_chk` CHECK (`skipped_count` >= 0),
  CONSTRAINT `export_job_eta_seconds_chk` CHECK (`eta_seconds` IS NULL OR `eta_seconds` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
-- ⚠ export_job_active_request_idx("요청 해시당 활성 작업 하나")는 V6 에서 만든다.
--   그것이 없으면 내보내기 멱등성 보장이 사라진다.


-- ============================================================
-- 내보내기 작업 항목 (013 + 015)
-- ============================================================
CREATE TABLE `export_job_item` (
  `export_job_id`       CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  -- `position` 은 MySQL 예약어는 아니지만 함수명이다. DDL 에서 백틱을 씌워 둔다.
  `position`            INT NOT NULL,
  `entity_type`         VARCHAR(64) NOT NULL,
  `entity_id`           VARCHAR(64) NOT NULL,
  `entity_ord`          VARCHAR(64) NOT NULL DEFAULT '',
  `analysis_job_id`     BIGINT,
  `analysis_history_id` BIGINT,
  `status`              VARCHAR(64) NOT NULL DEFAULT 'pending',
  `last_error`          TEXT,
  `created_at`          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  -- ---- 015_export_job_runtime.sql ---------------------------------------
  `item_payload`        JSON NOT NULL DEFAULT (JSON_OBJECT()),
  `input_hash`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
  `prompt_version`      VARCHAR(64),

  -- 키 길이: CHAR(36) ascii + VARCHAR(64) utf8mb4 × 3 = 약 807바이트 < 3072 ✔
  PRIMARY KEY (`export_job_id`, `entity_type`, `entity_id`, `entity_ord`),
  UNIQUE KEY `export_job_item_position_idx` (`export_job_id`, `position`),
  KEY `export_job_item_progress_idx` (`export_job_id`, `status`),
  -- 원본은 WHERE analysis_job_id IS NOT NULL 부분 인덱스. 술어를 떼도 같다.
  KEY `export_job_item_analysis_idx` (`analysis_job_id`),
  KEY `export_job_item_history_idx` (`analysis_history_id`),

  CONSTRAINT `fk_export_job_item_job` FOREIGN KEY (`export_job_id`)
    REFERENCES `export_job` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_export_job_item_analysis_job` FOREIGN KEY (`analysis_job_id`)
    REFERENCES `analysis_job` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_export_job_item_history` FOREIGN KEY (`analysis_history_id`)
    REFERENCES `analysis_history` (`id`) ON DELETE SET NULL,
  CONSTRAINT `export_job_item_position_chk` CHECK (`position` >= 0),
  CONSTRAINT `export_job_item_entity_type_chk`
    CHECK (`entity_type` IN ('procurement_request', 'pre_spec', 'bid_notice')),
  CONSTRAINT `export_job_item_status_chk`
    CHECK (`status` IN ('pending', 'queued', 'running', 'completed', 'failed', 'skipped'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 사양 문구 → 실제 모델명 대조 결과 (007_spec_resolution.sql)
--
-- 왜 필요한가: 보유 CSV(cpu_specs_complete_new.csv / gpu_specs_sanitized.csv)는 어느 시점의
-- 덤프다. 최신 Xeon이 없고(구형 2코어까지만), ECC 값은 92행에만 있고, 메모리·저장장치는
-- 아예 없다. 그래서 대조가 실패하면 웹에 물어보는데, 그 답을 어디에도 남기지 않으면
-- 같은 사양이 올 때마다 다시 물어본다 — CSV가 영원히 천장으로 남는다.
-- 웹이 해결한 것을 여기 적어 두면 두 번째부터는 로컬에서 끝난다.
--
-- ⚠ 구조 변경 (MySQL 필수):
--   Postgres 에서는 무제한 TEXT 인 spec_text 가 곧 PK 였다. MySQL 에서는 불가능하다 —
--   인덱스 키 상한이 utf8mb4 기준 3072바이트(768자)이고 규격 문구는 자유 서술 한국어다.
--   그래서 대리키 id 를 두고, spec_text 는 MEDIUMTEXT 로 남기고,
--   SHA-256 저장 생성 컬럼에 UNIQUE 를 건다. 의미(문구당 한 행)는 그대로다.
--   ⚠ 애플리케이션 영향: server.js:2048 / 2053 / 2069 세 질의를
--     `WHERE spec_text = ?` 에서 `WHERE spec_text_hash = UNHEX(SHA2(?, 256))` 로 바꿔야 한다.
-- ============================================================
CREATE TABLE `spec_resolution` (
  `id`             BIGINT NOT NULL AUTO_INCREMENT,
  `spec_text`      MEDIUMTEXT NOT NULL COMMENT '규격서에 적힌 사양 문구 그대로("CPU 12Core 이상, DDR5")',
  `spec_text_hash` BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(`spec_text`, 256))) STORED
                   COMMENT 'spec_text 의 대리 유일키 — 원본 PK 를 대신한다',
  `resolved_name`  TEXT NOT NULL COMMENT '골라낸 실제 모델명 — 가격 검색에 쓰는 이름',
  `kind`           TEXT COMMENT 'cpu / gpu',
  `reason`         TEXT COMMENT '선정 근거 한 줄(화면 툴팁에 그대로 나간다)',
  `source`         VARCHAR(64) NOT NULL COMMENT 'csv(보유 데이터) / web(검색으로 발견)',
  `source_url`     TEXT,
  `hit_count`      INT NOT NULL DEFAULT 0 COMMENT '재사용 횟수 — 어떤 사양이 자주 오는지 보인다',
  `created_at`     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`id`),
  UNIQUE KEY `spec_resolution_text_uidx` (`spec_text_hash`),
  -- 웹으로 해결한 것만 따로 보기 위한 인덱스(CSV 보강 대상 목록이 된다).
  KEY `spec_resolution_source_idx` (`source`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 사람이 골라 담은 공고 + 그 공고에 대해 사람이 확정한 것 (008_saved_notice.sql)
--
-- analysis_history(006)와 무엇이 다른가: 그쪽은 "AI가 돌린 결과"의 캐시다(공고를 열면 자동으로
-- 쌓이고, 같은 공고를 다시 열 때 재분석을 막는 게 목적). 이 표는 "영업이 담아 둔 건"이다.
--
-- 특히 가격표가 다르다. 지금까지 사람이 표에서 고친 단가·수량·출처 링크는 브라우저 안에서만
-- 살아 있었고 새로고침하면 사라졌다 — 웹 단가를 다시 조회해도 같은 값이 나온다는 보장이
-- 없으니(판매가는 바뀐다) 사실상 복구가 불가능했다. 확정한 견적은 남아야 한다.
-- ============================================================
CREATE TABLE `saved_notice` (
  `bid_ntce_no`   VARCHAR(64) NOT NULL,
  `bid_ntce_ord`  VARCHAR(64) NOT NULL DEFAULT '',
  `title`         TEXT,
  `instt_nm`      TEXT,
  `bid_clse_dt`   TEXT COMMENT '입찰마감(원문 문자열 그대로 — 표기가 제각각이라 파싱하지 않는다)',
  `amount`        BIGINT COMMENT '추정가격(부가세 별도)',
  `real_estimate` BIGINT COMMENT '실추정가 = 추정가 × 1.1. 견적 합계와 견줄 값',
  `summary`       TEXT COMMENT '요약본 전문(잘리지 않는다 — 저장이 목적이므로)',
  `price_rows`    JSON COMMENT '사람이 확정한 가격표. 행 배열(번들 헤더/품목/부품)',
  `price_total`   BIGINT COMMENT '그때의 합계 — 표를 다시 계산하지 않고도 목록에 쓴다',
  `raw`           JSON COMMENT '공고 원본 필드. 카드를 API 재조회 없이 복원한다',
  `note`          TEXT COMMENT '사람이 남기는 메모',
  -- 저장 공고 내 검색용. 제목·기관·요약·메모·가격표(품목명)를 한 칸에 모아 두고 훑는다.
  -- 한국어 형태소 사전이 없어 tsvector 는 실익이 적었고, 수천 건 규모에선 ILIKE 훑기로
  -- 충분했다. MySQL 에서는 기본 콜레이션(utf8mb4_0900_ai_ci)이 이미 대소문자를 무시하므로
  -- ILIKE 는 그냥 LIKE 가 된다. 인덱스가 없으므로 MEDIUMTEXT 로 둔다 —
  -- 느려지면 FULLTEXT ... WITH PARSER ngram 이 올바른 MySQL 대체재다(그때도 이 칸을 그대로 쓴다).
  `search_text`   MEDIUMTEXT,
  `saved_at`      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`bid_ntce_no`, `bid_ntce_ord`),
  KEY `saved_notice_updated_idx` (`updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 사전규격 그림자 비교 로그 (011_pre_spec_shadow.sql)
-- API 응답은 그대로 유지하고 DB 결과와의 차이만 기록한다.
-- ============================================================
CREATE TABLE `pre_spec_shadow_log` (
  `id`                        BIGINT NOT NULL AUTO_INCREMENT,
  `from_dt`                   TEXT,
  `to_dt`                     TEXT,
  `bid_type`                  TEXT,
  `api_count`                 INT NOT NULL,
  `db_count`                  INT NOT NULL,
  `matched_count`             INT NOT NULL,
  `api_only_count`            INT NOT NULL,
  `db_only_count`             INT NOT NULL,
  `api_duplicate_count`       INT NOT NULL DEFAULT 0,
  `core_mismatch_count`       INT NOT NULL DEFAULT 0,
  `attachment_mismatch_count` INT NOT NULL DEFAULT 0,
  `api_only_sample`           JSON NOT NULL DEFAULT (JSON_ARRAY()),
  `db_only_sample`            JSON NOT NULL DEFAULT (JSON_ARRAY()),
  `duration_ms`               INT,
  `created_at`                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`id`),
  KEY `pre_spec_shadow_created_idx` (`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 조달요청 적재 실행 이력 (014_procurement_request_provenance.sql)
-- 발주계획(조달요청) 원문을 실행 단위로 보존한다.
-- ============================================================
CREATE TABLE `procurement_request_ingest_run` (
  `id`             CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL
                   COMMENT 'gen_random_uuid() 대체 — 애플리케이션이 생성',
  `from_dt`        TEXT NOT NULL,
  `to_dt`          TEXT NOT NULL,
  `status`         VARCHAR(64) NOT NULL DEFAULT 'running',
  `fetched_count`  INT NOT NULL DEFAULT 0,
  `raw_count`      INT NOT NULL DEFAULT 0,
  `entity_count`   INT NOT NULL DEFAULT 0,
  `failed_windows` INT NOT NULL DEFAULT 0,
  `summary`        JSON NOT NULL DEFAULT (JSON_OBJECT()),
  `last_error`     TEXT,
  `started_at`     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `completed_at`   DATETIME(6),

  PRIMARY KEY (`id`),
  CONSTRAINT `prcrmnt_req_ingest_run_status_chk`
    CHECK (`status` IN ('running', 'completed', 'failed')),
  CONSTRAINT `prcrmnt_req_ingest_run_fetched_chk` CHECK (`fetched_count` >= 0),
  CONSTRAINT `prcrmnt_req_ingest_run_raw_chk` CHECK (`raw_count` >= 0),
  CONSTRAINT `prcrmnt_req_ingest_run_entity_chk` CHECK (`entity_count` >= 0),
  CONSTRAINT `prcrmnt_req_ingest_run_windows_chk` CHECK (`failed_windows` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 첨부 파싱 결과 캐시 (원본: lib/attachment-cache.js:65 — 런타임 CREATE TABLE IF NOT EXISTS)
--
-- 스키마 정의가 애플리케이션 코드 안에 있어서 프로세스가 뜰 때마다 만들어졌다.
-- 다른 모든 테이블과 달리 db/schema.sql 에도 db/migrations/ 에도 없었다.
-- Flyway 로 옮겨 스키마 소유권을 한 곳으로 모은다.
--
-- 시간 컬럼은 better-sqlite3 시절과 동일하게 epoch ms(BIGINT)를 유지한다 —
-- 타임존 해석이 끼어들 여지를 없애고 기존 만료·정렬 로직을 그대로 쓰기 위해서다.
-- 그래서 여기만 DATETIME(6) 이 아니다. 의도된 것이다.
-- ============================================================
CREATE TABLE `attachment_cache` (
  -- attachmentCacheKey() 가 sha1 hex 를 만든다 -> 정확히 40자.
  `cache_key`         VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `bid_tab`           VARCHAR(64),
  `bid_no`            VARCHAR(64),
  `bid_seq`           VARCHAR(64),
  `bid_type`          TEXT,
  `file_entries_json` TEXT,
  `parsed_text`       MEDIUMTEXT COMMENT '추출한 첨부 본문 — 규격서 전문이 들어올 수 있다',
  `status`            VARCHAR(64) NOT NULL DEFAULT 'pending'
                      COMMENT 'pending / done / skip / failed — 만료되지 않은 done/skip 만 캐시 적중',
  `retry_count`       INT NOT NULL DEFAULT 0,
  `last_error`        TEXT,
  `queued_at`         BIGINT NOT NULL COMMENT 'epoch ms',
  `updated_at`        BIGINT NOT NULL COMMENT 'epoch ms',
  `expire_at`         BIGINT NOT NULL COMMENT 'epoch ms (TTL 30일)',

  PRIMARY KEY (`cache_key`),
  KEY `idx_attachment_cache_status` (`status`, `queued_at`),
  KEY `idx_attachment_cache_bid_no` (`bid_no`),
  KEY `idx_attachment_cache_expire` (`expire_at`),
  KEY `idx_attachment_cache_tab_no` (`bid_tab`, `bid_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- V3 에서 미뤄 둔 FK — procurement_request_ingest_run 이 이제 존재한다
-- (migration 014 가 raw_procure_req_list 에 걸었던 것)
-- ============================================================
ALTER TABLE `raw_procure_req_list`
  ADD CONSTRAINT `fk_raw_procure_req_ingest_run` FOREIGN KEY (`ingest_run_id`)
    REFERENCES `procurement_request_ingest_run` (`id`) ON DELETE SET NULL;

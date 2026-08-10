-- ============================================================
-- V3__raw_tables.sql
-- RAW 테이블 — API 응답 원문 보관(재처리용). 7종.
-- 원본: g2bmastersopen/db/schema.sql §3 + db/migrations/009, 014
--
-- 실사용 3종: raw_pre_spec_list, raw_procure_req_list, raw_generic_list
-- 휴면 4종:   raw_bid_public_list, raw_scsbid_list, raw_contract_list, raw_order_plan_list
--   — 충실도(fidelity)를 위해 그대로 옮기지만 오늘 이들에 쓰는 코드는 없다.
--     각 테이블 위에 그 사실을 적어 두었다. JPA 엔티티는 만들지 말 것.
--
-- item_json JSONB -> JSON. Postgres 의 GIN/containment 연산자는 잃지만
-- 이 컬럼들은 재처리용 보관소일 뿐 조회 대상이 아니다.
--
-- ⚠ raw_procure_req_list.ingest_run_id 의 FK 대상인 procurement_request_ingest_run 은
--   V5 에서 만들어진다. 그래서 컬럼만 여기서 만들고 FK 제약은 V5 끝에서 건다.
-- ============================================================


-- ============================================================
-- 입찰공고 원문 (Bid Public Info) — 응답이 가장 풍부하다
-- ⚠ 휴면(dormant): 오늘 이 테이블에 쓰는 코드는 없다. 충실도 목적으로만 유지한다.
-- ============================================================
CREATE TABLE `raw_bid_public_list` (
  `raw_id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'BIGSERIAL 대체',
  `call_id`         CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,

  -- 응답 봉투
  `result_code`     TEXT,
  `result_msg`      TEXT,
  `total_count`     BIGINT,
  `page_no`         INT,
  `num_of_rows`     INT,

  -- 항목 전문 (100개 이상의 필드를 그대로 보존한다)
  `item_json`       JSON NOT NULL,

  -- 조인용으로 뽑아 둔 키
  `bid_ntce_no`     VARCHAR(64),
  `bid_ntce_ord`    VARCHAR(64),
  `bf_spec_rgst_no` VARCHAR(64),

  `received_at`     DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_bid_keys` (`bid_ntce_no`, `bid_ntce_ord`),
  KEY `idx_raw_bid_call` (`call_id`),
  CONSTRAINT `fk_raw_bid_public_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 개찰/낙찰 원문 (Scsbid)
-- ⚠ 휴면(dormant): 오늘 이 테이블에 쓰는 코드는 없다. 충실도 목적으로만 유지한다.
-- ============================================================
CREATE TABLE `raw_scsbid_list` (
  `raw_id`       BIGINT NOT NULL AUTO_INCREMENT,
  `call_id`      CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `result_code`  TEXT,
  `result_msg`   TEXT,
  `total_count`  BIGINT,
  `item_json`    JSON NOT NULL,
  `bid_ntce_no`  VARCHAR(64),
  `bid_ntce_ord` VARCHAR(64),
  `received_at`  DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_scsbid_keys` (`bid_ntce_no`, `bid_ntce_ord`),
  KEY `idx_raw_scsbid_call` (`call_id`),
  CONSTRAINT `fk_raw_scsbid_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 계약 원문 (Contract)
-- ⚠ 휴면(dormant): 오늘 이 테이블에 쓰는 코드는 없다. 충실도 목적으로만 유지한다.
-- ============================================================
CREATE TABLE `raw_contract_list` (
  `raw_id`         BIGINT NOT NULL AUTO_INCREMENT,
  `call_id`        CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `result_code`    TEXT,
  `result_msg`     TEXT,
  `total_count`    BIGINT,
  `item_json`      JSON NOT NULL,
  `cntrct_no`      VARCHAR(64),
  `unty_cntrct_no` VARCHAR(64),
  `bid_ntce_no`    VARCHAR(64),
  `bid_ntce_ord`   VARCHAR(64),
  `received_at`    DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_contract_keys` (`cntrct_no`),
  KEY `idx_raw_contract_bid` (`bid_ntce_no`, `bid_ntce_ord`),
  KEY `idx_raw_contract_call` (`call_id`),
  CONSTRAINT `fk_raw_contract_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 사전규격 원문 (Pre-spec) — 실사용
-- migration 009_pre_spec_provenance.sql 을 접어 넣었다:
-- 사전규격 원문을 먼저 보존한 뒤 정규화 결과를 추적하기 위한 최소 확장.
-- ============================================================
CREATE TABLE `raw_pre_spec_list` (
  `raw_id`              BIGINT NOT NULL AUTO_INCREMENT,
  `call_id`             CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `result_code`         TEXT,
  `result_msg`          TEXT,
  `total_count`         BIGINT,
  `item_json`           JSON NOT NULL,
  `bf_spec_rgst_no`     VARCHAR(64),
  `received_at`         DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  -- ---- migration 009 ----------------------------------------------------
  `page_no`             INT,
  `item_index`          INT,
  `normalized_at`       DATETIME(6),
  `normalization_error` TEXT,

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_pre_spec_key` (`bf_spec_rgst_no`),

  -- 한 API 응답 페이지 안에서 같은 항목을 두 번 저장하지 않는다.
  -- 원본은 WHERE call_id IS NOT NULL AND page_no IS NOT NULL AND item_index IS NOT NULL
  -- 부분 UNIQUE 였다. MySQL 은 UNIQUE 인덱스에서 NULL 을 서로 다른 값으로 보므로
  -- 술어가 사실상 공짜다 — 과거 행과 수동 적재 행(위치 값 없음)은 그대로 통과한다.
  UNIQUE KEY `raw_pre_spec_call_position_idx` (`call_id`, `page_no`, `item_index`),

  -- 호출 로그에서 원문으로, 정규화 엔티티에서 원문 이력으로 빠르게 이동한다.
  KEY `raw_pre_spec_call_idx` (`call_id`),
  KEY `raw_pre_spec_entity_history_idx` (`bf_spec_rgst_no`, `received_at` DESC),

  -- 정규화되지 않은 원문은 재처리 대상으로 바로 찾을 수 있어야 한다.
  -- 원본은 WHERE normalized_at IS NULL 부분 인덱스. 술어를 떼고 일반 인덱스로 둔다.
  KEY `raw_pre_spec_pending_idx` (`received_at`),

  CONSTRAINT `fk_raw_pre_spec_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 발주계획 원문 (Order Plan)
-- ⚠ 휴면(dormant): 오늘 이 테이블에 쓰는 코드는 없다. 충실도 목적으로만 유지한다.
-- ============================================================
CREATE TABLE `raw_order_plan_list` (
  `raw_id`        BIGINT NOT NULL AUTO_INCREMENT,
  `call_id`       CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `result_code`   TEXT,
  `result_msg`    TEXT,
  `total_count`   BIGINT,
  `item_json`     JSON NOT NULL,
  `order_plan_no` VARCHAR(64),
  `received_at`   DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_order_plan_call` (`call_id`),
  CONSTRAINT `fk_raw_order_plan_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 조달요청 원문 (Procurement Request) — 실사용
-- migration 014_procurement_request_provenance.sql 을 접어 넣었다.
-- ⚠ ingest_run_id 의 FK 는 procurement_request_ingest_run(V5) 이 생긴 뒤 V5 끝에서 건다.
-- ============================================================
CREATE TABLE `raw_procure_req_list` (
  `raw_id`              BIGINT NOT NULL AUTO_INCREMENT,
  `call_id`             CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `result_code`         TEXT,
  `result_msg`          TEXT,
  `total_count`         BIGINT,
  `item_json`           JSON NOT NULL,
  `prcrmnt_req_no`      VARCHAR(64),
  `received_at`         DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  -- ---- migration 014 ----------------------------------------------------
  `ingest_run_id`       CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `operation`           VARCHAR(64),
  `page_no`             INT,
  `item_index`          INT,
  `normalized_at`       DATETIME(6),
  `normalization_error` TEXT,

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_procure_req_key` (`prcrmnt_req_no`),

  -- 부분 UNIQUE -> 일반 UNIQUE (MySQL 은 UNIQUE 에서 NULL 을 서로 다르게 본다)
  UNIQUE KEY `raw_procure_req_call_position_idx` (`call_id`, `page_no`, `item_index`),

  KEY `raw_procure_req_run_idx` (`ingest_run_id`, `operation`, `page_no`, `item_index`),
  KEY `raw_procure_req_entity_history_idx` (`prcrmnt_req_no`, `received_at` DESC),
  -- 원본은 WHERE normalized_at IS NULL 부분 인덱스. 술어를 떼고 일반 인덱스로 둔다.
  KEY `raw_procure_req_pending_idx` (`received_at`),

  CONSTRAINT `fk_raw_procure_req_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 그 밖의 서비스용 범용 원문 — 실사용
-- ============================================================
CREATE TABLE `raw_generic_list` (
  `raw_id`      BIGINT NOT NULL AUTO_INCREMENT,
  `call_id`     CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `service_id`  VARCHAR(64) NOT NULL,
  `operation`   VARCHAR(64) NOT NULL,
  `result_code` TEXT,
  `result_msg`  TEXT,
  `total_count` BIGINT,
  `item_json`   JSON NOT NULL,
  `received_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`raw_id`),
  KEY `idx_raw_generic_svc` (`service_id`, `operation`),
  KEY `idx_raw_generic_call` (`call_id`),
  CONSTRAINT `fk_raw_generic_call` FOREIGN KEY (`call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

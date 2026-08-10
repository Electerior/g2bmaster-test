-- ============================================================
-- V1__baseline_dimensions.sql
-- G2B 공공조달 데이터 웨어하우스 — 기준 정보 / 운영 로그
-- 원본: g2bmastersopen/db/schema.sql §1, §2, §4 (PostgreSQL 14+)
-- 대상: MySQL 8.0.13+, InnoDB, utf8mb4 / utf8mb4_0900_ai_ci, ROW_FORMAT=DYNAMIC
--
-- 이 파일은 재생(replay)이 아니라 새 출발점(baseline)이다.
-- db/migrations/001..017 의 결과는 CREATE TABLE 안에 이미 접어 넣었다.
--
-- 변환 규칙 (전 파일 공통):
--   TIMESTAMPTZ -> DATETIME(6), UTC 저장. TIMESTAMP 는 쓰지 않는다 —
--     2038년 상한이 있고 이 데이터에는 9999-12-31 센티넬이 실제로 존재한다.
--   UUID        -> CHAR(36) CHARACTER SET ascii. 값은 애플리케이션이 만든다
--     (crypto.randomUUID() 가 이미 그렇게 하고 있었다). BINARY(16) 은 JPA 매핑이 번거롭다.
--   JSONB       -> JSON. '{}'::jsonb / '[]'::jsonb 기본값은
--     DEFAULT (JSON_OBJECT()) / DEFAULT (JSON_ARRAY()) — 괄호가 필수다(8.0.13+).
--   NUMERIC(무제한) -> DECIMAL(20,4) 금액 / DECIMAL(9,4) 비율. MySQL 에 무제한 NUMERIC 은 없다.
--   TEXT        -> 원칙적으로 TEXT 유지. PK·UNIQUE·FK·인덱스에 쓰이는 컬럼만 VARCHAR 로 승격한다
--     (MySQL 행 정의 상한 65,535바이트 — §2 의 행 크기 함정).
--   TEXT DEFAULT 'Y' -> CHAR(1) DEFAULT 'Y'. MySQL 은 TEXT/BLOB/JSON 에 리터럴 DEFAULT 를 못 준다.
--   BOOLEAN     -> TINYINT(1).
--   부분 인덱스(WHERE ...) -> 술어를 떼고 일반 인덱스로. MySQL 에 부분 인덱스는 없다.
--     술어가 UNIQUE 의 의미를 바꾸는 3종만 V6 에서 생성 컬럼으로 흉내낸다.
--
-- 삭제한 것: CREATE EXTENSION "uuid-ossp" / "pgcrypto" (MySQL 에 불필요),
--   머티리얼라이즈드 뷰 3종, PL/pgSQL 함수 3종 — 어느 것도 애플리케이션에서 호출하지 않는다.
-- ============================================================


-- ============================================================
-- 1. API 호출 로그 (모니터링·디버깅·레이트리밋)
--    모든 raw_* / dwt_* / stg_* 의 계보(lineage) FK 대상이므로 가장 먼저 만든다.
-- ============================================================
CREATE TABLE `api_call_log` (
  `call_id`        CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL
                   COMMENT 'gen_random_uuid() 대체 — 애플리케이션이 생성',
  `service_id`     VARCHAR(64) NOT NULL,
  `operation`      VARCHAR(64) NOT NULL,
  `base_path`      TEXT,
  `params_json`    JSON NOT NULL COMMENT 'ServiceKey 를 제거한 파라미터',

  `started_at`     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `completed_at`   DATETIME(6),
  `duration_ms`    INT,

  `http_status`    INT,
  `result_code`    TEXT COMMENT '00, 08, 12 등',
  `result_msg`     TEXT,
  `total_count`    BIGINT,
  `item_count`     INT,

  `error_text`     TEXT,
  `stack_trace`    TEXT,

  -- 증분 동기화 추적용
  `sync_batch_id`  CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci
                   COMMENT '연관 호출을 묶는다',
  `is_incremental` TINYINT(1) DEFAULT 0,

  `created_at`     DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`call_id`),
  KEY `idx_api_call_log_service` (`service_id`, `operation`),
  KEY `idx_api_call_log_started` (`started_at` DESC),
  -- 원본은 WHERE sync_batch_id IS NOT NULL 부분 인덱스였다. MySQL 에 부분 인덱스는
  -- 없고, 술어가 NULL 제외뿐이라 일반 인덱스로 의미가 유지된다(NULL 은 조회되지 않는다).
  KEY `idx_api_call_log_batch` (`sync_batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 2. 동기화 상태 (서비스/오퍼레이션별 마지막 성공 시점)
-- ============================================================
--
-- ⚠ inqry_div 는 Postgres 에서 nullable 이면서 PK 의 일부였다. MySQL 은 PK 컬럼에
--   NOT NULL 을 강제하므로 NOT NULL DEFAULT '' 로 바꾼다. 의미는 그대로다 —
--   migration 017 이 이미 COALESCE(inqry_div,'') 로 정규화해 두었고
--   g2b_sync_coverage 도 처음부터 NOT NULL DEFAULT '' 였다.
CREATE TABLE `sync_state` (
  `service_id`           VARCHAR(64) NOT NULL,
  `operation`            VARCHAR(64) NOT NULL,
  `inqry_div`            VARCHAR(64) NOT NULL DEFAULT ''
                         COMMENT '어떤 inqryDiv 를 썼는지. Postgres 에서는 nullable PK 였다',

  `last_success_at`      DATETIME(6),
  `last_params`          JSON COMMENT '마지막 성공에 쓴 파라미터',
  `last_total_count`     BIGINT,

  -- 날짜 구간 기반 동기화
  `last_bgn_dt`          DATETIME(6) COMMENT 'inqryBgnDt',
  `last_end_dt`          DATETIME(6) COMMENT 'inqryEndDt',

  -- 연월 기반 동기화
  `last_bgn_ym`          TEXT COMMENT 'orderBgnYm 등',
  `last_end_ym`          TEXT,

  `consecutive_failures` SMALLINT DEFAULT 0,
  `last_error`           TEXT,
  `last_error_at`        DATETIME(6),

  `updated_at`           DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`service_id`, `operation`, `inqry_div`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 장기 backfill 의 성공 구간을 날짜 단위로 누적한다. sync_state 는 최근 상태 표시용이고,
-- 이 테이블은 전체 기간 커버리지 확인과 중단 후 재개에 사용한다.
-- (원본: schema.sql + db/migrations/017_g2b_sync_coverage.sql — 같은 정의라 한 번만 만든다)
CREATE TABLE `g2b_sync_coverage` (
  `service_id`    VARCHAR(64) NOT NULL,
  `operation`     VARCHAR(64) NOT NULL,
  `inqry_div`     VARCHAR(64) NOT NULL DEFAULT '',
  `coverage_date` DATE NOT NULL,
  `total_count`   BIGINT,
  `completed_at`  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  -- 키 길이: VARCHAR(64) utf8mb4 × 3 + DATE = 774바이트 < 3072 ✔
  PRIMARY KEY (`service_id`, `operation`, `inqry_div`, `coverage_date`),
  KEY `g2b_sync_coverage_lookup_idx` (`service_id`, `operation`, `inqry_div`, `coverage_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 3. 차원 테이블 (공유 기준 정보) — 팩트보다 먼저 만들어야 FK 가 걸린다
-- ============================================================

-- 기관 (Institution/Organization)
-- 두 곳에서 이 테이블로 흘러든다:
--   * extract_institutions() -- 입찰·계약 팩트에서 긁어낸 코드/이름.
--   * UsrInfoService.getDminsttInfo -- 권위 있는 수요기관 마스터. 구분선 아래 컬럼 전부를 채운다.
-- API 는 코드를 dminsttCd 로 부르지만 적재기가 insttCd 로 정규화하므로
-- (OPERATION_FIELD_RENAME 참조) 두 출처가 같은 PK 로 떨어진다.
CREATE TABLE `dm_institution` (
  `instt_cd`                VARCHAR(20) NOT NULL COMMENT '7자리 코드',
  `instt_nm`                TEXT NOT NULL,
  `instt_ty_cd`             TEXT COMMENT '1:수요 2:공고 3:계약 4:관할',
  `up_instt_cd`             VARCHAR(20) COMMENT '자기참조 FK — 파일 끝에서 건다',
  `instt_dc`                TEXT,
  `zip_cd`                  TEXT,
  `adrs`                    TEXT,
  `tel_no`                  TEXT,
  `fax_no`                  TEXT,
  `hmpg_url`                TEXT,
  `use_yn`                  CHAR(1) DEFAULT 'Y',

  -- ---- getDminsttInfo (수요기관정보조회) ----------------------------------
  `biz_no`                  VARCHAR(32) COMMENT '사업자등록번호',
  `corprt_rgst_no`          TEXT COMMENT '법인등록번호',
  `dminstt_abrvt_nm`        TEXT COMMENT '수요기관약칭',
  `dminstt_eng_nm`          TEXT COMMENT '수요기관영문명',
  `jrsdctn_div_nm`          TEXT COMMENT '관할구분명',
  `instt_ty_cd_lrgclsfc_nm` TEXT COMMENT '기관유형 대분류명',
  `instt_ty_cd_midclsfc_nm` TEXT COMMENT '기관유형 중분류명',
  `instt_ty_cd_smlclsfc_nm` TEXT COMMENT '기관유형 소분류명',
  `bizcndtn_nm`             TEXT COMMENT '업태명',
  -- indstryty_nm 이 아니라 indstry_nm 인 이유: 적재기의 전역 필드 맵이
  -- API 의 indstrytyNm 을 이미 indstry_nm 으로 풀어 준다(dm_industry_law 와 동일).
  `indstry_nm`              TEXT COMMENT 'API indstrytyNm (업종명)',
  `ofcl_fax_no`             TEXT COMMENT '담당자 팩스번호',
  `rgn_cd`                  TEXT COMMENT '지역코드',
  `rgn_nm`                  TEXT COMMENT '지역명',
  `dtl_adrs`                TEXT COMMENT '상세주소',
  `dlt_yn`                  CHAR(1) COMMENT '삭제여부',
  -- 보고된 상위기관. 의도적으로 FK 컬럼이 아니다: 마스터 피드는 아직 적재되지 않은
  -- 상위기관을 지목할 수 있고, 하드 FK 를 걸면 도착 순서가 적재 실패로 바뀐다.
  -- up_instt_cd 가 큐레이션된 링크로 남는다.
  `toplvl_instt_cd`         TEXT COMMENT '최상위기관코드',
  `toplvl_instt_nm`         TEXT COMMENT '최상위기관명',
  `vld_prd_bgn_dt`          DATETIME(6) COMMENT '유효기간 시작일시',
  `vld_prd_end_dt`          DATETIME(6) COMMENT '유효기간 종료일시',
  `rgst_dt`                 DATETIME(6) COMMENT '등록일시',
  `chg_dt`                  DATETIME(6) COMMENT '변경일시',

  `created_at`              DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`              DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`instt_cd`),
  KEY `idx_dm_inst_up` (`up_instt_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 물품분류 (Product Classification)
-- 출처: ThngListInfoService.getPrdctClsfcNoUnit{2,4,6,8}Info + getThngGuidanceMapInfo.
-- 코드가 UNSPSC 모양이라 자릿수가 곧 레벨이다: 2=segment, 4=family, 6=class, 8=commodity.
-- Unit 오퍼레이션마다 한 자릿수를 돌려주고, 적재기가 호출한 오퍼레이션으로 clsfc_lv 를 찍는다.
--
-- up_clsfc_no 는 적재기가 일부러 NULL 로 둔다. 부모는 잘라내기로 유도할 수 있지만
-- (left(clsfc_no, clsfc_lv - 2)), 적재 중에 유도하면 부모 레벨이 아직 안 들어온 부분/증분
-- 동기화에서 이 테이블의 자기 FK 가 터진다. 네 레벨이 모두 들어온 뒤 한 번에 채운다:
--
--   UPDATE dm_product_classification c
--     JOIN dm_product_classification p ON p.clsfc_no = LEFT(c.clsfc_no, c.clsfc_lv - 2)
--      SET c.up_clsfc_no = p.clsfc_no
--    WHERE c.clsfc_lv > 2;
CREATE TABLE `dm_product_classification` (
  `clsfc_no`      VARCHAR(20) NOT NULL COMMENT '예: 43211507',
  `clsfc_nm`      TEXT NOT NULL,
  `up_clsfc_no`   VARCHAR(20) COMMENT '자기참조 FK — 파일 끝에서 건다',
  `clsfc_lv`      SMALLINT COMMENT '코드 자릿수: 2 / 4 / 6 / 8',
  `clsfc_dc`      TEXT,
  `use_yn`        CHAR(1) DEFAULT 'Y',

  -- ---- getPrdctClsfcNoUnit*Info / getThngGuidanceMapInfo -----------------
  `clsfc_eng_nm`  TEXT COMMENT '물품분류번호 영문명',
  `clsfc_rltn_dc` TEXT COMMENT '물품분류 관련설명',
  `clsfc_apl_yn`  CHAR(1) COMMENT '물품분류번호 적용여부',
  `clsfc_dlt_yn`  CHAR(1) COMMENT '물품분류번호 삭제여부',
  `clsfc_div_cd`  TEXT COMMENT '물품분류구분코드',
  `chg_date`      TEXT COMMENT '변경일자 (API 가 YYYYMMDD 문자열로 보낸다)',

  `created_at`    DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  -- 필수: upsert_records() 는 ON CONFLICT 절을 항상 `updated_at = now()` 로 끝내므로
  -- 모든 upsert 대상 테이블에 이 컬럼이 있어야 한다.
  `updated_at`    DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`clsfc_no`),
  KEY `idx_dm_prod_up` (`up_clsfc_no`),
  KEY `idx_dm_prod_lv` (`clsfc_lv`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 상세물품분류 (Detail Product Classification) — 10자리 세부품명번호
-- 출처: getPrdctClsfcNoUnit10Info 와 품명 목록 검색.
--
-- clsfc_no 를 적재기가 NULL 로 두는 이유는 dm_product_classification.up_clsfc_no 와 같다:
-- 두 피드 모두 8자리 부모를 보고하지만, 적재 중에 쓰면 부모 레벨이 아직 없을 때 FK 가 터진다.
-- 키의 앞 8자리이므로 나중에 한 번에 채운다:
--
--   UPDATE dm_detail_product_classification d
--     JOIN dm_product_classification p ON p.clsfc_no = LEFT(d.dtl_clsfc_no, 8)
--      SET d.clsfc_no = p.clsfc_no;
CREATE TABLE `dm_detail_product_classification` (
  `dtl_clsfc_no`     VARCHAR(20) NOT NULL COMMENT '예: 4213220301',
  `dtl_clsfc_nm`     TEXT NOT NULL,
  `clsfc_no`         VARCHAR(20),
  `spcfc_dc`         TEXT,
  `use_yn`           CHAR(1) DEFAULT 'Y',

  -- ---- getPrdctClsfcNoUnit10Info / ...InfoPrdnmSearch --------------------
  `dtl_clsfc_eng_nm` TEXT COMMENT '세부품명번호 영문명',
  `dtl_clsfc_no_num` TEXT COMMENT '세부품명번호 수량',
  `chg_date`         TEXT COMMENT '변경일자 (API 가 YYYYMMDD 문자열로 보낸다)',

  `created_at`       DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`       DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'upsert_records() 가 요구한다',

  PRIMARY KEY (`dtl_clsfc_no`),
  KEY `idx_dm_dtl_prod_clsfc` (`clsfc_no`),
  CONSTRAINT `fk_dm_dtl_prod_clsfc` FOREIGN KEY (`clsfc_no`)
    REFERENCES `dm_product_classification` (`clsfc_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 업종/근거법규 (Industry & Legal Basis)
-- 출처: IndstrytyBaseLawrgltInfoService.getIndstrytyBaseLawrgltInfoList
-- API 는 업종코드를 indstrytyCd 로 부르지만 적재기의 special 사전이 indstry_cd 로
-- 매핑하므로 dm_supplier 에서 오는 FK 가 그대로 유지된다.
CREATE TABLE `dm_industry_law` (
  `indstry_cd`                  VARCHAR(32) NOT NULL COMMENT 'API indstrytyCd (업종코드)',
  `indstry_nm`                  TEXT COMMENT 'API indstrytyNm (업종명)',
  `base_law_nm`                 TEXT COMMENT 'API baseLawordNm (근거법령명)',
  `base_law_no`                 TEXT,
  `law_dc`                      TEXT,
  `use_yn`                      CHAR(1) DEFAULT 'Y' COMMENT 'API indstrytyUseYn',

  -- 업종분류
  `indstryty_clsfc_cd`          TEXT,
  `indstryty_clsfc_nm`          TEXT,

  -- 근거법령
  `base_laword_artcl_clause_nm` TEXT COMMENT '근거법령조항명',
  `base_laword_url`             TEXT COMMENT '근거법령URL',
  `rltn_rglt_cntnts`            TEXT COMMENT '관련규정내용',
  `inclsn_lcns`                 TEXT COMMENT '포함면허',

  `indstryty_rgst_dt`           DATETIME(6) COMMENT '업종등록일시',
  `indstryty_chg_dt`            DATETIME(6) COMMENT '업종변경일시',

  `created_at`                  DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`                  DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'upsert_records() 가 요구한다',

  PRIMARY KEY (`indstry_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 업체 (Supplier) — 입찰 결과·계약에서 만들어지고
-- UsrInfoService.getPrcrmntCorpBasicInfo (조달업체 기본정보) 로 보강된다.
-- last_bid_dt / total_* / win_rate 는 분석 소유다: 팩트 테이블에서 계산하며 API 피드가 쓰지 않는다.
CREATE TABLE `dm_supplier` (
  `biz_no`                VARCHAR(32) NOT NULL COMMENT '사업자등록번호',
  `corp_nm`               TEXT NOT NULL,
  `ceo_nm`                TEXT,
  `adrs`                  TEXT,
  `tel_no`                TEXT,
  `fax_no`                TEXT,
  `indstry_cd`            VARCHAR(32),
  `rgst_dt`               DATE,
  `biz_sttus`             TEXT COMMENT '영업중, 폐업, 휴업',
  `last_bid_dt`           DATE COMMENT '마지막 입찰참여일',
  `total_bid_cnt`         INT DEFAULT 0,
  `total_win_cnt`         INT DEFAULT 0,
  `win_rate`              DECIMAL(9,4) COMMENT '비율 — 무제한 NUMERIC 대체',

  -- ---- getPrcrmntCorpBasicInfo (조달업체 기본정보) ------------------------
  `eng_corp_nm`           TEXT COMMENT '업체영문명',
  `opbiz_dt`              TEXT COMMENT '개업일자 (API 가 YYYYMMDD 문자열로 보낸다)',
  `rgn_cd`                TEXT COMMENT '지역코드',
  `rgn_nm`                TEXT COMMENT '지역명',
  `zip_cd`                TEXT COMMENT '우편번호',
  `dtl_adrs`              TEXT COMMENT '상세주소',
  `cntry_nm`              TEXT COMMENT '국가명',
  `hmpg_url`              TEXT COMMENT '홈페이지주소',
  `mnfct_div_cd`          TEXT COMMENT '제조구분코드',
  `mnfct_div_nm`          TEXT COMMENT '제조구분명',
  `emplye_num`            TEXT COMMENT '종업원수',
  `corp_bsns_div_cd`      TEXT COMMENT '기업사업구분코드',
  `corp_bsns_div_nm`      TEXT COMMENT '기업사업구분명',
  `hdoffce_div_nm`        TEXT COMMENT '본사구분명',
  `esntl_no_cert_rgst_yn` CHAR(1) COMMENT '필수번호 인증등록여부',
  `chg_dt`                DATETIME(6) COMMENT '변경일시',

  `created_at`            DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`            DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`biz_no`),
  KEY `idx_dm_sup_indstry` (`indstry_cd`),
  CONSTRAINT `fk_dm_supplier_indstry` FOREIGN KEY (`indstry_cd`)
    REFERENCES `dm_industry_law` (`indstry_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 조달업체 업종 (Supplier x Industry) — 면허 업종 하나당 한 행.
-- 출처: UsrInfoService.getPrcrmntCorpIndstrytyInfo.
-- 두 컬럼 어디에도 FK 가 없다: 이 피드는 dm_supplier / dm_industry_law 가 채워지기 전에
-- 도착하는 일이 흔하고, 하드 FK 를 걸면 도착 순서가 치명적이 된다.
CREATE TABLE `dm_supplier_industry` (
  `biz_no`              VARCHAR(32) NOT NULL COMMENT '사업자등록번호',
  `indstry_cd`          VARCHAR(32) NOT NULL COMMENT 'API indstrytyCd (업종코드)',
  `indstry_nm`          TEXT COMMENT 'API indstrytyNm (업종명)',
  `indstryty_stats_nm`  TEXT COMMENT '업종상태명',
  `rprsnt_indstryty_yn` CHAR(1) COMMENT '대표업종여부',
  `rgst_dt`             DATETIME(6) COMMENT '등록일시',
  `chg_dt`              DATETIME(6) COMMENT '변경일시',
  `systm_rgst_dt`       DATETIME(6) COMMENT '시스템등록일시',
  `systm_chg_dt`        DATETIME(6) COMMENT '시스템변경일시',
  `vld_prd_exprt_dt`    DATETIME(6) COMMENT '유효기간 만료일시',
  `created_at`          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'upsert_records() 가 요구한다',

  PRIMARY KEY (`biz_no`, `indstry_cd`),
  KEY `idx_dm_sup_ind_cd` (`indstry_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 조달업체 공급물품 (Supplier x Supplied Product)
-- 출처: UsrInfoService.getPrcrmntCorpSplyPrdctInfo.
-- 이 응답에서는 상세분류를 dtilPrdctClsfcNo 로 부르는데("dtil" 철자 주의)
-- dm_detail_product_classification.dtl_clsfc_no 와 같은 10자리 키다.
-- dm_supplier_industry 와 같은 도착 순서 이유로 제약을 걸지 않는다.
CREATE TABLE `dm_supplier_product` (
  `biz_no`           VARCHAR(32) NOT NULL COMMENT '사업자등록번호',
  `dtl_clsfc_no`     VARCHAR(20) NOT NULL COMMENT 'API dtilPrdctClsfcNo (세부품명번호)',
  `dtl_clsfc_nm`     TEXT COMMENT 'API dtilPrdctClsfcNoNm',
  `rprsnt_prdct_yn`  CHAR(1) COMMENT '대표물품여부',
  `mnfct_yn`         CHAR(1) COMMENT '제조여부',
  `rgst_dt`          DATETIME(6) COMMENT '등록일시',
  `chg_dt`           DATETIME(6) COMMENT '변경일시',
  `created_at`       DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`       DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'upsert_records() 가 요구한다',

  PRIMARY KEY (`biz_no`, `dtl_clsfc_no`),
  KEY `idx_dm_sup_prd_clsfc` (`dtl_clsfc_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 품목 (Product Item) — 물품식별번호 마스터, 식별된 품목당 한 행.
-- 출처: ThngListInfoService.getThngPrdnmLocplcAccotListInfoInfoPrdlstSearch.
-- clsfc_no / dtl_clsfc_no 에 FK 가 없다: 품목 피드는 두 분류 차원을 앞질러 도착할 수 있고,
-- 그것들이 없어도 이 테이블은 쓸모가 있다.
CREATE TABLE `dm_product_item` (
  `prdct_idnt_no`        VARCHAR(32) NOT NULL COMMENT '물품식별번호',
  `krn_prdct_nm`         TEXT COMMENT '한글품목명',
  `clsfc_no`             VARCHAR(20) COMMENT '물품분류번호 (8자리)',
  `dtl_clsfc_no`         VARCHAR(20) COMMENT '세부품명번호 (10자리)',
  `clsfc_nm`             TEXT COMMENT '물품분류번호명',
  `clsfc_eng_nm`         TEXT COMMENT '물품분류번호 영문명',
  `prdlst_div`           TEXT COMMENT '품목구분',
  `cmpnt_yn`             CHAR(1) COMMENT '부품여부',
  `dlt_yn`               CHAR(1) COMMENT '삭제여부',
  `use_yn`               CHAR(1) DEFAULT 'Y' COMMENT '사용여부',
  `prcrmnt_corp_rgst_no` TEXT COMMENT '조달업체등록번호',
  `mnfct_corp_nm`        VARCHAR(255) COMMENT '제조업체명 — 인덱스가 있어 VARCHAR 로 승격',
  `prdct_img_lrge`       TEXT COMMENT '물품이미지(대)',
  `prodct_cert_list`     JSON COMMENT '인증목록 (중첩 배열)',
  `rgst_dt`              DATETIME(6) COMMENT '등록일시',
  `chg_dt`               DATETIME(6) COMMENT '변경일시',
  `created_at`           DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`           DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`prdct_idnt_no`),
  KEY `idx_dm_prdct_item_dtl` (`dtl_clsfc_no`),
  KEY `idx_dm_prdct_item_corp` (`mnfct_corp_nm`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- 쇼핑몰 물품 / 품목분류 내용연수
-- 출처: PrdctMngInfoService.getPrdctClsfcNoUslfsvc
-- 이름과 달리 현재 연결된 오퍼레이션은 품목분류번호로 키가 잡힌 내용연수 행을 돌려주지,
-- 개별 몰 상품을 돌려주지 않는다.
-- dm_product_classification 으로 가는 FK 는 없다: 이 피드는 그 차원이 채워지기 전에
-- 도착할 수 있고, 하드 FK 는 도착 순서를 적재 실패로 바꾼다.
CREATE TABLE `dm_shopping_mall_product` (
  `prdct_clsfc_no`    VARCHAR(20) NOT NULL COMMENT '품목분류번호',
  `prdct_clsfc_no_nm` TEXT COMMENT '품목분류번호명',
  `uslfsvc`           TEXT COMMENT '내용연수',
  `rgst_date`         DATE COMMENT '등록일자',
  `end_date`          DATE COMMENT '종료일자 (예: 9999-12-31 — TIMESTAMP 를 쓰지 않는 이유)',
  `chg_rsn`           TEXT COMMENT '변경사유',
  `created_at`        DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`        DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'upsert_records() 가 요구한다',

  PRIMARY KEY (`prdct_clsfc_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 4. 자기참조 FK — 테이블이 존재한 뒤에 건다
-- ============================================================
ALTER TABLE `dm_institution`
  ADD CONSTRAINT `fk_dm_institution_up` FOREIGN KEY (`up_instt_cd`)
    REFERENCES `dm_institution` (`instt_cd`);

ALTER TABLE `dm_product_classification`
  ADD CONSTRAINT `fk_dm_prod_clsfc_up` FOREIGN KEY (`up_clsfc_no`)
    REFERENCES `dm_product_classification` (`clsfc_no`);

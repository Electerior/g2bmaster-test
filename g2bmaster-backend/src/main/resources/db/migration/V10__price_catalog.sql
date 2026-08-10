-- ============================================================
-- V10__price_catalog.sql
-- 가격 데이터베이스 — 다나와·아이티마야·에누리에서 모은 부품/제품 단가의 카탈로그.
--
-- deal_analysis_result(V9)이 "이 공고를 분석한 결과"를 캐시한다면, 여기는 "부품 X 의 시세"를
-- 소스별로 쌓아 두는 곳이다. AI 리졸버가 긁은 quote 를 적재(ingest)하거나 사람이 직접 등록/수정한다.
--
-- 자연키(natural_key)는 (source, name, model, spec) 의 SHA-256 이다 — 같은 소스의 같은 상품이면
-- 같은 행으로 upsert 되어 최신 단가로 갱신되고, 갱신 이력은 price_history 에 append 된다.
-- 분리자로 CHAR(31)(unit separator)를 쓰는 이유는 이름/모델에 공백·하이픈이 흔해 충돌하기 때문이다.
--
-- price_krw 는 nullable 이다 — "가격 미확인"과 "0원"은 다르다(0 을 넣으면 딜 계산이 거짓말을 한다).
-- ============================================================

CREATE TABLE price_catalog (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  source       VARCHAR(16)   NOT NULL COMMENT 'danawa | itmaya | enuri',
  category     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '자유 입력 부품 분류(GPU/CPU/서버…)',
  name         VARCHAR(500)  NOT NULL,
  model        VARCHAR(255)  NOT NULL DEFAULT '',
  spec         VARCHAR(2000) DEFAULT NULL,
  price_krw    BIGINT        DEFAULT NULL COMMENT 'NULL = 가격 미확인 (0 아님)',
  url          VARCHAR(1000) DEFAULT NULL,
  note         VARCHAR(1000) DEFAULT NULL,
  captured_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  -- 소스+상품 동일성 키. 생성 컬럼이라 애플리케이션이 해시를 계산할 필요가 없다.
  natural_key  CHAR(64) AS (SHA2(CONCAT_WS(CHAR(31), source, name, model, COALESCE(spec, '')), 256)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uq_price_catalog_natural (natural_key),
  KEY idx_price_catalog_lookup (source, category, updated_at),
  CONSTRAINT chk_price_catalog_source CHECK (source IN ('danawa', 'itmaya', 'enuri'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=DYNAMIC;

-- 가격 관측 이력(append-only). 카탈로그 행이 지워지면 함께 사라진다(ON DELETE CASCADE).
CREATE TABLE price_history (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  catalog_id   BIGINT        NOT NULL,
  price_krw    BIGINT        DEFAULT NULL,
  url          VARCHAR(1000) DEFAULT NULL,
  note         VARCHAR(1000) DEFAULT NULL,
  captured_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_price_history_catalog (catalog_id, captured_at),
  CONSTRAINT fk_price_history_catalog FOREIGN KEY (catalog_id)
      REFERENCES price_catalog (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=DYNAMIC;

-- ============================================================
-- V9__deal_analysis_result.sql
-- 딜 분석(deep) 결과 저장.
--
-- deep 분석은 비싸다 — 규격서 첨부를 내려받아 파싱하고, LLM 으로 부품을 뽑고, 부품마다
-- 다나와를 긁는다. 같은 공고를 다시 열 때마다 이걸 반복하면 외부 사이트를 과하게 긁고
-- (차단 위험), LLM 도 다시 태운다. 그래서 입력이 같으면 저장된 결과를 돌려준다.
--
-- 캐시 키는 input_hash = (item + 옵션)의 정준 JSON SHA-256 이다. 단가·수량·deep·토글이
-- 바뀌면 해시가 바뀌어 자연히 다시 분석된다. force_refresh 로 무시할 수 있다.
--
-- 얕은 분석(deep=false, 시가·딜 계산만)은 싸므로 저장하지 않는다 — deep 만 눌러앉힌다.
-- ============================================================

CREATE TABLE deal_analysis_result (
  input_hash   CHAR(64)     NOT NULL,
  bid_ntce_no  VARCHAR(64)  NOT NULL DEFAULT '',
  deep         TINYINT(1)   NOT NULL DEFAULT 1,
  result_json  LONGTEXT     NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (input_hash),
  KEY idx_deal_analysis_bid (bid_ntce_no)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=DYNAMIC;

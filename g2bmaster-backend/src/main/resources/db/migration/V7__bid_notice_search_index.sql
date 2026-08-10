-- ============================================================
-- V7__bid_notice_search_index.sql
-- 공고 검색 색인 — "Bid Notice Information (입찰공고정보)" ERD 의 구현체
--
-- 이 테이블은 웨어하우스(dwt_*)가 아니라 **읽기 모델(read model)** 이다.
-- 사용자가 검색하면 backend 는 오직 이 테이블만 본다 — 나라장터 OpenAPI 는
-- 적재기(BidNoticeIngestService)가 주기적으로 두드릴 뿐, 검색 경로에는 없다.
-- 그래서 컬럼은 dwt_bid_notice 처럼 원본 필드를 전부 펴 두지 않고,
-- ERD 가 정한 22개만 둔다. 넓히고 싶은 유혹이 생기면 dwt_* 를 쓸 것.
--
-- 왜 별도 테이블인가 (dwt_bid_notice 를 그냥 조회하지 않는 이유):
--   1. dwt_bid_notice 는 (공고번호, 차수) 별 이력이다. 검색 결과에 같은 공고가
--      차수마다 반복되면 안 되므로 매 질의가 "최신 차수만" 서브쿼리를 타야 한다.
--   2. 계획·사전규격·입찰·마감이 각각 다른 테이블에 흩어져 있어 한 번에 못 훑는다.
--      ERD 의 category 4값이 정확히 그 넷을 한 테이블로 합치라는 뜻이다.
--   3. 검색은 FULLTEXT 와 좁은 행이 필요하고, 적재는 넓은 행과 이력이 필요하다.
--      한 테이블에 두 요구를 같이 걸면 둘 다 나빠진다.
--
-- 조달 생애주기 한 줄 = 한 행:
--   계획(발주계획) → 사전규격 → 입찰(공고) → 마감(입찰마감)
--   before_spec_rgst_no 가 사전규격 → 입찰 을 잇는 유일한 끈이다.
-- ============================================================


-- ============================================================
-- 1. 공고 검색 색인
-- ============================================================
CREATE TABLE `bid_notice` (
  -- ---- 식별 ----------------------------------------------------------------
  -- ERD 의 PK 는 공고번호 하나다. 차수(notice_order)는 PK 가 아니므로 한 공고번호에
  -- 행은 언제나 하나이고, 그 행이 **최신 차수**를 담는다. 정정공고가 여러 번 나도
  -- 검색 결과에 같은 공고가 여러 줄로 뜨지 않는 것이 이 선택의 이유다.
  -- 낮은 차수가 뒤늦게 도착해도 최신을 덮지 않도록, 적재기의 upsert 가
  -- notice_order 를 비교해서 막는다(BidNoticeIndexRepository.upsertAll 참고).
  `id`                      VARCHAR(64) NOT NULL COMMENT '공고번호 — 계획은 조달요청번호, 사전규격은 사전규격등록번호',
  `notice_order`            VARCHAR(64) NOT NULL DEFAULT '000' COMMENT '차수번호',

  -- 공고명은 ERD 에 없지만 검색 화면이 성립하려면 반드시 있어야 한다(제목 없는 결과
  -- 목록은 쓸 수 없다). 원본 ERD 에 대한 유일한 추가 컬럼이고, 나머지 22개는 그대로다.
  `notice_name`             VARCHAR(500) NOT NULL DEFAULT '' COMMENT '공고명 (ERD 외 추가 — 검색 결과 표시용)',

  -- ---- 분류 ----------------------------------------------------------------
  -- ENUM 인 이유: ERD 가 도메인을 값 집합 {…} 으로 못박았다. VARCHAR 로 두면
  -- 적재기 오타가 조용히 새 분류를 만들어 내고, 화면의 필터 탭이 그만큼 비어 보인다.
  `category`                ENUM('계획','사전규격','입찰','마감') NOT NULL
                            COMMENT '공고 분류 — 마감은 입찰의 시간 경과 상태다(스위퍼가 전이시킨다)',
  -- 상태 없음(=일반 등록공고)이 정상이라 NULL 을 허용한다. ERD 의 4값은 모두 '예외'다.
  `state`                   ENUM('취소','재','다시','정정') NULL COMMENT '공고 상태 — 평시 NULL',
  `business_division`       ENUM('물품','용역','공사','외자') NOT NULL COMMENT '업종코드',

  -- ---- 지역 ----------------------------------------------------------------
  -- 참가가능지역은 원본에서 공고당 N행(lmtSno)이다. 여기서는 콤마로 합쳐 한 칸에 넣고
  -- LIKE 로 건다 — 지역 하나를 위해 조인 테이블을 두면 ERD 의 "한 엔티티" 전제가 깨진다.
  -- 지역 제한이 없는 공고는 빈 문자열이며 그것이 '전국'을 뜻한다.
  `region`                  VARCHAR(40) NOT NULL DEFAULT '' COMMENT '참가가능지역 — 복수는 콤마 구분, 빈 값은 전국',

  -- ---- 기관 ----------------------------------------------------------------
  -- 기관명이 아니라 코드만 두는 것이 ERD 의 의도다. 이름은 dm_institution 에 있고
  -- 검색 API 가 LEFT JOIN 으로 붙인다 — 기관명이 바뀌어도 색인을 다시 쌓지 않아도 된다.
  `demand_institution_code` VARCHAR(15) DEFAULT NULL COMMENT '수요기관코드 → dm_institution.instt_cd',
  `notice_institution_code` VARCHAR(15) DEFAULT NULL COMMENT '공고기관코드 → dm_institution.instt_cd',

  -- ---- 생애주기 연결 -------------------------------------------------------
  `before_spec_rgst_no`     VARCHAR(64) DEFAULT NULL COMMENT '사전규격등록번호 — 사전규격 행의 id 와 맞물린다',

  -- ---- 품목 ----------------------------------------------------------------
  -- 원본은 '[1^4110412701^실험실용공급기기]' 같은 캐럿 구분 문자열이다.
  -- 그대로 두면 검색도 표시도 못 하므로 적재 시점에 JSON 배열로 편다(CaretList).
  `product_list`            JSON DEFAULT NULL COMMENT '물품목록 [{seq,code,name}]',
  `detail_product_code`     VARCHAR(20) DEFAULT NULL COMMENT '세부품명번호',

  -- ---- 금액·비율 -----------------------------------------------------------
  -- 낙찰하한율은 원본이 '88' (=88%) 로 온다. ERD 의 DECIMAL(5,3) 은 88.000 을 담는
  -- 크기이므로 백분율 그대로 저장한다(0.88 로 바꾸지 않는다 — 화면 표기가 %다).
  `lowest_bid_rate`         DECIMAL(5,3) DEFAULT NULL COMMENT '낙찰하한율 (%) — 88.000 = 88%',
  `price_detail`            JSON DEFAULT NULL COMMENT '세부 가격 표 {assignedBudget,estimatedPrice,unitPrice,quantity,unit,vat}',

  -- ---- 일시 ----------------------------------------------------------------
  `created_date`            DATETIME(6) DEFAULT NULL COMMENT '생성일자 — 공고일시/접수일시',
  `close_date`              DATETIME(6) DEFAULT NULL COMMENT '마감일자 — 입찰마감/의견등록마감',
  `updated_at`              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            COMMENT '업데이트된 시각 — 색인에 반영된 때(원본의 변경일시가 아니다)',

  -- ---- 담당자 --------------------------------------------------------------
  `officer_name`            VARCHAR(120) DEFAULT NULL COMMENT '담당자명',
  `officer_contact`         VARCHAR(120) DEFAULT NULL COMMENT '담당자연락처',

  -- ---- 본문·요약 -----------------------------------------------------------
  -- notice_body 는 '원문 HTML' 이 아니라 **검색 대상 텍스트**다. 목록 API 가 주는
  -- 서술형 필드(공고명·품명·규격·계약방법·업종제한 등)를 이어 붙인 것이며,
  -- FULLTEXT 가 실제로 훑는 것이 이 칸이다.
  `notice_body`             MEDIUMTEXT COMMENT '공고 본문 — 검색용 통합 텍스트',
  -- AI 요약은 적재기가 건드리지 않는다. 별도 파이프라인이 채우고, 재적재해도 보존된다
  -- (upsert 의 갱신 목록에서 의도적으로 빠져 있다).
  `ai_summary`              TEXT COMMENT 'AI 요약 — 적재기가 덮어쓰지 않는다',

  -- ---- 링크 ----------------------------------------------------------------
  `attachment_urls`         JSON DEFAULT NULL COMMENT '첨부파일 URL [{name,url}]',
  `source_url`              TEXT COMMENT '원본 공고 URL',

  PRIMARY KEY (`id`),

  -- 화면의 기본 정렬이 '최근 공고 순'이고 탭이 곧 category 다. 이 둘을 묶은 인덱스가
  -- 목록 질의의 기본 경로가 된다.
  KEY `ix_bid_notice_category_created` (`category`, `created_date` DESC),
  -- '마감 임박 순' 정렬과, 입찰→마감 전이 스위퍼가 같이 쓴다.
  KEY `ix_bid_notice_category_close` (`category`, `close_date`),
  KEY `ix_bid_notice_division_created` (`business_division`, `created_date` DESC),
  KEY `ix_bid_notice_region` (`region`),
  KEY `ix_bid_notice_ntce_instt` (`notice_institution_code`),
  KEY `ix_bid_notice_dmnd_instt` (`demand_institution_code`),
  KEY `ix_bid_notice_detail_prdct` (`detail_product_code`),
  -- 사전규격 → 입찰공고 교차 이동. 값이 없는 행이 대부분이지만 MySQL 에 부분 인덱스가
  -- 없으므로 그냥 건다(V1 의 변환 규칙과 같은 판단).
  KEY `ix_bid_notice_before_spec` (`before_spec_rgst_no`),
  KEY `ix_bid_notice_updated` (`updated_at`),

  -- 한국어 FULLTEXT 에는 ngram 파서가 필수다. 기본 파서는 공백으로만 끊어서
  -- '서버'로 '노트북서버'를 못 찾는다. ngram_token_size 기본값이 2라 한 글자
  -- 검색어는 걸리지 않는다 — 검색 서비스가 그 경우 LIKE 로 떨어뜨린다.
  FULLTEXT KEY `ft_bid_notice_text` (`notice_name`, `notice_body`) WITH PARSER ngram
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=DYNAMIC
  COMMENT='공고 검색 색인 — 검색 경로가 유일하게 조회하는 테이블';


-- ============================================================
-- 2. 적재 워터마크
--    "어디까지 받아왔는가"를 출처별로 기억한다. 이게 없으면 매 주기마다
--    전 기간을 다시 훑어 일일 쿼터가 순식간에 마른다.
-- ============================================================
CREATE TABLE `bid_notice_sync_state` (
  -- 'bid-announce:물품' 처럼 (오퍼레이션 계열:업종) 조합이 하나의 출처다.
  `source`         VARCHAR(64) NOT NULL COMMENT '출처 키 — 예: bid-announce:물품',

  -- 다음 조회의 시작점. 원본 API 가 등록일시 기준으로 필터하므로 여기에 담는 것도
  -- 등록일시다. 겹침(overlap)을 두고 다시 읽는 것은 적재기가 결정한다 —
  -- 워터마크 직후에 등록된 공고가 조회 시점엔 아직 안 보이는 일이 있기 때문이다.
  `watermark`      DATETIME(6) DEFAULT NULL COMMENT '다음 조회 시작점(등록일시)',

  `last_run_at`    DATETIME(6) DEFAULT NULL COMMENT '마지막 시도 시각',
  `last_success_at` DATETIME(6) DEFAULT NULL COMMENT '마지막 성공 시각',
  -- 실패 사유를 그대로 적는다. 가짜 'ok' 를 남기지 않는 것은 sync_schedule 과 같은 원칙이다
  -- (돌지 않은 적재를 돌았다고 믿게 만드는 것이 운영에서 가장 비싼 거짓말이다).
  `last_result`    TEXT COMMENT '마지막 결과 — 성공/실패 사유를 그대로',
  `last_row_count` INT DEFAULT NULL COMMENT '마지막 회차에 색인한 행 수',
  `consecutive_failures` INT NOT NULL DEFAULT 0 COMMENT '연속 실패 횟수 — 백오프 판단용',

  PRIMARY KEY (`source`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=DYNAMIC
  COMMENT='공고 검색 색인 적재 워터마크';

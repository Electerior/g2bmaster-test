-- ============================================================
-- V2__facts.sql
-- 핵심 트랜잭션(팩트) 테이블 — dwt_* 9종
-- 원본: g2bmastersopen/db/schema.sql §5 + db/migrations/001, 002, 014
--
-- ⚠ 생성 순서가 곧 FK 순서다. 바꾸지 말 것:
--   dwt_pre_specification            (dwt_bid_notice 가 참조한다)
--     -> dwt_bid_notice              (첨부·결과가 복합 FK 로 참조한다)
--       -> dwt_bid_attachment / dwt_bid_result
--     -> dwt_contract
--       -> dwt_contract_detail / dwt_contract_process
--     -> dwt_order_plan
--     -> dwt_procurement_request
--
-- ⚠ 행 크기 함정: dwt_bid_notice 는 147컬럼(+ migration 001/002 로 149)이다.
--   업무 컬럼을 VARCHAR(255) utf8mb4 로 일괄 변환하면 약 150KB 가 되어
--   ERROR 1118 "Row size too large" 로 테이블 생성 자체가 실패한다.
--   TEXT 는 9~12바이트 포인터만 행에 넣는다. 그래서 업무 컬럼은 TEXT 로 남기고
--   PK·FK·인덱스 컬럼만 VARCHAR 로 승격했다. dwt_order_plan(72)·dwt_contract(71) 도 같다.
--
-- 접어 넣은 마이그레이션:
--   001_bsns_div.sql               -> dwt_bid_notice.bsns_div_nm + 인덱스
--   002_parts_json.sql             -> dwt_bid_notice.parts_json
--   014_procurement_request_provenance.sql -> dwt_procurement_request 컬럼 29종
--   010 / 014 의 표현식 인덱스      -> V6 (COALESCE 저장 생성 컬럼)
--   009 / 014 의 GIN 인덱스         -> 삭제. MySQL 에 GIN 은 없다(아래 주석 참조)
-- ============================================================


-- ============================================================
-- 5.0 사전규격 (Pre-Specification) — 가장 먼저. dwt_bid_notice 가 참조한다.
-- ============================================================
CREATE TABLE `dwt_pre_specification` (
  `bf_spec_rgst_no`     VARCHAR(64) NOT NULL,

  -- 목록 필드
  `inqry_div`           VARCHAR(64),
  `inqry_bgn_dt`        DATETIME(6),
  `inqry_end_dt`        DATETIME(6),

  `bsns_div_nm`         VARCHAR(64),
  `prdct_clsfc_no`      VARCHAR(20),
  `prdct_clsfc_nm`      TEXT,
  `prdct_nm`            TEXT,
  `prdct_spec`          TEXT,

  `openg_dt`            DATETIME(6),
  `presmpt_prce`        DECIMAL(20,4),

  `dminstt_cd`          VARCHAR(20),
  `dminstt_nm`          TEXT,

  -- 상세 필드 (getThngDetailMetaInfoThng)
  `stdrd_opinion`       TEXT COMMENT '표준의견',
  `rel_stdrd_doc_file`  TEXT COMMENT '관련표준문서파일',
  `guidance_cont`       TEXT COMMENT '규격안내내용',
  `krn_prdct_nm`        TEXT COMMENT '한글품목명',

  -- getPublicPrcureThngInfoThng / getThngDetailMetaInfoThng 가 돌려주는 필드
  `prdct_clsfc_no_nm`   TEXT COMMENT '품목분류번호명',
  `prdct_dtl_list`      MEDIUMTEXT COMMENT '물품상세리스트 (JSON 유사 문자열) — blob 성격이라 MEDIUMTEXT',
  `bid_ntce_no_list`    MEDIUMTEXT COMMENT '입찰공고번호리스트 — blob 성격이라 MEDIUMTEXT',
  `asign_bdgt_amt`      DECIMAL(20,4) COMMENT '배정예산금액',
  `ref_no`              TEXT COMMENT '참조번호',
  `rl_dminstt_nm`       TEXT COMMENT '실수요기관명',
  `order_instt_nm`      TEXT COMMENT '발주기관명',
  `ofcl_nm`             TEXT COMMENT '담당자명',
  `ofcl_tel_no`         TEXT COMMENT '담당자전화번호',
  `sw_biz_obj_yn`       CHAR(1) COMMENT 'SW사업대상여부',
  `dlvr_daynum`         TEXT COMMENT '납품일수',
  `dlvr_tmlmt_dt`       DATETIME(6) COMMENT '납품기한일시',
  `opnin_rgst_clse_dt`  DATETIME(6) COMMENT '의견등록마감일시',
  `rcpt_dt`             DATETIME(6) COMMENT '접수일시',
  `rgst_dt`             DATETIME(6) COMMENT '등록일시',
  `chg_dt`              DATETIME(6) COMMENT '변경일시',

  -- 규격서 파일 URL (specDocFileUrl1..5)
  `spec_doc_file_url1`  TEXT,
  `spec_doc_file_url2`  TEXT,
  `spec_doc_file_url3`  TEXT,
  `spec_doc_file_url4`  TEXT,
  `spec_doc_file_url5`  TEXT,

  -- UUID[] -> JSON 문자열 배열. 여러 호출이 한 사전규격에 기여할 수 있다.
  -- migration 009 의 GIN 인덱스는 옮기지 않았다: MySQL 에 GIN 이 없고, 이 컬럼은
  -- 계보(lineage) 용이라 조회되지 않는다. 조회가 필요해지면 접합 테이블
  -- pre_spec_api_call(bf_spec_rgst_no, call_id) 로 정규화하는 것이 올바른 대체다
  -- (JPA @OneToMany 가 되고 read-modify-write 도 사라진다).
  `api_call_id_list`    JSON NOT NULL DEFAULT (JSON_ARRAY()),
  `created_at`          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`bf_spec_rgst_no`),
  KEY `idx_dwt_pre_spec_dminstt` (`dminstt_cd`),
  KEY `idx_dwt_pre_spec_prdct` (`prdct_clsfc_no`),
  CONSTRAINT `fk_dwt_pre_spec_prdct_clsfc` FOREIGN KEY (`prdct_clsfc_no`)
    REFERENCES `dm_product_classification` (`clsfc_no`),
  CONSTRAINT `fk_dwt_pre_spec_dminstt` FOREIGN KEY (`dminstt_cd`)
    REFERENCES `dm_institution` (`instt_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.1 입찰공고 (Bid Notice) — 스키마의 중심. 147컬럼 + migration 001/002 로 149컬럼.
-- ============================================================
CREATE TABLE `dwt_bid_notice` (
  -- 자연키
  `bid_ntce_no`                      VARCHAR(64) NOT NULL,
  `bid_ntce_ord`                     VARCHAR(64) NOT NULL,

  -- 분류·유형
  `re_ntce_yn`                       CHAR(1) DEFAULT 'N' COMMENT '재공고여부',
  `rgst_ty_nm`                       TEXT COMMENT '등록유형명',
  `ntce_kind_nm`                     TEXT COMMENT '공고구분명',
  `intrbid_yn`                       CHAR(1) DEFAULT 'N' COMMENT '국제입찰여부',

  -- 일시 (전부 UTC DATETIME(6))
  `bid_ntce_dt`                      DATETIME(6) COMMENT '공고일시',
  `bid_begin_dt`                     DATETIME(6) COMMENT '입찰개시일시',
  `bid_clse_dt`                      DATETIME(6) COMMENT '입찰마감일시',
  `openg_dt`                         DATETIME(6) COMMENT '개찰일시',
  `rbid_openg_dt`                    DATETIME(6) COMMENT '재입찰개찰일시',

  -- 참조번호
  `ref_no`                           TEXT COMMENT '참조번호',
  `bid_ntce_nm`                      TEXT COMMENT '입찰공고명',

  -- 기관
  `ntce_instt_cd`                    VARCHAR(20),
  `ntce_instt_nm`                    TEXT,
  `dminstt_cd`                       VARCHAR(20),
  `dminstt_nm`                       TEXT,

  -- 방법·유형
  `bid_methd_nm`                     TEXT COMMENT '입찰방법명',
  `cntrct_cncls_mthd_nm`             TEXT COMMENT '계약체결방법명',

  -- 담당자 정보
  `ntce_instt_ofcl_nm`               TEXT,
  `ntce_instt_ofcl_tel`              TEXT,
  `ntce_instt_ofcl_email`            TEXT,
  `exctv_nm`                         TEXT,

  -- 자격
  `bid_qlfct_rgst_dt`                DATETIME(6) COMMENT '입찰참가자격등록마감일시',

  -- 공동수급
  `cmmn_spldmd_agrnmnt_rcptdoc_methd` TEXT,
  `cmmn_spldmd_agrnmnt_clse_dt`      DATETIME(6),
  `cmmn_spldmd_corp_rgn_lmt_yn`      CHAR(1),

  -- 가격
  `prearng_prce_dcsn_mthd_nm`        TEXT COMMENT '예정가격결정방법',
  `tot_prdprc_num`                   INT COMMENT '전체예가번호수',
  `drwt_prdprc_num`                  INT COMMENT '추첨예가번호수',
  `asign_bdgt_amt`                   DECIMAL(20,4) COMMENT '배정예산액',
  `presmpt_prce`                     DECIMAL(20,4) COMMENT '예정가격',

  -- 장소·상세
  `openg_plce`                       TEXT,
  `bid_ntce_dtl_url`                 TEXT COMMENT '상세URL',
  `bid_ntce_url`                     TEXT COMMENT '공고URL',

  -- 물품 정보
  `prdct_clsfc_lmt_yn`               CHAR(1),
  `mnfct_yn`                         CHAR(1),
  `dtil_prdct_clsfc_no`              VARCHAR(20),
  `dtil_prdct_clsfc_nm`              TEXT,
  `prdct_spec_nm`                    TEXT,
  `prdct_qty`                        DECIMAL(20,4),
  `prdct_unit`                       TEXT,
  `prdct_uprc`                       DECIMAL(20,4),

  -- 납품
  `dlvr_tmlmt_dt`                    DATE,
  `dlvr_daynum`                      INT,
  `dlvry_cndtn_nm`                   TEXT,
  `purchs_obj_prdct_list`            MEDIUMTEXT COMMENT '구매대상물품리스트 (JSON 유사 문자열) — blob 성격',

  `unty_ntce_no`                     VARCHAR(64) COMMENT '통합공고번호',

  -- 공동수급 방식
  `cmmn_spldmd_methd_cd`             TEXT,
  `cmmn_spldmd_methd_nm`             TEXT,

  -- 표준 문서
  `std_ntce_doc_url`                 TEXT,

  -- 입찰보증
  `brffc_bidprc_permsn_yn`           CHAR(1),
  `dsgnt_cmpt_yn`                    CHAR(1),

  -- 예비가격
  `rsrvtn_prce_re_mkng_mthd_nm`      TEXT,
  `arslt_appl_doc_rcpt_mthd_nm`      TEXT,
  `arslt_appl_doc_rcpt_dt`           DATETIME(6),

  -- 발주계획 링크
  `order_plan_unty_no`               VARCHAR(64),

  -- 낙찰
  `sucsfbid_lwlt_rate`               DECIMAL(9,4) COMMENT '낙찰하한율',
  `sucsfbid_mthd_cd`                 TEXT,
  `sucsfbid_mthd_nm`                 TEXT,
  `sucsfbid_mthd_app_std`            TEXT,

  -- 변경 정보
  `chg_dt`                           DATETIME(6),
  `chg_ntce_rsn`                     TEXT,

  -- 사전규격 링크
  `bf_spec_rgst_no`                  VARCHAR(64),

  -- 기타
  `info_biz_yn`                      CHAR(1),
  `indstryty_lmt_yn`                 CHAR(1),

  -- 부가세
  `vat_amt`                          DECIMAL(20,4),
  `induty_vat`                       DECIMAL(20,4),

  -- 입찰보증금
  `bid_wgrntee_rcpt_clse_dt`         DATETIME(6),

  -- 지역 제한
  `rgn_lmt_bid_locplc_jdgm_bss_cd`   TEXT,
  `rgn_lmt_bid_locplc_jdgm_bss_nm`   TEXT,

  -- 평가 비율
  `tech_abl_evl_rt`                  DECIMAL(9,4),
  `bid_prce_evl_rt`                  DECIMAL(9,4),

  -- 등록
  `rgst_dt`                          DATETIME(6),

  -- ----------------------------------------------------------------
  -- Cnstwk(공사)/Servc(용역)/Frgcpt(외자) 오퍼레이션 문서에 실린 필드.
  -- 출처: 조달청_OpenAPI참고자료_나라장터_입찰공고정보서비스_1.0.docx
  -- ----------------------------------------------------------------
  `bdgt_amt`                         DECIMAL(20,4) COMMENT '예산금액',
  `govsply_amt`                      DECIMAL(20,4) COMMENT '관급금액',
  `contrctrcnstrtn_govsply_mtrl_amt` DECIMAL(20,4) COMMENT '도급자설치관급자재금액',
  `govcnstrtn_govsply_mtrl_amt`      DECIMAL(20,4) COMMENT '관급자설치관급자재금액',
  `apl_bss_cntnts`                   TEXT COMMENT '적용기준내용',
  `arslt_cmpt_yn`                    CHAR(1) COMMENT '실적경쟁여부',
  `arslt_reqstdoc_rcpt_dt`           DATETIME(6) COMMENT '실적신청서접수일시',
  `bid_prtcpt_lmt_yn`                CHAR(1) COMMENT '입찰참가제한여부',
  `cibl_apl_yn`                      CHAR(1) COMMENT '건설산업법적용대상여부',
  `cmmn_spldmd_cnum`                 DECIMAL(20,4) COMMENT '공동수급업체수',
  `cnstrtn_ablty_evl_amt_list`       MEDIUMTEXT COMMENT '시공능력평가금액목록 — blob 성격',
  `cnstrtsite_rgn_nm`                TEXT COMMENT '공사현장지역명',
  `cnstty_accot_shre_rate_list`      MEDIUMTEXT COMMENT '공종별지분율목록 — blob 성격',
  `dcmtg_oprtn_dt`                   DATETIME(6) COMMENT '설명회실시일시',
  `dcmtg_oprtn_plce`                 TEXT COMMENT '설명회실시장소',
  `dtls_bid_yn`                      CHAR(1) COMMENT '내역입찰여부',
  `ntce_dscrpt_yn`                   CHAR(1) COMMENT '공고설명여부',
  `ppsw_gnrl_srvce_yn`               CHAR(1) COMMENT '조달청일반용역여부',
  `srvce_div_nm`                     TEXT COMMENT '용역구분명',
  `prdct_sno`                        TEXT COMMENT '물품순번',
  `mtlty_advc_psbl_yn`               CHAR(1) COMMENT '상호시장진출허용여부',
  `mtlty_advc_psbl_yn_cnstwk_nm`     TEXT COMMENT '건설산업법적용대상공사명 (이름이 _yn 으로 끝나지 않는다 — 명칭 컬럼)',

  -- 주공종 / 부대공종
  `main_cnstty_nm`                   TEXT COMMENT '주공종명',
  `main_cnstty_cnstwk_prearng_amt`   DECIMAL(20,4) COMMENT '주공종공사예정금액',
  `main_cnstty_presmpt_prce`         DECIMAL(20,4) COMMENT '주공종추정가격',
  `indstryty_evl_rt`                 DECIMAL(9,4) COMMENT '업종평가비율',
  `indstryty_mfrc_fld_evl_yn`        CHAR(1) COMMENT '주력분야평가여부',
  `subsi_cnstty_nm1`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt1`   DECIMAL(9,4),
  `subsi_cnstty_nm2`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt2`   DECIMAL(9,4),
  `subsi_cnstty_nm3`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt3`   DECIMAL(9,4),
  `subsi_cnstty_nm4`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt4`   DECIMAL(9,4),
  `subsi_cnstty_nm5`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt5`   DECIMAL(9,4),
  `subsi_cnstty_nm6`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt6`   DECIMAL(9,4),
  `subsi_cnstty_nm7`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt7`   DECIMAL(9,4),
  `subsi_cnstty_nm8`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt8`   DECIMAL(9,4),
  `subsi_cnstty_nm9`                 TEXT,
  `subsi_cnstty_indstryty_evl_rt9`   DECIMAL(9,4),

  -- 지역 제한 / 가산
  `rgn_duty_jntcontrct_yn`           CHAR(1) COMMENT '지역의무공동도급여부',
  `rgn_duty_jntcontrct_rt`           DECIMAL(9,4) COMMENT '지역의무공동도급비율',
  `jntcontrct_duty_rgn_nm1`          TEXT COMMENT '공동도급의무지역명1',
  `jntcontrct_duty_rgn_nm2`          TEXT,
  `jntcontrct_duty_rgn_nm3`          TEXT,
  `incntv_rgn_nm1`                   TEXT COMMENT '가산지역명1',
  `incntv_rgn_nm2`                   TEXT,
  `incntv_rgn_nm3`                   TEXT,
  `incntv_rgn_nm4`                   TEXT,

  -- PQ / TP 심사
  `pq_eval_yn`                       CHAR(1) COMMENT 'PQ심사여부',
  `pq_appl_doc_rcpt_dt`              DATETIME(6) COMMENT 'PQ신청서접수일시',
  `pq_appl_doc_rcpt_mthd_nm`         TEXT COMMENT 'PQ신청서접수방법명',
  `tp_eval_yn`                       CHAR(1) COMMENT 'TP심사여부',
  `tp_eval_appl_clse_dt`             DATETIME(6) COMMENT 'TP심사신청마감일시',
  `tp_eval_appl_mthd_nm`             TEXT COMMENT 'TP심사신청방법명',

  -- 공공조달분류
  `pub_prcrmnt_clsfc_no`             TEXT COMMENT '공공조달분류번호',
  `pub_prcrmnt_clsfc_nm`             TEXT COMMENT '공공조달분류명',
  `pub_prcrmnt_lrgclsfc_nm`          TEXT COMMENT '공공조달대분류명',
  `pub_prcrmnt_midclsfc_nm`          TEXT COMMENT '공공조달중분류명',

  -- 현장설명서
  `spt_dscrpt_doc_url1`              TEXT,
  `spt_dscrpt_doc_url2`              TEXT,
  `spt_dscrpt_doc_url3`              TEXT,
  `spt_dscrpt_doc_url4`              TEXT,
  `spt_dscrpt_doc_url5`              TEXT,

  -- migration 001_bsns_div.sql: 적재기 operation 명의 …Thng / …Cnstwk / …Servc / …Frgcpt
  -- 접미사가 물품/공사/용역/외자로 매핑된다. upsert_records() 가 앞으로 채운다.
  `bsns_div_nm`                      VARCHAR(64) COMMENT '업무구분명 (migration 001)',

  -- migration 002_parts_json.sql: /api/profit-estimate 파이프라인용 사이드카.
  -- 가격이 매겨진 부품표를 담아 두어 같은 공고를 다시 분석할 때
  -- price_search 스크레이핑과 DeepSpecExtractor 호출을 건너뛴다.
  `parts_json`                       JSON COMMENT '부품표 캐시 (migration 002)',

  -- 메타데이터
  `api_call_id`                      CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `raw_json`                         JSON COMMENT '감사용 원본 응답 전문',
  `created_at`                       DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`                       DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`bid_ntce_no`, `bid_ntce_ord`),

  -- 최근 공고 조회용. 원본에도 부분 인덱스가 아니었다 —
  -- `WHERE bid_ntce_dt > now() - interval '1 year'` 술어는 PostgreSQL 이 거부하고
  -- (now() 는 STABLE 이지 IMMUTABLE 이 아니다), 설령 됐어도 인덱스 생성 시점에
  -- 기준일이 얼어붙어 "지금"을 따라가지 못한다.
  KEY `idx_dwt_bid_notice_dates` (`bid_ntce_dt` DESC),
  KEY `idx_dwt_bid_notice_dminstt` (`dminstt_cd`),
  KEY `idx_dwt_bid_notice_ntce_instt` (`ntce_instt_cd`),
  KEY `idx_dwt_bid_notice_presmpt` (`presmpt_prce` DESC),
  -- 원본은 WHERE bf_spec_rgst_no IS NOT NULL 부분 인덱스. 술어가 NULL 제외뿐이라 그대로 뗀다.
  KEY `idx_dwt_bid_notice_bf_spec` (`bf_spec_rgst_no`),
  KEY `idx_dwt_bid_notice_unty` (`unty_ntce_no`),
  KEY `idx_dwt_bid_notice_bsns_div` (`bsns_div_nm`),

  CONSTRAINT `fk_dwt_bid_notice_ntce_instt` FOREIGN KEY (`ntce_instt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_bid_notice_dminstt` FOREIGN KEY (`dminstt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_bid_notice_dtil_clsfc` FOREIGN KEY (`dtil_prdct_clsfc_no`)
    REFERENCES `dm_detail_product_classification` (`dtl_clsfc_no`),
  CONSTRAINT `fk_dwt_bid_notice_bf_spec` FOREIGN KEY (`bf_spec_rgst_no`)
    REFERENCES `dwt_pre_specification` (`bf_spec_rgst_no`),
  CONSTRAINT `fk_dwt_bid_notice_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.2 입찰공고 첨부파일 (Bid Attachments) — 1:N
-- ============================================================
CREATE TABLE `dwt_bid_attachment` (
  `bid_ntce_no`        VARCHAR(64) NOT NULL,
  `bid_ntce_ord`       VARCHAR(64) NOT NULL,
  `file_seq`           SMALLINT NOT NULL COMMENT '1-10',

  `file_url`           TEXT COMMENT 'ntceSpecDocUrl1-10',
  `file_nm`            TEXT COMMENT 'ntceSpecFileNm1-10',
  `file_ty_cd`         TEXT COMMENT 'SPEC, REGULATION, GUIDE, OTHER',
  `file_size`          BIGINT,
  `file_extsn`         TEXT,
  `download_cnt`       INT DEFAULT 0,
  `last_downloaded_at` DATETIME(6),

  -- 표준문서 (단일)
  `is_std_doc`         TINYINT(1) DEFAULT 0 COMMENT 'stdNtceDocUrl 과 일치하는가',

  `api_call_id`        CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `created_at`         DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`bid_ntce_no`, `bid_ntce_ord`, `file_seq`),
  -- 원본은 WHERE file_url IS NOT NULL 부분 인덱스였다. 술어는 떼고,
  -- file_url 은 URL 이라 utf8mb4 인덱스 키 상한(3072바이트 = 768자)을 넘을 수 있으므로
  -- 255자 접두 인덱스로 만든다.
  KEY `idx_dwt_bid_att_url` (`file_url`(255)),
  CONSTRAINT `fk_dwt_bid_att_notice` FOREIGN KEY (`bid_ntce_no`, `bid_ntce_ord`)
    REFERENCES `dwt_bid_notice` (`bid_ntce_no`, `bid_ntce_ord`) ON DELETE CASCADE,
  CONSTRAINT `fk_dwt_bid_att_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.3 개찰/낙찰 결과 (Bid Result)
-- ============================================================
CREATE TABLE `dwt_bid_result` (
  `bid_ntce_no`                  VARCHAR(64) NOT NULL,
  `bid_ntce_ord`                 VARCHAR(64) NOT NULL,
  `bid_clsfc_no`                 VARCHAR(64) NOT NULL,
  `rbid_no`                      VARCHAR(64) NOT NULL DEFAULT '00',

  -- 결과 정보
  `inpt_dt`                      DATETIME(6) COMMENT '입력일시',
  `ntce_instt_cd`                VARCHAR(20),
  `ntce_instt_nm`                TEXT,
  `openg_dt`                     DATETIME(6) COMMENT '개찰일시',
  `openg_rslt_ntc_cntnts`        TEXT COMMENT '개찰결과공고내용',
  `openg_corp_info`              TEXT COMMENT '개찰업체정보',

  -- 낙찰자
  `bidwinnr_nm`                  TEXT COMMENT '최종낙찰업체명',
  `bidwinnr_bizno`               VARCHAR(32),
  `bidwinnr_ceo_nm`              TEXT,
  `bidwinnr_adrs`                TEXT,
  `bidwinnr_tel_no`              TEXT,
  `sucsfbid_amt`                 DECIMAL(20,4) COMMENT '최종낙찰금액',
  `sucsfbid_rate`                DECIMAL(9,4) COMMENT '최종낙찰률',
  `rl_openg_dt`                  DATETIME(6) COMMENT '실개찰일시',

  -- 수요기관
  `dminstt_cd`                   VARCHAR(20),
  `dminstt_nm`                   TEXT,

  `prtcpt_cnum`                  INT COMMENT '참가업체수',

  -- 등록
  `rgst_dt`                      DATETIME(6),
  `fnl_sucsf_date`               DATE COMMENT '최종낙찰일자',
  `fnl_sucsf_corp_ofcl`          TEXT,

  -- getOpengResultListInfoThng 가 추가로 돌려주는 필드
  `bid_ntce_nm`                  TEXT COMMENT '입찰공고명',
  `progrs_div_cd_nm`             TEXT COMMENT '진행구분코드명',
  `rsrvtn_prce_file_existnce_yn` CHAR(1) COMMENT '예비가격파일존재여부',

  `api_call_id`                  CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `created_at`                   DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`bid_ntce_no`, `bid_ntce_ord`, `bid_clsfc_no`, `rbid_no`),
  KEY `idx_dwt_bid_result_ntce_instt` (`ntce_instt_cd`),
  KEY `idx_dwt_bid_result_dminstt` (`dminstt_cd`),
  KEY `idx_dwt_bid_result_bidwinnr` (`bidwinnr_bizno`),
  CONSTRAINT `fk_dwt_bid_result_notice` FOREIGN KEY (`bid_ntce_no`, `bid_ntce_ord`)
    REFERENCES `dwt_bid_notice` (`bid_ntce_no`, `bid_ntce_ord`) ON DELETE CASCADE,
  CONSTRAINT `fk_dwt_bid_result_ntce_instt` FOREIGN KEY (`ntce_instt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_bid_result_dminstt` FOREIGN KEY (`dminstt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_bid_result_bidwinnr` FOREIGN KEY (`bidwinnr_bizno`)
    REFERENCES `dm_supplier` (`biz_no`),
  CONSTRAINT `fk_dwt_bid_result_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.4 계약 (Contract) — 71컬럼. 업무 컬럼은 TEXT 로 남긴다(행 크기).
-- ============================================================
CREATE TABLE `dwt_contract` (
  `cntrct_no`                   VARCHAR(64) NOT NULL,
  `unty_cntrct_no`              VARCHAR(64),

  `bid_ntce_no`                 VARCHAR(64),
  `bid_ntce_ord`                VARCHAR(64),

  -- 기관
  `dminstt_cd`                  VARCHAR(20),
  `dminstt_nm`                  TEXT,
  `cntrct_instt_cd`             VARCHAR(20),
  `cntrct_instt_nm`             TEXT,

  -- 계약 내용
  `cntrct_nm`                   TEXT,
  `cntrct_amt`                  DECIMAL(20,4),
  `cntrct_dt`                   DATE,
  `cntrct_term_bgn_dt`          DATE,
  `cntrct_term_end_dt`          DATE,

  `bsns_div_nm`                 VARCHAR(64),
  `cntrct_clsfc_nm`             TEXT,

  -- 공급자
  `scsbdlr_bizno`               VARCHAR(32),
  `scsbdlr_nm`                  TEXT,
  `scsbdlr_ceo_nm`              TEXT,
  `scsbdlr_adrs`                TEXT,

  -- 이행
  `perfm_dt`                    DATE COMMENT '이행일자',
  `perfm_rate`                  DECIMAL(9,4) COMMENT '이행률',

  `sucsfbid_mthd_nm`            TEXT,

  -- getCntrctInfoListThng 가 추가로 돌려주는 필드.
  -- 주: API 의 cntrctRefNo 는 적재기가 cntrct_no PK 로 매핑하므로
  -- 일부러 별도 컬럼으로 중복시키지 않았다.
  `dcsn_cntrct_no`              TEXT COMMENT '확정계약번호',
  `ntce_no`                     TEXT COMMENT '공고번호',
  `req_no`                      TEXT COMMENT '요청번호',
  `cntrct_cncls_mthd_nm`        TEXT COMMENT '계약체결방법명',
  `lngtrm_ctnu_div_nm`          TEXT COMMENT '장기계속구분명',
  `pay_div_nm`                  TEXT COMMENT '지급구분명',
  `cmmn_cntrct_yn`              CHAR(1) COMMENT '공동계약여부',
  `info_biz_yn`                 CHAR(1) COMMENT '정보화사업여부',
  `cntrct_prd`                  TEXT COMMENT '계약기간',
  `base_dtls`                   TEXT COMMENT '근거내역',
  `base_law_nm`                 TEXT COMMENT '근거법령명',

  -- 공공조달분류
  `pub_prcrmnt_clsfc_no`        TEXT,
  `pub_prcrmnt_clsfc_nm`        TEXT,
  `pub_prcrmnt_lrg_clsfc_nm`    TEXT,
  `pub_prcrmnt_mid_clsfc_nm`    TEXT,

  -- 계약기관 담당자
  `cntrct_instt_chrg_dept_nm`   TEXT,
  `cntrct_instt_ofcl_nm`        TEXT,
  `cntrct_instt_ofcl_tel_no`    TEXT,
  `cntrct_instt_ofcl_fax_no`    TEXT,
  `cntrct_instt_jrsdctn_div_nm` TEXT,

  -- 금액·비율
  `tot_cntrct_amt`              DECIMAL(20,4) COMMENT '총계약금액',
  `thtm_cntrct_amt`             DECIMAL(20,4) COMMENT '차수계약금액',
  `grntymny_rate`               DECIMAL(9,4) COMMENT '보증금율',
  `dfrcmpnst_rt`                DECIMAL(9,4) COMMENT '지체상금율',

  -- 참여자 목록 (JSON 유사 문자열) — blob 성격이라 MEDIUMTEXT
  `corp_list`                   MEDIUMTEXT COMMENT '업체리스트',
  `dminstt_list`                MEDIUMTEXT COMMENT '수요기관리스트',

  -- URL·일자
  `cntrct_info_url`             TEXT COMMENT '계약정보URL',
  `cntrct_dtl_info_url`         TEXT COMMENT '계약상세정보URL',
  `cntrct_date`                 DATE COMMENT '계약일자',
  `cntrct_cncls_date`           DATE COMMENT '계약체결일자',
  `rgst_dt`                     DATETIME(6) COMMENT '등록일시',
  `chg_dt`                      DATETIME(6) COMMENT '변경일시',

  -- Cnstwk(공사)/Servc(용역)/Frgcpt(외자) 오퍼레이션 문서에 실린 필드.
  -- 출처: 조달청_OpenAPI참고자료_나라장터_계약정보서비스_1.0.docx
  `cnstwk_nm`                   TEXT COMMENT '공사명',
  `wbgn_date`                   DATE COMMENT '착수일자',
  `cbgn_date`                   DATE COMMENT '착공일자',
  `thtm_ccmplt_date`            DATE COMMENT '금차준공일자',
  `thtm_scmplt_date`            DATE COMMENT '금차완수일자',
  `ttal_ccmplt_date`            DATE COMMENT '총준공일자',
  `ttal_scmplt_date`            DATE COMMENT '총완수일자',
  `tot_cntrct_amt_crncy`        TEXT COMMENT '총계약금액통화',
  `thtm_cntrct_amt_crncy`       TEXT COMMENT '금차계약금액통화',
  `prces_change_apl_bss_cd`     TEXT COMMENT '물가변동적용기준코드',
  `prces_change_apl_bss_nm`     TEXT COMMENT '물가변동적용기준명',
  `pub_prcrmnt_lrgclsfc_nm`     TEXT COMMENT '공공조달대분류명',
  `pub_prcrmnt_midclsfc_nm`     TEXT COMMENT '공공조달중분류명',

  -- 메타데이터
  `api_call_id`                 CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `raw_json`                    JSON,
  `created_at`                  DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`                  DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`cntrct_no`),
  KEY `idx_dwt_contract_bid` (`bid_ntce_no`, `bid_ntce_ord`),
  KEY `idx_dwt_contract_supplier` (`scsbdlr_bizno`),
  KEY `idx_dwt_contract_dminstt` (`dminstt_cd`),
  KEY `idx_dwt_contract_dates` (`cntrct_dt` DESC),
  KEY `idx_dwt_contract_instt` (`cntrct_instt_cd`),
  CONSTRAINT `fk_dwt_contract_dminstt` FOREIGN KEY (`dminstt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_contract_instt` FOREIGN KEY (`cntrct_instt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_contract_supplier` FOREIGN KEY (`scsbdlr_bizno`)
    REFERENCES `dm_supplier` (`biz_no`),
  CONSTRAINT `fk_dwt_contract_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.5 계약 상세 (Contract Detail — 1:N 부가 정보)
-- ============================================================
CREATE TABLE `dwt_contract_detail` (
  `detail_id`    BIGINT NOT NULL AUTO_INCREMENT COMMENT 'BIGSERIAL 대체',
  `cntrct_no`    VARCHAR(64) NOT NULL,
  `detail_ty_cd` VARCHAR(64) NOT NULL COMMENT 'SPEC, TERM, PAYMENT, CHANGE, ETC — UNIQUE 에 쓰여 VARCHAR',
  `detail_seq`   SMALLINT NOT NULL,
  `detail_nm`    TEXT,
  `detail_val`   TEXT,
  `detail_json`  JSON,
  `api_call_id`  CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `created_at`   DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`detail_id`),
  UNIQUE KEY `uk_dwt_contract_detail` (`cntrct_no`, `detail_ty_cd`, `detail_seq`),
  CONSTRAINT `fk_dwt_contract_detail_cntrct` FOREIGN KEY (`cntrct_no`)
    REFERENCES `dwt_contract` (`cntrct_no`) ON DELETE CASCADE,
  CONSTRAINT `fk_dwt_contract_detail_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.6 계약과정통합공개 (Contract Process Integration)
-- 출처: 조달청_OpenAPI참고자료_나라장터_계약과정통합공개서비스_1.0.docx
-- 단계별 이벤트 로그가 아니다. 문서화된 응답은 공고 하나당 한 행이고
-- 생애주기 전체를 하나로 꿴다: 발주계획 -> 사전규격 -> 조달요청 -> 입찰공고 -> 낙찰자 -> 계약
-- 예전에는 (proc_ty_cd, proc_seq) 단계에 BIGSERIAL 키로 모델링했는데 API 가 그 값을
-- 전혀 채우지 않아서 NOT NULL 컬럼이 모든 INSERT 를 거부했고, 시리얼 키는 충돌할 수가
-- 없으니 재실행하면 영원히 중복이 쌓였다.
-- ============================================================
CREATE TABLE `dwt_contract_process` (
  `bid_ntce_no`           VARCHAR(64) NOT NULL COMMENT '입찰공고번호',
  `bid_ntce_ord`          VARCHAR(64) NOT NULL COMMENT '입찰공고차수',

  -- 발주계획
  `order_plan_unty_no`    VARCHAR(64) COMMENT '발주계획통합번호',
  `order_plan_no`         TEXT COMMENT '발주계획번호',
  `order_biz_nm`          TEXT COMMENT '발주사업명',
  `order_instt_nm`        TEXT COMMENT '발주기관명',
  `order_ym`              TEXT COMMENT '발주년월',
  `prcrmnt_methd_nm`      TEXT COMMENT '조달방식명',
  `cntrct_cncls_mthd_nm`  TEXT COMMENT '계약체결방법명',

  -- 사전규격
  `bf_spec_rgst_no`       VARCHAR(64) COMMENT '사전규격등록번호',
  `bf_spec_biz_nm`        TEXT COMMENT '사전규격사업명',
  `bf_spec_dminstt_nm`    TEXT COMMENT '사전규격수요기관명',
  `bf_spec_ntce_instt_nm` TEXT COMMENT '사전규격공고기관명',
  `opnin_rgst_clse_dt`    DATETIME(6) COMMENT '의견등록마감일시',

  -- 조달요청 / 입찰공고
  `prcrmnt_req_no`        VARCHAR(64) COMMENT '조달요청번호',
  `bid_ntce_nm`           TEXT COMMENT '입찰공고명',
  `bid_dminstt_nm`        TEXT COMMENT '입찰수요기관명',
  `bid_mthd_nm`           TEXT COMMENT '입찰방법명',
  `bid_ntce_dt`           DATETIME(6) COMMENT '입찰공고일시',

  -- 낙찰 / 계약 (JSON 유사 목록, 각 4000자까지) — blob 성격이라 MEDIUMTEXT
  `bidwinr_info_list`     MEDIUMTEXT COMMENT '낙찰자정보목록',
  `cntrct_info_list`      MEDIUMTEXT COMMENT '계약정보목록',

  `api_call_id`           CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `created_at`            DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`            DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  -- (bid_ntce_no, bid_ntce_ord) 에 별도 인덱스를 두지 않는다: 그것이 곧 PK 다.
  PRIMARY KEY (`bid_ntce_no`, `bid_ntce_ord`),
  CONSTRAINT `fk_dwt_contract_process_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.8 발주계획 (Order Plan) — 72컬럼
-- 키: getOrderPlanSttusListThng 은 orderPlanUntyNo + orderPlanSno 로 행을 식별한다.
-- orderPlanNo 는 아예 보내지 않고 orderOrd 는 빈 값으로 도착하므로 키가 될 수 없다 —
-- 그 둘을 채우는 다른 발주계획 오퍼레이션을 위해 nullable 컬럼으로 남겨 둔다.
-- ============================================================
CREATE TABLE `dwt_order_plan` (
  `order_plan_unty_no`       VARCHAR(64) NOT NULL COMMENT '발주계획통합번호',
  `order_plan_sno`           VARCHAR(64) NOT NULL COMMENT '발주계획일련번호',

  `order_plan_no`            TEXT,
  `order_plan_ord`           TEXT,

  `order_bgn_ym`             TEXT,
  `order_end_ym`             TEXT,

  `prdct_clsfc_no`           VARCHAR(20),
  `prdct_clsfc_nm`           TEXT,
  `order_plan_nm`            TEXT,

  `bsns_div_nm`              VARCHAR(64),
  `estim_prce`               DECIMAL(20,4),
  `order_plan_dc`            TEXT,

  `dminstt_cd`               VARCHAR(20),
  `dminstt_nm`               TEXT,

  -- getOrderPlanSttusListThng 가 추가로 돌려주는 필드
  `order_ord`                TEXT COMMENT '발주차수 (실제로는 빈 값으로 온다)',
  `order_year`               TEXT COMMENT '발주년도',
  `order_mnth`               TEXT COMMENT '발주월',
  `biz_nm`                   TEXT COMMENT '사업명',
  `bsns_div_cd`              TEXT COMMENT '업무구분코드',
  `bsns_ty_cd`               TEXT COMMENT '사업유형코드',
  `bsns_ty_nm`               TEXT COMMENT '사업유형명',
  `bdgt_div_cd`              TEXT COMMENT '예산구분코드',
  `prcrmnt_methd`            TEXT COMMENT '조달방법',
  `cntrct_mthd_nm`           TEXT COMMENT '계약방법명',
  `agrmnt_yn`                CHAR(1) COMMENT '협정여부',
  `ntce_ntice_yn`            CHAR(1) COMMENT '공고게시여부',

  -- 발주기관
  `order_instt_cd`           TEXT,
  `order_instt_nm`           TEXT,
  `totlmng_instt_nm`         TEXT COMMENT '총괄관리기관명',
  `jrsdctn_div_cd`           TEXT COMMENT '관할구분코드',
  `jrsdctn_div_nm`           TEXT COMMENT '관할구분명',
  `dept_nm`                  TEXT COMMENT '부서명',
  `ofcl_nm`                  TEXT COMMENT '담당자명',
  `tel_no`                   TEXT COMMENT '전화번호',

  -- 발주금액
  `sum_order_amt`            DECIMAL(20,4) COMMENT '발주금액합계',
  `sum_order_dol_amt`        DECIMAL(20,4) COMMENT '발주금액합계(달러)',
  `order_contrct_amt`        DECIMAL(20,4) COMMENT '발주계약금액',
  `order_thtm_contrct_amt`   DECIMAL(20,4) COMMENT '발주차수계약금액',
  `order_govsply_mtrcst`     DECIMAL(20,4) COMMENT '발주관급자재비',
  `order_ntntrs_aux_amt`     DECIMAL(20,4) COMMENT '발주내자보조금액',
  `order_etc_amt`            DECIMAL(20,4) COMMENT '발주기타금액',

  -- 물품분류 상세
  `dtil_prdct_clsfc_no`      VARCHAR(20) COMMENT '세부품명번호',
  `dtil_prdct_clsfc_nm`      TEXT COMMENT '세부품명',
  `prdct_clsfc_no_nm`        TEXT COMMENT '품목분류번호명',
  `unit`                     TEXT COMMENT '단위',
  `qty_cntnts`               TEXT COMMENT '수량내용',

  -- 규격
  `spec_cntnts`              TEXT COMMENT '규격내용',
  `spec_item_nm1`            TEXT,
  `spec_item_cntnts1`        TEXT,
  `spec_item_nm2`            TEXT,
  `spec_item_cntnts2`        TEXT,
  `spec_item_nm3`            TEXT,
  `spec_item_cntnts3`        TEXT,
  `spec_item_nm4`            TEXT,
  `spec_item_cntnts4`        TEXT,
  `spec_item_nm5`            TEXT,
  `spec_item_cntnts5`        TEXT,

  -- 공사 전용
  `cnstwk_mng_no`            TEXT COMMENT '공사관리번호',
  `cnstty_div_nm`            TEXT COMMENT '공종구분명',
  `cnstwk_rgn_nm`            TEXT COMMENT '공사지역명',
  `cnstwk_prd_cntnts`        TEXT COMMENT '공사기간내용',
  `dsgn_doc_rdng_plce_nm`    TEXT COMMENT '설계도서열람장소명',
  `dsgn_doc_rdng_prd_cntnts` TEXT COMMENT '설계도서열람기간내용',

  `rcrit_rgst_no`            TEXT COMMENT '모집등록번호',
  `bid_ntce_no_list`         MEDIUMTEXT COMMENT '입찰공고번호리스트 — blob 성격',
  `usg_cntnts`               TEXT COMMENT '용도내용',
  `rmrk_cntnts`              TEXT COMMENT '비고내용',

  `ntice_dt`                 DATETIME(6) COMMENT '게시일시',
  `chg_dt`                   DATETIME(6) COMMENT '변경일시',

  `rgst_dt`                  DATETIME(6),
  `api_call_id`              CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `created_at`               DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (`order_plan_unty_no`, `order_plan_sno`),
  KEY `idx_dwt_order_plan_prdct` (`prdct_clsfc_no`),
  KEY `idx_dwt_order_plan_dminstt` (`dminstt_cd`),
  CONSTRAINT `fk_dwt_order_plan_prdct_clsfc` FOREIGN KEY (`prdct_clsfc_no`)
    REFERENCES `dm_product_classification` (`clsfc_no`),
  CONSTRAINT `fk_dwt_order_plan_dminstt` FOREIGN KEY (`dminstt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_order_plan_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;


-- ============================================================
-- 5.9 조달요청 (Procurement Request)
-- schema.sql 의 35컬럼 + migration 014_procurement_request_provenance.sql 의 29컬럼.
-- 014 는 발주계획(조달요청) 원문을 실행 단위로 보존하고 네 유형(물품/공사/용역/외자)을
-- 하나의 DWT 에 손실 없이 정규화하기 위한 것이다. 여기서는 ALTER 가 아니라
-- CREATE TABLE 안에 그대로 접어 넣는다 — MySQL 스키마는 재생이 아니라 새 출발점이다.
-- ============================================================
CREATE TABLE `dwt_procurement_request` (
  `prcrmnt_req_no`               VARCHAR(64) NOT NULL,
  `prcrmnt_req_ord`              TEXT,

  `inqry_div`                    VARCHAR(64),
  `inqry_bgn_dt`                 DATETIME(6),
  `inqry_end_dt`                 DATETIME(6),

  `dminstt_cd`                   VARCHAR(20),
  `dminstt_nm`                   TEXT,
  `prcrmnt_req_nm`               TEXT,

  `bsns_div_nm`                  VARCHAR(64),
  `prdct_clsfc_no`               VARCHAR(20),
  `prdct_clsfc_nm`               TEXT,

  -- getPrcrmntReqInfoListThng 가 추가로 돌려주는 필드
  `order_instt_cd`               TEXT COMMENT '발주기관코드',
  `order_instt_nm`               TEXT COMMENT '발주기관명',
  `bdgt_amt`                     DECIMAL(20,4) COMMENT '예산금액',
  `cntrct_cncls_stle_nm`         TEXT COMMENT '계약체결형태명',
  `lease_yn`                     CHAR(1) COMMENT '임차여부',
  `frstyear_unty_cntrct_idnt_no` TEXT COMMENT '최초년도단가계약식별번호',
  `prcrmnt_req_info_url`         TEXT COMMENT '조달요청정보URL',
  `prcrmnt_req_ofcl_nm`          TEXT COMMENT '조달요청담당자명',
  `prcrmnt_req_ofcl_emp_no`      TEXT COMMENT '조달요청담당자직원번호',

  -- 대표품목
  `rprsnt_prdctidntno`           TEXT COMMENT '대표품목식별번호',
  `rprsnt_prdct_clsfc_no_nm`     TEXT COMMENT '대표품목분류번호명',
  `rprsnt_spec_dtls_cntnts`      TEXT COMMENT '대표규격상세내용',
  `rprsnt_qty`                   DECIMAL(20,4) COMMENT '대표수량',
  `rprsnt_unit`                  TEXT COMMENT '대표단위',
  `rprsnt_uprc`                  DECIMAL(20,4) COMMENT '대표단가',
  `rprsnt_amt`                   DECIMAL(20,4) COMMENT '대표금액',
  `rprsnt_dlvr_plce`             TEXT COMMENT '대표납품장소',
  `rprsnt_dlvr_daynum`           TEXT COMMENT '대표납품일수',
  `rprsnt_dedt_date`             DATE COMMENT '대표납기일자',

  `inpt_dt`                      DATETIME(6) COMMENT '입력일시',
  `rcpt_dt`                      DATETIME(6) COMMENT '접수일시',

  `rgst_dt`                      DATETIME(6),
  `api_call_id`                  CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci,
  `created_at`                   DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),

  -- ---- 여기부터 migration 014 가 추가한 컬럼 ----------------------------
  `operation`                    VARCHAR(64) COMMENT '어느 오퍼레이션이 이 행을 만들었는지 (migration 014)',
  `raw_json`                     JSON COMMENT '원문 스냅샷 (migration 014)',
  -- UUID[] -> JSON. migration 014 의 GIN 인덱스는 옮기지 않았다(MySQL 에 GIN 없음).
  -- 조회가 필요해지면 접합 테이블 procurement_request_api_call 로 정규화하는 것이 정답이다.
  `api_call_id_list`             JSON NOT NULL DEFAULT (JSON_ARRAY()),
  `updated_at`                   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `cntrct_cncls_mthd_nm`         TEXT,
  `cntrct_dispos_nm`             TEXT,
  `tot_cnstwk_scle_amt`          DECIMAL(20,4),
  `cnstty_nm`                    TEXT,
  `contrct_amt`                  DECIMAL(20,4),
  `thtm_contrct_amt`             DECIMAL(20,4),
  `main_cnstty_dept_nm`          TEXT,
  `govsply_amt`                  DECIMAL(20,4),
  `tot_cnstwk_daynum`            INT,
  `thtm_cnstwk_daynum`           INT,
  `etc_amt`                      DECIMAL(20,4),
  `cnstrtsite_rgn_nm`            TEXT,
  `cnstwk_prearng_amt`           DECIMAL(20,4),
  `tech_rvw_reqst_date`          DATE,
  `rcpt_brnofce_nm`              TEXT,
  `thtm_bdgt_amt`                DECIMAL(20,4),
  `frstyear_prcrmnt_req_no`      TEXT,
  `cnstwk_const_gvsp_amt`        DECIMAL(20,4),
  `cnstwk_prtm_conr_amt`         DECIMAL(20,4),
  `presmpt_prce`                 DECIMAL(20,4),
  `eng_rprsnt_prdct_nm`          TEXT,
  `cptal_div_cd`                 TEXT,
  `cptal_div_nm`                 TEXT,
  `totl_prdct_idnt_no_num`       INT,
  `asign_dol_amt`                DECIMAL(20,4),

  PRIMARY KEY (`prcrmnt_req_no`),
  KEY `idx_dwt_prcrmnt_req_dminstt` (`dminstt_cd`),
  KEY `idx_dwt_prcrmnt_req_prdct` (`prdct_clsfc_no`),
  CONSTRAINT `fk_dwt_prcrmnt_req_dminstt` FOREIGN KEY (`dminstt_cd`)
    REFERENCES `dm_institution` (`instt_cd`),
  CONSTRAINT `fk_dwt_prcrmnt_req_prdct_clsfc` FOREIGN KEY (`prdct_clsfc_no`)
    REFERENCES `dm_product_classification` (`clsfc_no`),
  CONSTRAINT `fk_dwt_prcrmnt_req_api_call` FOREIGN KEY (`api_call_id`)
    REFERENCES `api_call_log` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

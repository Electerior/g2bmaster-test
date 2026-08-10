-- ============================================================
-- V8__bid_notice_institution_name.sql
-- 공고 검색 색인에 기관명을 직접 담는다.
--
-- 왜 ERD 를 벗어나 컬럼을 두 개 더 두는가 — 조인이 성립하지 않기 때문이다.
--
--   1. ERD 는 기관을 **코드로만** 담고 이름은 `dm_institution` 에서 조인해 오기로 했다.
--      그런데 그 표는 비어 있고(0행), 채울 수 있는 조달청 API 였던
--      `UsrInfoService/getDminsttInfo` 는 폐기됐다(실측: returnReasonCode 12,
--      "해당 오픈API 서비스가 없거나 폐기됨"). 즉 이름을 얻을 경로가 그 방향에는 없다.
--
--   2. 사전규격은 애초에 **기관코드가 없다.** 그 오퍼레이션은 기관을 이름으로만 준다
--      (`orderInsttNm`/`rlDminsttNm`). 색인의 24.3%(1,111건)가 여기 해당하므로,
--      `dm_institution` 을 채운다 해도 조인할 키가 없어 영원히 공백으로 남는다.
--
--   3. 반면 기관명은 **공고 응답에 매번 같이 온다.** 지금까지 그걸 버리고 있었을 뿐이다.
--
-- 정규화를 포기하는 대가(기관 개명 시 재적재)는 실질적으로 없다 — 적재기가 몇 분마다
-- 같은 공고를 다시 읽으면서 이름도 함께 갱신하기 때문이다. 반대로 조인을 고집하면
-- 화면의 발주기관 칸이 계속 코드(5795000)로 남는다.
--
-- `dm_institution` 은 그대로 둔다. 다른 화면이 쓰고 있고, 이 색인이 더 이상 의존하지 않을 뿐이다.
-- ============================================================

ALTER TABLE `bid_notice`
  -- 길이는 실측 기준으로 넉넉히 잡았다. 가장 긴 축이 '전남광주통합특별시 나주시' 급이라
  -- 300 이면 개명·통합으로 이름이 길어져도 잘리지 않는다.
  ADD COLUMN `notice_institution_name` VARCHAR(300) DEFAULT NULL
    COMMENT '공고기관명 — 원본 응답에서 그대로. 코드가 없는 사전규격도 이 칸은 채워진다'
    AFTER `notice_institution_code`,
  ADD COLUMN `demand_institution_name` VARCHAR(300) DEFAULT NULL
    COMMENT '수요기관명 — 원본 응답에서 그대로'
    AFTER `demand_institution_code`;

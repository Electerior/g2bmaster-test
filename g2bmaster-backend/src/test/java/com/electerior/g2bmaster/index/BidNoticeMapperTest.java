package com.electerior.g2bmaster.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 나라장터 응답 → 색인 행 매핑.
 *
 * <p>고정값은 2026-07-01 실제 응답에서 뽑았다({@code getBidPblancListInfoThngPPSSrch} 등).
 */
class BidNoticeMapperTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 5, 12, 0);

	/** 실제 물품 공고 한 건(필드는 매핑에 쓰이는 것만 남겼다). */
	private static Map<String, Object> announceFixture() {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("bidNtceNo", "R26BK01610168");
		item.put("bidNtceOrd", "000");
		item.put("bidNtceNm", "실험실용 공급기기 구매");
		item.put("ntceKindNm", "재공고");
		item.put("reNtceYn", "Y");
		item.put("ntceInsttCd", "B553968");
		item.put("ntceInsttNm", "한국어촌어항공단");
		item.put("dminsttCd", "B553968");
		item.put("dminsttNm", "한국어촌어항공단");
		item.put("bidNtceDt", "2026-07-01 07:25:26");
		item.put("bidClseDt", "2026-07-07 10:00:00");
		item.put("dtilPrdctClsfcNo", "4110412701");
		item.put("dtilPrdctClsfcNoNm", "실험실용공급기기");
		item.put("purchsObjPrdctList", "[1^4110412701^실험실용공급기기]");
		item.put("sucsfbidLwltRate", "88");
		item.put("asignBdgtAmt", "29920000");
		item.put("presmptPrce", "27200000");
		item.put("prdctUprc", "29920000");
		item.put("prdctQty", "1");
		item.put("prdctUnit", "식");
		item.put("VAT", "2720000");
		item.put("ntceInsttOfclNm", "강혜인");
		item.put("ntceInsttOfclTelNo", "02-6098-0736");
		item.put("bfSpecRgstNo", "R26BD00246859");
		item.put("bidNtceDtlUrl", "https://www.g2b.go.kr/link/PNPE027_01/single/?bidPbancNo=R26BK01610168");
		item.put("ntceSpecFileNm1", "입찰공고서.hwp");
		item.put("ntceSpecDocUrl1", "https://www.g2b.go.kr/downloadFile.do?fileSeq=1");
		item.put("ntceSpecFileNm2", "");
		item.put("ntceSpecDocUrl2", "");
		return item;
	}

	// ── 입찰공고 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("입찰공고 — ERD 컬럼으로 전부 옮겨진다")
	void announce() {
		BidNoticeRow row = BidNoticeMapper.fromBidAnnounce(announceFixture(), BusinessDivision.물품, NOW);

		assertThat(row).isNotNull();
		assertThat(row.id()).isEqualTo("R26BK01610168");
		assertThat(row.noticeOrder()).isEqualTo("000");
		assertThat(row.noticeName()).isEqualTo("실험실용 공급기기 구매");
		assertThat(row.businessDivision()).isEqualTo(BusinessDivision.물품);
		assertThat(row.demandInstitutionCode()).isEqualTo("B553968");
		assertThat(row.noticeInstitutionCode()).isEqualTo("B553968");
		// 기관명은 응답에 같이 오므로 색인에 그대로 담는다 — dm_institution 조인이 아니다.
		assertThat(row.demandInstitutionName()).isEqualTo("한국어촌어항공단");
		assertThat(row.noticeInstitutionName()).isEqualTo("한국어촌어항공단");
		assertThat(row.beforeSpecRgstNo()).isEqualTo("R26BD00246859");
		assertThat(row.detailProductCode()).isEqualTo("4110412701");
		assertThat(row.officerName()).isEqualTo("강혜인");
		assertThat(row.officerContact()).isEqualTo("02-6098-0736");
		assertThat(row.createdDate()).isEqualTo(LocalDateTime.of(2026, 7, 1, 7, 25, 26));
		assertThat(row.closeDate()).isEqualTo(LocalDateTime.of(2026, 7, 7, 10, 0));
		assertThat(row.sourceUrl()).startsWith("https://www.g2b.go.kr/link/");
	}

	@Test
	@DisplayName("낙찰하한율은 백분율 그대로 담는다 — 88 은 0.88 이 아니다")
	void lowestBidRateStaysPercent() {
		BidNoticeRow row = BidNoticeMapper.fromBidAnnounce(announceFixture(), BusinessDivision.물품, NOW);

		assertThat(row.lowestBidRate()).isEqualByComparingTo("88");
	}

	@Test
	@DisplayName("DECIMAL(5,3) 범위를 넘는 낙찰하한율은 버린다 — 배치 전체를 잃지 않기 위해")
	void outOfRangeRateDropped() {
		Map<String, Object> item = announceFixture();
		item.put("sucsfbidLwltRate", "12345");

		assertThat(BidNoticeMapper.fromBidAnnounce(item, BusinessDivision.물품, NOW).lowestBidRate()).isNull();
	}

	@Test
	@DisplayName("세부 가격 표는 JSON 으로 접힌다")
	void priceDetailJson() {
		BidNoticeRow row = BidNoticeMapper.fromBidAnnounce(announceFixture(), BusinessDivision.물품, NOW);

		assertThat(row.priceDetail())
				.contains("\"assignedBudget\":29920000")
				.contains("\"estimatedPrice\":27200000")
				.contains("\"unit\":\"식\"");
	}

	@Test
	@DisplayName("첨부는 URL 이 있는 슬롯만 담는다 — 열 칸 중 대부분은 비어 있다")
	void attachmentsSkipEmptySlots() {
		BidNoticeRow row = BidNoticeMapper.fromBidAnnounce(announceFixture(), BusinessDivision.물품, NOW);

		assertThat(BidNoticeMapper.attachmentUrlsOf(row.attachmentUrls()))
				.containsExactly("https://www.g2b.go.kr/downloadFile.do?fileSeq=1");
	}

	@Test
	@DisplayName("입찰공고 적재분의 지역은 비어 있다 — 별도 오퍼레이션이 채운다")
	void announceCarriesNoRegion() {
		assertThat(BidNoticeMapper.fromBidAnnounce(announceFixture(), BusinessDivision.물품, NOW).region())
				.isEmpty();
	}

	// ── 분류: 입찰 / 마감 ───────────────────────────────────────────────────

	@Test
	@DisplayName("마감일시가 지났으면 '마감', 아직이면 '입찰'")
	void categoryFollowsCloseDate() {
		Map<String, Object> open = announceFixture();
		assertThat(BidNoticeMapper.fromBidAnnounce(open, BusinessDivision.물품, NOW).category())
				.isEqualTo(NoticeCategory.입찰);

		Map<String, Object> closed = announceFixture();
		closed.put("bidClseDt", "2026-07-02 10:00:00");
		assertThat(BidNoticeMapper.fromBidAnnounce(closed, BusinessDivision.물품, NOW).category())
				.isEqualTo(NoticeCategory.마감);
	}

	/** 모르는 것을 끝났다고 단정하지 않는다 — 살아 있는 공고가 '마감 전만'에서 사라지면 안 된다. */
	@Test
	@DisplayName("마감일시를 모르면 '마감'이 아니라 '입찰'로 둔다")
	void unknownCloseDateStaysOpen() {
		Map<String, Object> item = announceFixture();
		item.put("bidClseDt", "");

		BidNoticeRow row = BidNoticeMapper.fromBidAnnounce(item, BusinessDivision.물품, NOW);
		assertThat(row.closeDate()).isNull();
		assertThat(row.category()).isEqualTo(NoticeCategory.입찰);
	}

	// ── 상태 ────────────────────────────────────────────────────────────────

	@ParameterizedTest(name = "{0} → {1}")
	@DisplayName("공고구분명이 ERD 의 상태 4값으로 접힌다")
	@CsvSource({
			"등록공고,",
			"재공고,재",
			"취소공고,취소",
			"변경공고,정정",
			"정정공고,정정",
			"다시공고,다시",
			"긴급취소공고,취소",
	})
	void stateMapping(String noticeKind, String expected) {
		NoticeState state = NoticeState.fromNoticeKind(noticeKind);

		assertThat(state == null ? null : state.name()).isEqualTo(expected);
	}

	// ── 차수 ────────────────────────────────────────────────────────────────

	/**
	 * upsert 의 차수 역행 방지가 <b>문자열 비교</b>라, 자릿수가 섞이면 10차가 9차를 못 덮는다.
	 * ('10' &lt; '9' 이다.)
	 */
	@ParameterizedTest(name = "차수 {0} → {1}")
	@DisplayName("차수는 세 자리로 정규화된다")
	@CsvSource({"000,000", "'',000", "1,001", "10,010", "999,999"})
	void noticeOrderPadded(String raw, String expected) {
		Map<String, Object> item = announceFixture();
		item.put("bidNtceOrd", raw);

		assertThat(BidNoticeMapper.fromBidAnnounce(item, BusinessDivision.물품, NOW).noticeOrder())
				.isEqualTo(expected);
	}

	@Test
	@DisplayName("정규화된 차수는 문자열 비교로도 순서가 맞는다")
	void paddedOrdersCompareCorrectly() {
		assertThat("010".compareTo("009")).isPositive();
		assertThat("100".compareTo("099")).isPositive();
	}

	@Test
	@DisplayName("공고번호가 없는 행은 색인하지 않는다")
	void noIdIsSkipped() {
		Map<String, Object> item = announceFixture();
		item.put("bidNtceNo", "");

		assertThat(BidNoticeMapper.fromBidAnnounce(item, BusinessDivision.물품, NOW)).isNull();
	}

	// ── 발주계획 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("발주계획 — 분류는 '계획', 마감일시는 없다")
	void plan() {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("prcrmntReqNo", "R26DC00231563");
		item.put("prcrmntReqNm", "(단위산업)이화공공하수처리시설 MDF A, B호기 부품 구입");
		item.put("bsnsDivNm", "물품");
		item.put("orderInsttCd", "3910161");
		item.put("orderInsttNm", "경기도 평택시 상하수도사업소");
		item.put("rprsntPrdctClsfcNoNm", "섬유여과기");
		item.put("bdgtAmt", "129742060");
		item.put("rprsntAmt", "128455800");
		item.put("rcptDt", "2026-07-01 18:59:51");
		item.put("prcrmntReqOfclNm", "김태훈");

		BidNoticeRow row = BidNoticeMapper.fromProcurementPlan(item, BusinessDivision.물품);

		assertThat(row.id()).isEqualTo("R26DC00231563");
		assertThat(row.category()).isEqualTo(NoticeCategory.계획);
		assertThat(row.closeDate()).isNull();
		// 발주기관 하나가 공고기관이자 수요기관이다.
		assertThat(row.demandInstitutionCode()).isEqualTo("3910161");
		assertThat(row.noticeInstitutionCode()).isEqualTo("3910161");
		assertThat(row.noticeInstitutionName()).isEqualTo("경기도 평택시 상하수도사업소");
		assertThat(row.productList()).contains("섬유여과기");
		assertThat(row.noticeBody()).contains("경기도 평택시 상하수도사업소");
	}

	// ── 사전규격 ────────────────────────────────────────────────────────────

	/**
	 * 사전규격의 {@code id} 가 {@code bfSpecRgstNo} 인 것이 핵심이다 — 입찰공고 행의
	 * {@code before_spec_rgst_no} 와 맞물려야 생애주기 교차 이동이 조인 하나로 된다.
	 */
	@Test
	@DisplayName("사전규격 — id 가 사전규격등록번호라 입찰공고와 맞물린다")
	void preSpecLinksToAnnounce() {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("bfSpecRgstNo", "R26BD00246859");
		item.put("refNo", "PS2026SCF26605");
		item.put("bsnsDivNm", "물품");
		item.put("prdctClsfcNoNm", "(긴급) 밸브,앵글형 1종");
		item.put("orderInsttNm", "해군군수사령부");
		item.put("rlDminsttNm", "해군군수사령부");
		item.put("asignBdgtAmt", "6900000");
		item.put("rcptDt", "2026-07-01 08:46:58");
		item.put("opninRgstClseDt", "2026-07-03 00:00:00");
		item.put("prdctDtlList", "[1^^(긴급) 밸브,앵글형 1종]");

		BidNoticeRow preSpec = BidNoticeMapper.fromPreSpec(item, BusinessDivision.물품);
		BidNoticeRow announce = BidNoticeMapper.fromBidAnnounce(announceFixture(), BusinessDivision.물품, NOW);

		assertThat(preSpec.category()).isEqualTo(NoticeCategory.사전규격);
		assertThat(preSpec.id()).isEqualTo(announce.beforeSpecRgstNo());
		// 마감일시 칸에는 '의견등록 마감'이 들어간다.
		assertThat(preSpec.closeDate()).isEqualTo(LocalDateTime.of(2026, 7, 3, 0, 0));
		// 이 오퍼레이션은 기관을 이름으로만 준다 — 코드를 지어내지 않는다.
		assertThat(preSpec.demandInstitutionCode()).isNull();
		assertThat(preSpec.noticeInstitutionCode()).isNull();
		// 코드가 없어도 이름은 채워진다. dm_institution 조인으로는 영영 못 채우던 자리다.
		assertThat(preSpec.noticeInstitutionName()).isEqualTo("해군군수사령부");
		assertThat(preSpec.demandInstitutionName()).isEqualTo("해군군수사령부");
		assertThat(preSpec.noticeBody()).contains("해군군수사령부");
	}

	// ── 지역 ────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("한 공고의 여러 지역이 콤마로 합쳐진다")
	void regionsFolded() {
		List<Map<String, Object>> items = List.of(
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "경상남도"),
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "부산광역시"),
				Map.of("bidNtceNo", "B2", "prtcptPsblRgnNm", "경기도"));

		assertThat(BidNoticeMapper.foldRegions(items))
				.containsEntry("A1", "경상남도,부산광역시")
				.containsEntry("B2", "경기도");
	}

	/**
	 * 낱말 중간에서 자르면 '경상남'처럼 없는 지역명이 만들어져 필터가 영영 안 걸린다.
	 */
	@Test
	@DisplayName("40자를 넘겨도 지역명이 중간에서 잘리지 않는다")
	void regionTruncatesOnWordBoundary() {
		List<Map<String, Object>> items = List.of(
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "경상남도 창원시"),
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "경상남도 김해시"),
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "경상남도 밀양시"),
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "경상남도 통영시"),
				Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", "경상남도 거제시"));

		String region = BidNoticeMapper.foldRegions(items).get("A1");

		assertThat(region).hasSizeLessThanOrEqualTo(40);
		// 남은 조각이 전부 온전한 지역명이어야 한다.
		assertThat(region.split(",")).allSatisfy(part -> assertThat(part).endsWith("시"));
	}

	@Test
	@DisplayName("지역이 빈 행은 접지 않는다 — 빈 값이 기존 지역을 지우면 안 된다")
	void blankRegionsIgnored() {
		List<Map<String, Object>> items = List.of(Map.of("bidNtceNo", "A1", "prtcptPsblRgnNm", ""));

		assertThat(BidNoticeMapper.foldRegions(items)).isEmpty();
	}

	// ── 날짜 ────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("초가 있든 없든, 날짜만 있어도 읽는다")
	void dateFormats() {
		assertThat(BidNoticeMapper.date("2026-07-01 07:25:26"))
				.isEqualTo(LocalDateTime.of(2026, 7, 1, 7, 25, 26));
		assertThat(BidNoticeMapper.date("2026-07-06 18:00"))
				.isEqualTo(LocalDateTime.of(2026, 7, 6, 18, 0));
		assertThat(BidNoticeMapper.date("2026-07-06")).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 0));
		assertThat(BidNoticeMapper.date("")).isNull();
		assertThat(BidNoticeMapper.date(null)).isNull();
		assertThat(BidNoticeMapper.date("알 수 없음")).isNull();
	}

	// ── 배치 중복 ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("한 배치에 같은 공고가 여러 차수면 최신 차수만 남는다")
	void dedupeKeepsLatestRevision() {
		BidNoticeRow first = row("A1", "000");
		BidNoticeRow second = row("A1", "002");
		BidNoticeRow third = row("A1", "001");

		List<BidNoticeRow> deduped = BidNoticeIngestService.dedupeKeepLatest(List.of(first, second, third));

		assertThat(deduped).hasSize(1);
		assertThat(deduped.get(0).noticeOrder()).isEqualTo("002");
	}

	private static BidNoticeRow row(String id, String order) {
		return new BidNoticeRow(id, order, "이름", NoticeCategory.입찰, null, BusinessDivision.물품,
				"", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null);
	}
}

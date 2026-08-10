package com.electerior.g2bmaster.index;

import com.electerior.g2bmaster.common.Numbers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 나라장터 응답 행 → {@link BidNoticeRow}.
 *
 * <p>출처가 셋(입찰공고 · 발주계획 · 사전규격)인데 목적지는 하나다. 필드 이름이 출처마다
 * 다르고(같은 '접수일시'가 {@code bidNtceDt}/{@code rcptDt} 로 온다) 없는 필드도 서로 달라서,
 * 그 차이를 흡수하는 것이 이 클래스의 일 전부다.
 *
 * <p><b>절대 예외를 던지지 않는다.</b> 한 건의 이상한 값 때문에 수천 건짜리 적재 배치가
 * 통째로 실패하는 것이 최악이다. 파싱 못 한 값은 {@code null} 로 두고 넘어간다.
 */
public final class BidNoticeMapper {

	private static final Logger log = LoggerFactory.getLogger(BidNoticeMapper.class);

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	/** 첨부파일 슬롯 수. 입찰공고는 {@code ntceSpecDocUrl1..10}, 사전규격은 {@code specDocFileUrl1..5}. */
	private static final int ANNOUNCE_FILE_SLOTS = 10;
	private static final int PRESPEC_FILE_SLOTS = 5;

	/** {@code region VARCHAR(40)} 상한. 넘치면 잘라야 하는데, 낱말 중간에서 자르지 않는다. */
	private static final int REGION_MAX = 40;

	/** {@code notice_name VARCHAR(500)} 상한. */
	private static final int NAME_MAX = 500;

	/** {@code *_institution_name VARCHAR(300)} 상한. */
	private static final int INSTITUTION_MAX = 300;

	/**
	 * {@code DECIMAL(5,3)} 이 담을 수 있는 최댓값 초과 기준.
	 *
	 * <p>낙찰하한율은 백분율이라 100 이상이 나올 일이 없지만, 원본에 이상값이 섞이면
	 * INSERT 가 통째로 실패한다(MySQL strict 모드). 값 하나 때문에 배치를 잃지 않도록 버린다.
	 */
	private static final BigDecimal RATE_LIMIT = new BigDecimal("100");

	private BidNoticeMapper() {}

	// ── 입찰공고 ────────────────────────────────────────────────────────────

	/**
	 * 입찰공고 목록 행 → 색인 행.
	 *
	 * @param division 오퍼레이션이 정하는 업종. 응답의 {@code bsnsDivNm} 보다 신뢰도가 높다
	 *                 (물품 오퍼레이션이 돌려준 것은 물품이다)
	 * @param now      마감 여부 판정 기준 시각. 테스트가 고정할 수 있도록 인자로 받는다
	 * @return 공고번호가 없으면 {@code null}(색인할 수 없는 행)
	 */
	public static BidNoticeRow fromBidAnnounce(Map<String, Object> item, BusinessDivision division,
			LocalDateTime now) {
		String id = str(item.get("bidNtceNo"));
		if (id.isEmpty()) {
			return null;
		}

		LocalDateTime closeDate = date(item.get("bidClseDt"));
		// 마감일시를 모르는 공고는 '입찰'로 둔다. '마감'으로 접으면 아직 살아 있는 공고가
		// '마감 전만 보기'에서 사라진다 — 모르는 것을 끝났다고 단정하지 않는다.
		NoticeCategory category = (closeDate != null && closeDate.isBefore(now))
				? NoticeCategory.마감
				: NoticeCategory.입찰;

		List<CaretList.Entry> products = CaretList.parse(str(item.get("purchsObjPrdctList")));
		BusinessDivision resolved = division != null ? division : BusinessDivision.of(str(item.get("bsnsDivNm")));

		String body = body(
				str(item.get("bidNtceNm")),
				str(item.get("ntceInsttNm")),
				str(item.get("dminsttNm")),
				str(item.get("dtilPrdctClsfcNoNm")),
				str(item.get("prdctSpecNm")),
				str(item.get("cntrctCnclsMthdNm")),
				str(item.get("bidMethdNm")),
				str(item.get("sucsfbidMthdNm")),
				str(item.get("bidprcPsblIndstrytyNm")),
				str(item.get("refNo")),
				String.join(" ", CaretList.distinctNames(products)));

		return new BidNoticeRow(
				id,
				orderOf(item.get("bidNtceOrd")),
				clip(str(item.get("bidNtceNm")), NAME_MAX),
				category,
				NoticeState.fromNoticeKind(str(item.get("ntceKindNm"))),
				resolved,
				// 지역은 별도 오퍼레이션에서 온다. 여기서 빈 값을 넣어도 upsert 가
				// 기존 값을 지우지 않는다(BidNoticeIndexRepository.buildUpsertSql 참고).
				"",
				str(item.get("dminsttCd")),
				clipToNull(str(item.get("dminsttNm")), INSTITUTION_MAX),
				str(item.get("ntceInsttCd")),
				clipToNull(str(item.get("ntceInsttNm")), INSTITUTION_MAX),
				trimToNull(str(item.get("bfSpecRgstNo"))),
				productJson(products),
				trimToNull(str(item.get("dtilPrdctClsfcNo"))),
				rate(item.get("sucsfbidLwltRate")),
				priceJson(
						item.get("asignBdgtAmt"), item.get("presmptPrce"),
						item.get("prdctUprc"), item.get("prdctQty"),
						str(item.get("prdctUnit")), item.get("VAT")),
				date(item.get("bidNtceDt")),
				closeDate,
				trimToNull(str(item.get("ntceInsttOfclNm"))),
				trimToNull(str(item.get("ntceInsttOfclTelNo"))),
				body,
				attachmentJson(item, "ntceSpecFileNm", "ntceSpecDocUrl", ANNOUNCE_FILE_SLOTS),
				firstNonBlank(str(item.get("bidNtceDtlUrl")), str(item.get("bidNtceUrl"))));
	}

	// ── 발주계획 ────────────────────────────────────────────────────────────

	/**
	 * 발주계획(조달요청) 행 → 색인 행.
	 *
	 * <p>계획 단계에는 <b>마감일시가 없다</b>(아직 공고가 아니다). {@code close_date} 를 비워
	 * 두는 것이 정확하며, 그래서 '마감 임박 순' 정렬에서 계획은 자연히 뒤로 밀린다.
	 *
	 * <p>발주기관 하나가 공고기관이자 수요기관 역할을 겸하므로 두 코드에 같은 값이 들어간다.
	 */
	public static BidNoticeRow fromProcurementPlan(Map<String, Object> item, BusinessDivision division) {
		String id = str(item.get("prcrmntReqNo"));
		if (id.isEmpty()) {
			return null;
		}
		String institutionCode = trimToNull(str(item.get("orderInsttCd")));
		String productName = str(item.get("rprsntPrdctClsfcNoNm"));

		// 계획은 대표품목 하나만 준다 — 캐럿 목록이 아니라 이름 하나라서 직접 만든다.
		List<CaretList.Entry> products = productName.isEmpty()
				? List.of()
				: List.of(new CaretList.Entry("1", "", productName));

		String body = body(
				str(item.get("prcrmntReqNm")),
				str(item.get("orderInsttNm")),
				productName,
				str(item.get("rprsntSpecDtlsCntnts")),
				str(item.get("cntrctCnclsStleNm")),
				str(item.get("rprsntDlvrPlce")));

		return new BidNoticeRow(
				id,
				"000",
				clip(str(item.get("prcrmntReqNm")), NAME_MAX),
				NoticeCategory.계획,
				null,
				division != null ? division : BusinessDivision.of(str(item.get("bsnsDivNm"))),
				"",
				institutionCode,
				clipToNull(str(item.get("orderInsttNm")), INSTITUTION_MAX),
				institutionCode,
				clipToNull(str(item.get("orderInsttNm")), INSTITUTION_MAX),
				null,
				productJson(products),
				// 계획 응답에는 세부품명'번호'가 없다 — 대표품명 '이름'만 온다.
				null,
				null,
				priceJson(item.get("bdgtAmt"), item.get("rprsntAmt"), item.get("rprsntUprc"),
						item.get("rprsntQty"), str(item.get("rprsntUnit")), null),
				date(firstNonBlankObject(item.get("rcptDt"), item.get("inptDt"))),
				null,
				trimToNull(str(item.get("prcrmntReqOfclNm"))),
				null,
				body,
				null,
				trimToNull(str(item.get("prcrmntReqInfoUrl"))));
	}

	// ── 사전규격 ────────────────────────────────────────────────────────────

	/**
	 * 사전규격 행 → 색인 행.
	 *
	 * <p>id 가 {@code bfSpecRgstNo} 인 것이 중요하다. 입찰공고 쪽도 같은 값을
	 * {@code before_spec_rgst_no} 에 담으므로, 사전규격 행의 {@code id} 와 입찰공고 행의
	 * {@code before_spec_rgst_no} 가 맞물려 <b>사전규격 → 입찰공고 교차 이동</b>이 조인 하나로 된다.
	 *
	 * <p><b>기관코드가 없다.</b> 이 오퍼레이션은 기관을 이름({@code orderInsttNm})으로만 준다.
	 * 코드 칸을 비워 두는 대신 이름을 본문에 넣어 기관명 검색에는 걸리게 한다 — 없는 코드를
	 * 지어내는 것보다 이쪽이 정직하다.
	 *
	 * <p>마감일시는 입찰 마감이 아니라 <b>의견등록 마감</b>이다. 사용자가 실제로 지켜야 하는
	 * 기한이라는 점에서 역할이 같아 같은 칸에 넣는다.
	 */
	public static BidNoticeRow fromPreSpec(Map<String, Object> item, BusinessDivision division) {
		String id = str(item.get("bfSpecRgstNo"));
		if (id.isEmpty()) {
			// 등록번호가 없으면 입찰공고와 이을 수 없고 PK 도 못 만든다.
			return null;
		}
		List<CaretList.Entry> products = CaretList.parse(str(item.get("prdctDtlList")));
		String title = firstNonBlank(str(item.get("prdctClsfcNoNm")), str(item.get("refNo")));

		String body = body(
				title,
				str(item.get("orderInsttNm")),
				str(item.get("rlDminsttNm")),
				str(item.get("refNo")),
				String.join(" ", CaretList.distinctNames(products)));

		return new BidNoticeRow(
				id,
				"000",
				clip(title, NAME_MAX),
				NoticeCategory.사전규격,
				null,
				division != null ? division : BusinessDivision.of(str(item.get("bsnsDivNm"))),
				"",
				// 사전규격은 기관코드를 주지 않는다 — 없는 코드를 지어내지 않고 이름만 담는다.
				// 이 칸이 바로 dm_institution 조인으로는 영영 못 채우던 24.3% 다(V8 주석 참고).
				null,
				clipToNull(str(item.get("rlDminsttNm")), INSTITUTION_MAX),
				null,
				clipToNull(str(item.get("orderInsttNm")), INSTITUTION_MAX),
				id,
				productJson(products),
				null,
				null,
				priceJson(item.get("asignBdgtAmt"), null, null, null, null, null),
				date(item.get("rcptDt")),
				date(item.get("opninRgstClseDt")),
				trimToNull(str(item.get("ofclNm"))),
				trimToNull(str(item.get("ofclTelNo"))),
				body,
				attachmentJson(item, null, "specDocFileUrl", PRESPEC_FILE_SLOTS),
				null);
	}

	// ── 지역 ────────────────────────────────────────────────────────────────

	/**
	 * 참가가능지역 응답행들을 공고별 지역 문자열로 접는다.
	 *
	 * <p>한 공고가 여러 지역을 갖는다(행이 {@code lmtSno} 로 나뉜다). 콤마로 이어 붙이되
	 * {@code VARCHAR(40)} 을 넘기면 <b>낱말 경계에서</b> 자른다 — 중간에서 자르면
	 * '경상남'처럼 존재하지 않는 지역명이 만들어져 필터가 영영 안 걸린다.
	 *
	 * @return 공고번호 → 지역 문자열
	 */
	public static Map<String, String> foldRegions(List<Map<String, Object>> items) {
		Map<String, Set<String>> byNotice = new java.util.LinkedHashMap<>();
		for (Map<String, Object> item : items) {
			String id = str(item.get("bidNtceNo"));
			String region = str(item.get("prtcptPsblRgnNm")).trim();
			if (id.isEmpty() || region.isEmpty()) {
				continue;
			}
			byNotice.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(region);
		}

		Map<String, String> folded = new java.util.LinkedHashMap<>();
		byNotice.forEach((id, regions) -> {
			StringBuilder joined = new StringBuilder();
			for (String region : regions) {
				int added = joined.isEmpty() ? region.length() : region.length() + 1;
				if (joined.length() + added > REGION_MAX) {
					break;
				}
				if (!joined.isEmpty()) {
					joined.append(',');
				}
				joined.append(region);
			}
			if (!joined.isEmpty()) {
				folded.put(id, joined.toString());
			}
		});
		return folded;
	}

	// ── 내부: 조립 ──────────────────────────────────────────────────────────

	/**
	 * 검색 본문 조립.
	 *
	 * <p>같은 낱말이 여러 필드에 반복되는 일이 흔하다(공고명에도 품명에도 '서버'). 그대로
	 * 이어 붙이면 FULLTEXT 의 빈도가 부풀어 관련도 순위가 흔들리므로 <b>중복 조각을 지운다</b>.
	 */
	private static String body(String... parts) {
		Set<String> seen = new LinkedHashSet<>();
		for (String part : parts) {
			if (part != null && !part.isBlank()) {
				seen.add(part.trim());
			}
		}
		return String.join(" ", seen);
	}

	private static String productJson(List<CaretList.Entry> products) {
		if (products.isEmpty()) {
			return null;
		}
		ArrayNode array = JSON.createArrayNode();
		for (CaretList.Entry entry : products) {
			ObjectNode node = array.addObject();
			node.put("seq", entry.seq());
			node.put("code", entry.code());
			node.put("name", entry.name());
		}
		return array.toString();
	}

	/**
	 * 세부 가격 표. 값이 하나도 없으면 {@code null} 을 돌려준다 — 빈 객체 {@code {}} 를 넣으면
	 * 화면이 "가격 정보 있음"으로 오해한다.
	 */
	private static String priceJson(Object budget, Object estimated, Object unitPrice, Object quantity,
			String unit, Object vat) {
		ObjectNode node = JSON.createObjectNode();
		putNumber(node, "assignedBudget", budget);
		putNumber(node, "estimatedPrice", estimated);
		putNumber(node, "unitPrice", unitPrice);
		putNumber(node, "quantity", quantity);
		putNumber(node, "vat", vat);
		if (unit != null && !unit.isBlank()) {
			node.put("unit", unit.trim());
		}
		return node.isEmpty() ? null : node.toString();
	}

	private static void putNumber(ObjectNode node, String field, Object value) {
		BigDecimal number = Numbers.toNumber(value);
		if (number != null) {
			node.put(field, number);
		}
	}

	/**
	 * 첨부 목록. 파일명 슬롯이 없는 출처(사전규격)는 {@code nameField} 를 {@code null} 로 준다.
	 * URL 이 빈 슬롯은 건너뛴다 — 열 칸 중 대여섯은 늘 비어 있다.
	 */
	private static String attachmentJson(Map<String, Object> item, String nameField, String urlField,
			int slots) {
		ArrayNode array = JSON.createArrayNode();
		for (int i = 1; i <= slots; i++) {
			String url = str(item.get(urlField + i));
			if (url.isBlank()) {
				continue;
			}
			ObjectNode node = array.addObject();
			String name = nameField == null ? "" : str(item.get(nameField + i));
			node.put("name", name.isBlank() ? "첨부" + i : name);
			node.put("url", url);
		}
		return array.isEmpty() ? null : array.toString();
	}

	// ── 내부: 값 변환 ───────────────────────────────────────────────────────

	/** 낙찰하한율. 범위를 벗어난 값은 저장하지 않는다({@link #RATE_LIMIT} 주석 참고). */
	private static BigDecimal rate(Object value) {
		BigDecimal rate = Numbers.toNumber(value);
		if (rate == null) {
			return null;
		}
		if (rate.signum() < 0 || rate.compareTo(RATE_LIMIT) >= 0) {
			log.debug("낙찰하한율이 DECIMAL(5,3) 범위를 벗어나 버립니다: {}", rate);
			return null;
		}
		return rate;
	}

	/**
	 * 차수. 비어 있으면 {@code '000'}.
	 *
	 * <p>0으로 채워 세 자리로 맞추는 것이 중요하다. upsert 의 차수 역행 방지가 <b>문자열
	 * 비교</b>라서, {@code '1'} 과 {@code '000'} 이 섞이면 {@code '1' > '000'} 은 맞지만
	 * {@code '10' < '9'} 가 되어 10차가 9차를 못 덮는다.
	 */
	private static String orderOf(Object value) {
		String order = str(value).trim();
		if (order.isEmpty()) {
			return "000";
		}
		// 숫자면 세 자리로 정규화하고, 아니면(있을 수 없지만) 원본을 살린다.
		try {
			return String.format("%03d", Integer.parseInt(order));
		}
		catch (NumberFormatException ex) {
			return order;
		}
	}

	/** 지원 형식: {@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-dd HH:mm} / {@code yyyy-MM-dd}. */
	private static final DateTimeFormatter[] DATE_FORMATS = {
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
	};

	/**
	 * 날짜 파싱. 나라장터는 같은 의미의 칸에도 초가 있기도 없기도 하다.
	 *
	 * @return 못 읽으면 {@code null}
	 */
	static LocalDateTime date(Object value) {
		String raw = str(value).trim();
		if (raw.isEmpty()) {
			return null;
		}
		for (DateTimeFormatter format : DATE_FORMATS) {
			try {
				return LocalDateTime.parse(raw, format);
			}
			catch (java.time.format.DateTimeParseException ignored) {
				// 다음 형식 시도
			}
		}
		try {
			return java.time.LocalDate.parse(raw).atStartOfDay();
		}
		catch (java.time.format.DateTimeParseException ex) {
			log.debug("날짜를 읽지 못했습니다: {}", raw);
			return null;
		}
	}

	/** {@link #clip} 과 같되 빈 값을 {@code null} 로 만든다 — 빈 문자열 기관명은 '없음'이다. */
	private static String clipToNull(String value, int max) {
		String clipped = clip(value, max);
		return clipped.isEmpty() ? null : clipped;
	}

	private static String clip(String value, int max) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private static Object firstNonBlankObject(Object... values) {
		for (Object value : values) {
			if (value != null && !String.valueOf(value).isBlank()) {
				return value;
			}
		}
		return null;
	}

	/** 첨부 목록이 비어 있지 않은지 — 테스트가 쓰는 편의 메서드. */
	static List<String> attachmentUrlsOf(String json) {
		List<String> urls = new ArrayList<>();
		if (json == null) {
			return urls;
		}
		JSON.readTree(json).forEach(node -> urls.add(node.get("url").asString()));
		return urls;
	}
}

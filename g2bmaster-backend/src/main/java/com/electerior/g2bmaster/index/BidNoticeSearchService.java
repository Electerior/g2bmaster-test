package com.electerior.g2bmaster.index;

import com.electerior.g2bmaster.common.PagedResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공고 검색 — <b>로컬 색인만</b> 조회한다.
 *
 * <p>이 클래스에 나라장터 클라이언트가 주입되지 않는 것이 설계의 핵심이다. 검색 경로에서
 * 상류 호출을 물리적으로 불가능하게 해 둬야, 나중에 "여기서 한 번만 더 부르면 되는데" 하는
 * 유혹에 구조가 무너지지 않는다. 색인이 낡았다면 그것은 적재기의 문제이지 검색의 문제가 아니다.
 *
 * <p>기존 {@code BidAnnounceService} 와의 차이: 그쪽은 요청마다 나라장터를 3~수십 번 두드리고
 * 2시간 캐시로 버틴다. 이쪽은 인덱스 질의 한 번이라 캐시가 필요 없다.
 */
@Service
public class BidNoticeSearchService {

	private static final Logger log = LoggerFactory.getLogger(BidNoticeSearchService.class);

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	/**
	 * 정렬 화이트리스트.
	 *
	 * <p>사용자 입력을 {@code ORDER BY} 에 그대로 넣으면 SQL 주입이다. 표에 있는 키만 받고
	 * 없는 키는 기본값으로 떨어뜨린다.
	 *
	 * <p>{@code close} 의 {@code IS NULL} 이 앞에 붙는 이유: 마감일시가 없는 행(계획 단계)이
	 * MySQL 기본 오름차순에서 맨 앞에 온다. '마감 임박 순'을 골랐는데 마감이 없는 것부터
	 * 나오면 정렬이 뒤집힌 것처럼 보인다 — NULL 을 항상 뒤로 보낸다.
	 */
	private static final Map<String, String> SORTS = Map.of(
			"created", "n.created_date %s",
			"close", "n.close_date IS NULL, n.close_date %s",
			"name", "n.notice_name %s",
			"updated", "n.updated_at %s",
			"amount", "CAST(JSON_UNQUOTE(JSON_EXTRACT(n.price_detail, '$.estimatedPrice')) AS DECIMAL(20,4)) %s",
			"relevance", "relevance %s");

	/** 패싯을 뽑을 컬럼. 사용자 입력이 아니라 이 상수만 저장소로 넘어간다. */
	private static final Map<String, String> FACET_COLUMNS = Map.of(
			"category", "category",
			"division", "business_division",
			"region", "region",
			"state", "state");

	/** 지역 패싯 상한. 시·도 단위라 스무 개면 전부 덮는다. */
	private static final int FACET_LIMIT = 30;

	private final BidNoticeIndexRepository repository;

	public BidNoticeSearchService(BidNoticeIndexRepository repository) {
		this.repository = repository;
	}

	// ── 검색 ────────────────────────────────────────────────────────────────

	public PagedResponse<Map<String, Object>> search(NoticeSearchRequest request) {
		BidNoticeQueryBuilder.Where where = buildWhere(request);
		String orderBy = orderBy(request, where.fullText());

		int total = repository.count(where);
		List<Map<String, Object>> rows = repository.search(where, orderBy,
				request.perPageValue(), request.offset());

		LocalDateTime now = LocalDateTime.now();
		List<Map<String, Object>> items = rows.stream().map(row -> shape(row, now)).toList();
		return new PagedResponse<>(items, total, request.pageValue(), request.perPageValue());
	}

	/**
	 * 패싯 — 같은 조건에서 분류·업종·지역·상태가 각각 몇 건인지.
	 *
	 * <p>화면의 필터 칩에 건수를 붙이려면 필요하다. "0건짜리 필터"를 눌러 보고 나서야 아는
	 * 것과, 누르기 전에 아는 것의 차이가 크다.
	 */
	public Map<String, Object> facets(NoticeSearchRequest request) {
		BidNoticeQueryBuilder.Where where = buildWhere(request);
		Map<String, Object> facets = new LinkedHashMap<>();
		FACET_COLUMNS.forEach((name, column) ->
				facets.put(name, repository.facet(where, column, FACET_LIMIT).stream()
						.filter(row -> row.get("value") != null && !String.valueOf(row.get("value")).isEmpty())
						.toList()));
		return facets;
	}

	/** 상세 한 건. 없으면 {@code null}. */
	public Map<String, Object> findOne(String id) {
		Map<String, Object> row = repository.findOne(id);
		return row == null ? null : shape(row, LocalDateTime.now());
	}

	/**
	 * 조건 → {@code WHERE}.
	 *
	 * <p>검색과 패싯이 <b>반드시 같은 조건</b>을 써야 하므로 한 곳에서만 만든다. 갈라 두면
	 * 언젠가 한쪽에만 필터가 추가되고, 화면은 "12건"이라 써 놓고 3건을 보여준다.
	 */
	private BidNoticeQueryBuilder buildBuilder(NoticeSearchRequest request) {
		return new BidNoticeQueryBuilder()
				.keywords(request.and(), request.or(), request.not())
				.category(request.categoryValue())
				.state(request.stateValue())
				.businessDivision(request.divisionValue())
				.region(request.region())
				.noticeInstitutionCode(request.insttCd())
				.demandInstitutionCode(request.dmndInsttCd())
				.institutionName(request.insttNm())
				.detailProductCode(request.detailProductCode())
				.beforeSpecRgstNo(request.beforeSpecRgstNo())
				.officerName(request.officerName())
				.createdBetween(request.createdFrom(), request.createdTo())
				.closeBetween(request.closeFromValue(), request.closeToValue())
				.activeOnly(request.activeOnlyEnabled())
				.estimatedPriceBetween(request.minAmount(), request.maxAmount());
	}

	private BidNoticeQueryBuilder.Where buildWhere(NoticeSearchRequest request) {
		return buildBuilder(request).build();
	}

	/**
	 * 정렬 절.
	 *
	 * <p>기본값이 조건에 따라 달라진다 — 검색어가 있으면 <b>관련도</b>, 없으면 <b>최신순</b>.
	 * 검색어 없이 관련도로 정렬하면 전부 0점이라 순서가 사실상 무작위가 되고, 검색어가 있는데
	 * 최신순으로 두면 정확히 맞는 공고가 3페이지 뒤로 밀린다.
	 *
	 * <p>마지막에 {@code n.id} 를 붙이는 것은 <b>페이징 안정성</b> 때문이다. 정렬 키가 같은 행이
	 * 여럿일 때(같은 날 올라온 공고가 수백 건이다) 타이브레이커가 없으면 MySQL 이 페이지마다
	 * 다른 순서를 줘서, 2페이지에 1페이지의 공고가 또 나오거나 아예 건너뛴다.
	 */
	private String orderBy(NoticeSearchRequest request, boolean fullText) {
		String key = request.sort() == null ? "" : request.sort().trim();
		if (!SORTS.containsKey(key)) {
			key = fullText ? "relevance" : "created";
		}
		String direction = "asc".equalsIgnoreCase(request.dir()) ? "ASC" : "DESC";
		// 마감 임박은 '가까운 것부터'가 자연스럽다 — 방향 지정이 없으면 오름차순으로 뒤집는다.
		if ("close".equals(key) && request.dir() == null) {
			direction = "ASC";
		}
		return SORTS.get(key).formatted(direction) + ", n.id DESC";
	}

	// ── 행 성형 ─────────────────────────────────────────────────────────────

	/**
	 * DB 행(snake_case) → 프론트가 쓰는 모양(camelCase).
	 *
	 * <p>JSON 컬럼은 <b>문자열이 아니라 값으로</b> 펴서 내려준다. 문자열로 주면 프론트가
	 * 컴포넌트마다 {@code JSON.parse} 를 부르게 되고, 그중 한 곳이 빠지면 화면에
	 * {@code [object Object]} 나 원시 JSON 이 그대로 뜬다.
	 */
	private Map<String, Object> shape(Map<String, Object> row, LocalDateTime now) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", row.get("id"));
		out.put("noticeOrder", row.get("notice_order"));
		out.put("noticeName", row.get("notice_name"));
		out.put("category", row.get("category"));
		out.put("state", row.get("state"));
		out.put("businessDivision", row.get("business_division"));
		out.put("region", row.get("region"));

		out.put("demandInstitutionCode", row.get("demand_institution_code"));
		out.put("demandInstitutionName", row.get("demand_institution_name"));
		out.put("noticeInstitutionCode", row.get("notice_institution_code"));
		out.put("noticeInstitutionName", row.get("notice_institution_name"));

		out.put("beforeSpecRgstNo", row.get("before_spec_rgst_no"));
		out.put("productList", parseJson(row.get("product_list")));
		out.put("detailProductCode", row.get("detail_product_code"));
		out.put("lowestBidRate", row.get("lowest_bid_rate"));
		out.put("priceDetail", parseJson(row.get("price_detail")));

		out.put("createdDate", row.get("created_date"));
		out.put("closeDate", row.get("close_date"));
		out.put("updatedAt", row.get("updated_at"));

		out.put("officerName", row.get("officer_name"));
		out.put("officerContact", row.get("officer_contact"));

		out.put("aiSummary", row.get("ai_summary"));
		out.put("attachmentUrls", parseJson(row.get("attachment_urls")));
		out.put("sourceUrl", row.get("source_url"));

		// 목록은 미리보기, 상세는 전문. 있는 것만 넣는다.
		out.put("bodyPreview", row.get("body_preview"));
		if (row.containsKey("notice_body")) {
			out.put("noticeBody", row.get("notice_body"));
		}
		if (row.containsKey("relevance")) {
			out.put("relevance", row.get("relevance"));
		}

		// 화면이 매번 계산하지 않도록 서버에서 붙인다. 마감이 없으면(계획) null.
		out.put("dday", dday(row.get("close_date"), now));
		out.put("estimatedPrice", numberIn(out.get("priceDetail"), "estimatedPrice"));
		return out;
	}

	/**
	 * 남은 일수. 오늘 마감이면 0, 이미 지났으면 음수.
	 *
	 * <p>시각이 아니라 <b>날짜</b>로 센다. 사용자가 'D-1' 을 보고 기대하는 것은 '내일까지'이지
	 * '24시간 남음'이 아니다.
	 */
	private static Long dday(Object closeDate, LocalDateTime now) {
		if (!(closeDate instanceof LocalDateTime close)) {
			return null;
		}
		return ChronoUnit.DAYS.between(LocalDate.from(now), LocalDate.from(close));
	}

	private static Object numberIn(Object priceDetail, String field) {
		if (priceDetail instanceof Map<?, ?> map) {
			Object value = map.get(field);
			return value instanceof Number || value instanceof BigDecimal ? value : null;
		}
		return null;
	}

	/**
	 * JSON 컬럼 문자열 → 값.
	 *
	 * <p>깨진 JSON 이 들어 있어도 검색 결과 전체를 500 으로 만들지 않는다 — 그 칸만 null 로
	 * 두고 넘어간다. 색인 데이터의 흠이 조회 가능성을 무너뜨려서는 안 된다.
	 */
	private static Object parseJson(Object raw) {
		if (raw == null) {
			return null;
		}
		String text = String.valueOf(raw);
		if (text.isBlank()) {
			return null;
		}
		try {
			return JSON.readValue(text, Object.class);
		}
		catch (JacksonException ex) {
			log.debug("색인 JSON 을 읽지 못했습니다: {}", ex.getMessage());
			return null;
		}
	}
}

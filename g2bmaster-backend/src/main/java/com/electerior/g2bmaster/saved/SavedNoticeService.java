package com.electerior.g2bmaster.saved;

import com.electerior.g2bmaster.common.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 저장 공고 서비스 — 파생값 계산과 JSON 경계를 여기서 처리한다.
 */
@Service
public class SavedNoticeService {

	/** {@code limit} 상한. 원본 {@code Math.min(Math.max(n || 100, 1), 500)}. */
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 500;
	private static final int DEFAULT_LIMIT = 100;

	/** 부가세 10%. 실추정가는 견적 합계와 직접 견주는 값이라 저장 시점에 굳힌다. */
	private static final double VAT_MULTIPLIER = 1.1;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private final SavedNoticeRepository repository;

	public SavedNoticeService(SavedNoticeRepository repository) {
		this.repository = repository;
	}

	/**
	 * 저장(신규/갱신).
	 *
	 * @return 저장한 공고번호
	 */
	public String save(JsonNode body) {
		JsonNode in = body == null ? JsonNodeFactory.instance.objectNode() : body;
		String bidNtceNo = text(in.path("bidNtceNo")).trim();
		if (bidNtceNo.isEmpty()) {
			throw ApiException.badRequest("공고번호가 필요합니다");
		}

		JsonNode priceRows = in.path("priceRows").isArray()
				? in.path("priceRows") : JsonNodeFactory.instance.arrayNode();
		Long amount = parseAmount(in.path("amount"));

		String title = emptyToNull(text(in.path("title")));
		String insttNm = emptyToNull(text(in.path("insttNm")));
		String summary = emptyToNull(text(in.path("summary")));
		String note = emptyToNull(text(in.path("note")));

		repository.upsert(new SavedNoticeRepository.SavedNoticeRow(
				bidNtceNo,
				text(in.path("bidNtceOrd")),
				title,
				insttNm,
				emptyToNull(text(in.path("bidClseDt"))),
				amount,
				// 원본: amount == null ? null : Math.round(amount * 1.1)
				amount == null ? null : Math.round(amount * VAT_MULTIPLIER),
				summary,
				priceRows.toString(),
				parseLong(in.path("priceTotal")),
				in.path("raw").isObject() || in.path("raw").isArray() ? in.path("raw").toString() : "{}",
				note,
				SavedSearchText.build(title, insttNm, summary, note, priceRows)));
		return bidNtceNo;
	}

	/** 목록 + 검색. G2B API 는 부르지 않는다 — 저장된 것만 훑는 것이 이 화면의 요점이다. */
	public Map<String, Object> search(String query, Integer limit) {
		List<String> terms = SavedNoticeRepository.parseTerms(query);
		List<Map<String, Object>> items = repository.search(terms, clampLimit(limit));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("totalCount", items.size());
		out.put("query", query == null ? "" : query.trim());
		return out;
	}

	/** 단건 전문. JSON 컬럼은 문자열이 아니라 파싱된 객체로 내려간다(원본 node-postgres 와 같다). */
	public Map<String, Object> findOne(String bidNtceNo, String bidNtceOrd) {
		Map<String, Object> row = repository.findOne(bidNtceNo, bidNtceOrd == null ? "" : bidNtceOrd);
		if (row == null) {
			throw ApiException.notFound("저장되지 않은 공고입니다");
		}
		parseJsonColumn(row, "price_rows");
		parseJsonColumn(row, "raw");
		return row;
	}

	public int delete(String bidNtceNo, String bidNtceOrd) {
		return repository.delete(bidNtceNo, bidNtceOrd == null ? "" : bidNtceOrd);
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	static int clampLimit(Integer limit) {
		int value = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
		return Math.min(Math.max(value, MIN_LIMIT), MAX_LIMIT);
	}

	/**
	 * 금액 파싱 — 원본 {@code Number(String(v ?? '').replace(/[^\d]/g, '')) || null}.
	 *
	 * <p>숫자가 아닌 문자를 전부 걷어낸다("1,234,000원" → 1234000). 결과가 0 이면 {@code null} 이다
	 * (원본의 {@code || null} — 0원 공고와 미입력을 구분하지 않는다).
	 */
	static Long parseAmount(JsonNode node) {
		String digits = text(node).replaceAll("[^\\d]", "");
		if (digits.isEmpty()) {
			return null;
		}
		try {
			long value = Long.parseLong(digits);
			return value == 0 ? null : value;
		}
		catch (NumberFormatException e) {
			return null;   // 자릿수가 long 을 넘는 값. 원본이라면 부동소수로 뭉개졌을 것이다.
		}
	}

	/** 원본 {@code Number(v) || null} — 0·NaN·빈값은 null. */
	private static Long parseLong(JsonNode node) {
		if (node.isNumber()) {
			long value = Math.round(node.doubleValue());
			return value == 0 ? null : value;
		}
		String value = text(node).trim();
		if (value.isEmpty()) {
			return null;
		}
		try {
			long parsed = Math.round(Double.parseDouble(value));
			return parsed == 0 ? null : parsed;
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static void parseJsonColumn(Map<String, Object> row, String column) {
		Object value = row.get(column);
		if (value instanceof String text && !text.isBlank()) {
			row.put(column, MAPPER.readTree(text));
		}
	}

	private static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "";
		}
		return node.isString() ? node.stringValue() : node.toString();
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}
}

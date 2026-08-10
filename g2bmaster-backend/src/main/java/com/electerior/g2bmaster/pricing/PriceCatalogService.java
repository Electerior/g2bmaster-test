package com.electerior.g2bmaster.pricing;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.common.Numbers;
import com.electerior.g2bmaster.integration.ai.AiClient;
import com.electerior.g2bmaster.integration.ai.AiUnavailableException;
import com.electerior.g2bmaster.pricing.PriceCatalogRepository.CatalogUpsert;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.IngestRequest;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.UpdateRequest;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.UpsertRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 가격 카탈로그 서비스 — 검색·수동 등록/수정·삭제·AI 리졸버 적재·이력.
 *
 * <p>적재({@link #ingest})는 AI 저장소의 다중소스 리졸버({@code /api/price/resolve})를 <b>한 번</b>
 * 부르고, 돌아온 {@code quotes[]} 를 소스별로 걸러 upsert 한다. 경계 규칙을 지킨다: {@code aiFallback}/
 * {@code aiDisabled} 응답은 성공이 아니므로 적재하지 않고, {@code priceKrw} 가 없으면 0 이 아니라
 * null 로 넣는다("가격 미확인" ≠ "0원"). ai 가 꺼져 있으면 500 이 아니라 ai-unavailable 형태로 알린다.
 */
@Service
public class PriceCatalogService {

	private static final Logger log = LoggerFactory.getLogger(PriceCatalogService.class);

	/** DB CHECK 와 같은 소스 집합. 이 밖의 소스는 적재/등록하지 않는다. */
	static final Set<String> SOURCES = Set.of("danawa", "itmaya", "enuri");

	private static final int SEARCH_DEFAULT = 50;
	private static final int SEARCH_MAX = 500;
	private static final int HISTORY_DEFAULT = 100;
	private static final int HISTORY_MAX = 1000;

	private final PriceCatalogRepository repo;
	private final AiClient aiClient;

	public PriceCatalogService(PriceCatalogRepository repo, AiClient aiClient) {
		this.repo = repo;
		this.aiClient = aiClient;
	}

	/** 소스·분류·낱말 검색. */
	public Map<String, Object> search(String source, String category, String q, int limit) {
		List<Map<String, Object>> items = repo.search(
				trimToNull(source), trimToNull(category),
				PriceCatalogRepository.parseTerms(q), clamp(limit, SEARCH_DEFAULT, SEARCH_MAX));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("count", items.size());
		return out;
	}

	/** 수동 등록/갱신(upsert) + 이력 append. */
	public Map<String, Object> upsertManual(UpsertRequest r) {
		if (r == null) {
			throw ApiException.badRequest("요청 본문이 없습니다.");
		}
		String source = r.source() == null ? "" : r.source().trim();
		if (!SOURCES.contains(source)) {
			throw ApiException.badRequest("source 는 danawa·itmaya·enuri 중 하나여야 합니다.");
		}
		if (r.name() == null || r.name().isBlank()) {
			throw ApiException.badRequest("name 은 필수입니다.");
		}
		long id = repo.upsert(new CatalogUpsert(
				source, nz(r.category()), r.name().trim(), nz(r.model()),
				trimToNull(r.spec()), r.priceKrw(), trimToNull(r.url()), trimToNull(r.note())));
		repo.insertHistory(id, r.priceKrw(), trimToNull(r.url()), trimToNull(r.note()));
		return ok(id);
	}

	/** id 기반 부분 수정(PATCH). 가격이 바뀌면 이력에 남긴다. */
	public Map<String, Object> updateManual(long id, UpdateRequest r) {
		if (r == null) {
			throw ApiException.badRequest("요청 본문이 없습니다.");
		}
		if (repo.findById(id) == null) {
			throw ApiException.notFound("가격 항목을 찾을 수 없습니다: " + id);
		}
		Map<String, Object> set = new LinkedHashMap<>();
		if (r.category() != null) {
			set.put("category", r.category().trim());
		}
		if (r.name() != null) {
			if (r.name().isBlank()) {
				throw ApiException.badRequest("name 은 비울 수 없습니다.");
			}
			set.put("name", r.name().trim());
		}
		if (r.model() != null) {
			set.put("model", r.model().trim());
		}
		if (r.spec() != null) {
			set.put("spec", trimToNull(r.spec()));
		}
		if (r.priceKrw() != null) {
			set.put("price_krw", r.priceKrw());
		}
		if (r.url() != null) {
			set.put("url", trimToNull(r.url()));
		}
		if (r.note() != null) {
			set.put("note", trimToNull(r.note()));
		}
		repo.updateById(id, set);
		if (r.priceKrw() != null) {
			repo.insertHistory(id, r.priceKrw(), trimToNull(r.url()), trimToNull(r.note()));
		}
		return ok(id);
	}

	/** 삭제(멱등). 이력은 FK CASCADE 로 함께 사라진다. */
	public Map<String, Object> delete(long id) {
		int n = repo.deleteById(id);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("deleted", n);
		return out;
	}

	/**
	 * AI 다중소스 리졸버로 시세를 긁어 카탈로그에 적재한다.
	 *
	 * <p>리졸버를 한 번 부르고 {@code quotes[]} 를 소스별로 걸러 upsert + 이력 append 한다.
	 * ai 가 꺼졌거나 폴백이면 적재하지 않고 그 사실을 돌려준다(500 아님).
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> ingest(IngestRequest req) {
		if (req == null || req.query() == null || req.query().isBlank()) {
			throw ApiException.badRequest("query 는 필수입니다.");
		}
		String query = req.query().trim();
		Set<String> want = wantedSources(req.sources());
		if (!aiClient.isEnabled()) {
			return aiUnavailable(query);
		}

		Map<String, Object> resp;
		try {
			resp = aiClient.resolvePrice(Map.of("itemName", query));
		}
		catch (AiUnavailableException e) {
			log.info("가격 적재 — AI 미가용: {}", e.getMessage());
			return aiUnavailable(query);
		}
		// 폴백/비활성 응답은 성공이 아니다 — 적재하지 않는다.
		if (Boolean.TRUE.equals(resp.get("aiDisabled")) || Boolean.TRUE.equals(resp.get("aiFallback"))) {
			return aiUnavailable(query);
		}

		List<?> quotes = resp.get("quotes") instanceof List<?> list ? list : List.of();
		int ingested = 0;
		Map<String, Integer> perSource = new LinkedHashMap<>();
		List<String> errors = new ArrayList<>();
		for (Object q : quotes) {
			if (!(q instanceof Map<?, ?> quote)) {
				continue;
			}
			Map<String, Object> qm = (Map<String, Object>) quote;
			String source = str(qm.get("source"));
			if (!SOURCES.contains(source) || !want.contains(source)) {
				continue;
			}
			String name = str(qm.get("name"));
			if (name.isBlank()) {
				continue;
			}
			Long priceKrw = Numbers.toLong(firstNonNull(
					qm.get("priceKrw"), qm.get("price_krw"), qm.get("lowestPrice"), qm.get("price")));
			String url = trimToNull(str(qm.get("url")));
			try {
				long id = repo.upsert(new CatalogUpsert(
						source, nz(req.category()), name, str(qm.get("model")),
						trimToNull(str(qm.get("spec"))), priceKrw, url, null));
				repo.insertHistory(id, priceKrw, url, null);
				ingested++;
				perSource.merge(source, 1, Integer::sum);
			}
			catch (RuntimeException e) {
				errors.add(source + ": " + e.getMessage());
			}
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("query", query);
		out.put("ingested", ingested);
		out.put("perSource", perSource);
		out.put("errors", errors);
		return out;
	}

	/** 이력 — catalogId 우선, 없으면 (source,name,model,spec) 자연키로 찾는다. */
	public Map<String, Object> history(Long catalogId, String source, String name, String model,
			String spec, int limit) {
		Long id = catalogId;
		if (id == null) {
			if (source == null || source.isBlank() || name == null || name.isBlank()) {
				throw ApiException.badRequest("catalogId 또는 (source, name) 이 필요합니다.");
			}
			id = repo.findId(source.trim(), name.trim(), model, spec);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		if (id == null) {
			out.put("items", List.of());
			out.put("count", 0);
			return out;
		}
		List<Map<String, Object>> items = repo.listHistory(id, clamp(limit, HISTORY_DEFAULT, HISTORY_MAX));
		out.put("catalogId", id);
		out.put("items", items);
		out.put("count", items.size());
		return out;
	}

	// ── 내부 ──────────────────────────────────────────────────────────────────
	private Set<String> wantedSources(List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			return SOURCES;
		}
		Set<String> want = new java.util.LinkedHashSet<>();
		for (String s : requested) {
			if (s != null && SOURCES.contains(s.trim())) {
				want.add(s.trim());
			}
		}
		return want.isEmpty() ? SOURCES : want;
	}

	private static Map<String, Object> aiUnavailable(String query) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("query", query);
		out.put("ingested", 0);
		out.put("perSource", Map.of());
		out.put("errors", List.of("ai-unavailable"));
		out.put("aiUnavailable", true);
		return out;
	}

	private static Map<String, Object> ok(long id) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("id", id);
		return out;
	}

	private static Object firstNonNull(Object... values) {
		for (Object v : values) {
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	private static int clamp(int value, int fallback, int max) {
		if (value <= 0) {
			return fallback;
		}
		return Math.min(value, max);
	}

	private static String nz(String s) {
		return s == null ? "" : s.trim();
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}
}

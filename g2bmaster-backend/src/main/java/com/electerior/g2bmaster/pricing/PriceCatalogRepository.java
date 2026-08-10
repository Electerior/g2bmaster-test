package com.electerior.g2bmaster.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 가격 카탈로그 저장소 — 소스(다나와·아이티마야·에누리)별 부품 단가와 그 변동 이력.
 *
 * <p>{@code SavedNoticeRepository}·{@code DealAnalysisRepository} 와 같은 이유로 네이티브
 * upsert 를 쓴다: 같은 상품이면 {@code ON DUPLICATE KEY UPDATE} 한 방으로 최신 단가로 덮고,
 * 그 관측을 {@code price_history} 에 남긴다. 동일성 키(natural_key)는 DB 생성 컬럼(SHA-256)이라
 * 애플리케이션이 해시를 계산하지 않는다 — 계산이 두 곳으로 갈리면 조용히 어긋난다.
 */
@Repository
public class PriceCatalogRepository {

	/** 검색 낱말 상한 — 많아질수록 {@code LIKE} 훑기가 급격히 비싸진다. */
	static final int MAX_TERMS = 8;

	private static final String SELECT_COLS = """
			SELECT id, source, category, name, model, price_krw AS priceKrw, url, note,
			       LEFT(COALESCE(spec, ''), 300) AS specPreview,
			       captured_at AS capturedAt, updated_at AS updatedAt
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public PriceCatalogRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 저장(신규/갱신) 후 카탈로그 id 를 돌려준다.
	 *
	 * <p>{@code id = LAST_INSERT_ID(id)} 덕에 갱신 경로에서도 {@link GeneratedKeyHolder} 가
	 * 기존 행의 id 를 받는다 — 이력 append 가 그 id 를 참조한다.
	 */
	public long upsert(CatalogUpsert u) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update("""
				INSERT INTO price_catalog (source, category, name, model, spec, price_krw, url, note,
				                           captured_at, updated_at)
				VALUES (:source, :category, :name, :model, :spec, :priceKrw, :url, :note, NOW(6), NOW(6)) AS new
				ON DUPLICATE KEY UPDATE
				  id = LAST_INSERT_ID(id),
				  category = new.category,
				  price_krw = new.price_krw,
				  url = new.url,
				  note = new.note,
				  updated_at = NOW(6)
				""",
				new MapSqlParameterSource()
						.addValue("source", u.source())
						.addValue("category", u.category() == null ? "" : u.category())
						.addValue("name", u.name())
						.addValue("model", u.model() == null ? "" : u.model())
						.addValue("spec", u.spec())
						.addValue("priceKrw", u.priceKrw())
						.addValue("url", u.url())
						.addValue("note", u.note()),
				keys, new String[] {"id"});
		Number key = keys.getKey();
		if (key != null) {
			return key.longValue();
		}
		// 안전망: 드물게 키홀더가 비면 자연키로 다시 찾는다.
		Long id = findId(u.source(), u.name(), u.model(), u.spec());
		if (id == null) {
			throw new IllegalStateException("upsert 후 id 를 확인하지 못했습니다.");
		}
		return id;
	}

	/** 가격 관측 한 건을 이력에 append 한다. */
	public void insertHistory(long catalogId, Long priceKrw, String url, String note) {
		jdbc.update("""
				INSERT INTO price_history (catalog_id, price_krw, url, note)
				VALUES (:cid, :priceKrw, :url, :note)
				""",
				new MapSqlParameterSource()
						.addValue("cid", catalogId)
						.addValue("priceKrw", priceKrw)
						.addValue("url", url)
						.addValue("note", note));
	}

	/** 소스·분류·낱말(name/model/spec AND-of-LIKE)로 검색. 최신 갱신순. */
	public List<Map<String, Object>> search(String source, String category, List<String> terms, int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
		StringBuilder where = new StringBuilder();
		if (source != null && !source.isBlank()) {
			where.append(where.length() == 0 ? " WHERE " : " AND ").append("source = :source");
			params.addValue("source", source);
		}
		if (category != null && !category.isBlank()) {
			where.append(where.length() == 0 ? " WHERE " : " AND ").append("category = :category");
			params.addValue("category", category);
		}
		List<String> t = terms == null ? List.of() : terms;
		for (int i = 0; i < t.size(); i++) {
			where.append(where.length() == 0 ? " WHERE " : " AND ")
					.append("(name LIKE :t").append(i)
					.append(" OR model LIKE :t").append(i)
					.append(" OR COALESCE(spec, '') LIKE :t").append(i).append(")");
			params.addValue("t" + i, "%" + t.get(i) + "%");
		}
		return jdbc.queryForList(SELECT_COLS + """
				  FROM price_catalog
				 %s
				 ORDER BY updated_at DESC
				 LIMIT :limit
				""".formatted(where), params);
	}

	/** 카탈로그 한 건(있으면). */
	public Map<String, Object> findById(long id) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				SELECT_COLS + " FROM price_catalog WHERE id = :id",
				new MapSqlParameterSource("id", id));
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 자연키(소스+상품)로 id 조회 — 생성 컬럼과 같은 SHA-256 식을 DB 에서 다시 계산한다. */
	public Long findId(String source, String name, String model, String spec) {
		List<Long> ids = jdbc.query("""
				SELECT id FROM price_catalog
				 WHERE natural_key = SHA2(CONCAT_WS(CHAR(31), :source, :name, COALESCE(:model, ''), COALESCE(:spec, '')), 256)
				""",
				new MapSqlParameterSource()
						.addValue("source", source)
						.addValue("name", name)
						.addValue("model", model)
						.addValue("spec", spec),
				(rs, n) -> rs.getLong("id"));
		return ids.isEmpty() ? null : ids.get(0);
	}

	/** 이력(최신순). */
	public List<Map<String, Object>> listHistory(long catalogId, int limit) {
		return jdbc.queryForList("""
				SELECT id, price_krw AS priceKrw, url, note, captured_at AS capturedAt
				  FROM price_history
				 WHERE catalog_id = :cid
				 ORDER BY captured_at DESC
				 LIMIT :limit
				""",
				new MapSqlParameterSource().addValue("cid", catalogId).addValue("limit", limit));
	}

	/**
	 * id 로 부분 수정. {@code setCols} 는 <b>DB 컬럼명 → 값</b> 이며 제공된 것만 갱신한다(PATCH).
	 * updated_at 은 항상 갱신한다. 갱신할 컬럼이 없으면 0.
	 */
	public int updateById(long id, Map<String, Object> setCols) {
		if (setCols == null || setCols.isEmpty()) {
			return 0;
		}
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
		StringBuilder set = new StringBuilder();
		for (Map.Entry<String, Object> e : setCols.entrySet()) {
			set.append(set.length() == 0 ? "" : ", ").append(e.getKey()).append(" = :").append(e.getKey());
			params.addValue(e.getKey(), e.getValue());
		}
		set.append(", updated_at = NOW(6)");
		return jdbc.update("UPDATE price_catalog SET " + set + " WHERE id = :id", params);
	}

	/** @return 지운 행 수. 이력은 FK CASCADE 로 함께 사라진다. */
	public int deleteById(long id) {
		return jdbc.update("DELETE FROM price_catalog WHERE id = :id",
				new MapSqlParameterSource("id", id));
	}

	/** 공백으로 나눈 낱말. 최대 {@value #MAX_TERMS} 개. */
	static List<String> parseTerms(String query) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		List<String> terms = new ArrayList<>();
		for (String term : query.trim().split("\\s+")) {
			if (!term.isEmpty()) {
				terms.add(term);
				if (terms.size() == MAX_TERMS) {
					break;
				}
			}
		}
		return terms;
	}

	/** upsert 입력. 컬럼과 1:1. {@code priceKrw} 는 null 가능(가격 미확인 ≠ 0). */
	public record CatalogUpsert(String source, String category, String name, String model,
			String spec, Long priceKrw, String url, String note) {}
}

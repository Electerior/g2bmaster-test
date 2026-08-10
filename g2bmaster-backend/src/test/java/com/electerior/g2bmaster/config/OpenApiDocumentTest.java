package com.electerior.g2bmaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAPI 문서({@code /v3/api-docs})가 실제로 만들어지는지 확인한다.
 *
 * <p>이 테스트가 있는 이유는 두 가지다.
 *
 * <p>첫째, <b>이 저장소에서 스프링 컨텍스트를 통째로 띄우는 유일한 테스트</b>다. 다른 테스트는
 * 협력자를 손으로 넣기 때문에 빈 설정이 깨져도 기동 시점에야 드러난다. springdoc 은 웹 계층
 * 전체를 훑어야 문서를 만들 수 있어서, 문서가 나온다는 것 자체가 배선이 성립한다는 증거다.
 *
 * <p>둘째, <b>자물쇠가 실제 인증과 어긋나는 것</b>을 잡는다. 문서에 열려 있다고 적힌 401 은
 * 없는 것보다 나쁘다. 그래서 앱 키가 필요한 경로와 아닌 경로를 짝지어 확인한다.
 *
 * <p>DB 는 H2 로 바꾸고 Flyway·DDL 을 끈다 — 여기서 보려는 것은 웹 계층의 문서화이지
 * 스키마가 아니고, MySQL 을 요구하면 CI 와 로컬에서 이유 없이 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:openapi-doc;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=none",
		"g2b.ai.enabled=false",
		"g2b.sync.enabled=false",
})
class OpenApiDocumentTest {

	/**
	 * 지금 이 프로세스가 제공하는 전부.
	 *
	 * <p>{@code docs/api-contract.md} 의 65개와 다른 것이 정상이다 — 그 문서는 아직 옮기지 않은
	 * 것까지 적은 계약이고, 여기는 실제로 뜬 것만 적는다. 목록을 박아 두는 이유는 엔드포인트가
	 * <b>조용히 늘거나 줄어드는 것</b>을 잡기 위해서다.
	 */
	private static final Set<String> EXPECTED = Set.of(
			// 입찰 검색
			"GET /api/bid-announce",
			"GET /api/bid-result",
			"GET /api/bid-plan",
			"GET /api/pre-spec",
			// 공고 통합 검색 — 로컬 색인(bid_notice) 단독 조회
			"GET /api/search/notices",
			"GET /api/search/notices/facets",
			"GET /api/search/notices/status",
			"GET /api/search/notices/{id}",
			"POST /api/search/notices/sync",
			// 트렌드
			"GET /api/trends/{kind}",
			"GET /api/trends/{kind}/keyword-groups",
			// 시장 정보
			"POST /api/bid-opening-results",
			"POST /api/company-history",
			"POST /api/officer-search",
			"POST /api/collusion-analysis",
			"POST /api/deal-analysis",
			"POST /api/deal-analysis/backfill",
			"POST /api/prebuilt-comparables",
			// AI 분석
			"POST /api/analysis-jobs/status",
			// 첨부·파일
			"GET /api/download-attachment",
			// 저장 공고
			"POST /api/saved-notices",
			"GET /api/saved-notices",
			"GET /api/saved-notices/{no}",
			"DELETE /api/saved-notices/{no}",
			// 가격 DB
			"GET /api/price-catalog",
			"POST /api/price-catalog",
			"PUT /api/price-catalog/{id}",
			"DELETE /api/price-catalog/{id}",
			"POST /api/price-catalog/ingest",
			"GET /api/price-catalog/history",
			// 시스템·운영
			"GET /api/system/status",
			"GET /api/system/calls",
			"GET /api/system/operations",
			"GET /api/system/tables",
			"GET /api/system/schedules",
			"POST /api/system/schedules",
			"DELETE /api/system/schedules/{id}",
			"GET /api/system/backfill",
			"POST /api/system/backfill",
			"DELETE /api/system/backfill",
			"GET /healthz");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 문서가_만들어진다() throws Exception {
		JsonNode doc = apiDocs();

		assertThat(doc.path("openapi").asString()).startsWith("3.");
		assertThat(doc.path("info").path("title").asString()).isEqualTo("g2bmaster-backend");
	}

	@Test
	void 실제로_뜬_엔드포인트가_전부_실린다() throws Exception {
		assertThat(documented(apiDocs())).containsExactlyInAnyOrderElementsOf(EXPECTED);
	}

	@Test
	void 태그가_계약_문서의_절_이름과_같다() throws Exception {
		Set<String> tags = new LinkedHashSet<>();
		for (JsonNode tag : apiDocs().path("tags")) {
			tags.add(tag.path("name").asString());
		}
		assertThat(tags).containsExactly(
				OpenApiConfig.TAG_SEARCH, OpenApiConfig.TAG_INDEX_SEARCH, OpenApiConfig.TAG_TREND,
				OpenApiConfig.TAG_MARKET, OpenApiConfig.TAG_ANALYSIS, OpenApiConfig.TAG_ATTACHMENT,
				OpenApiConfig.TAG_SAVED, OpenApiConfig.TAG_PRICE, OpenApiConfig.TAG_SYSTEM);
	}

	/**
	 * 자물쇠가 {@code @RequireAppAuth} 와 정확히 같은 경로에만 붙는가.
	 *
	 * <p>{@code /api/system/schedules} 는 GET 과 POST 가 한 경로에 있으면서 인증이 갈린다 —
	 * 판정이 메서드 단위로 도는지 확인하기에 가장 좋은 자리다.
	 */
	@Test
	void 앱_키가_필요한_경로에만_자물쇠가_붙는다() throws Exception {
		JsonNode paths = apiDocs().path("paths");

		assertThat(schemes(paths, "/api/saved-notices", "post"))
				.containsExactlyInAnyOrder(OpenApiConfig.APP_KEY_SCHEME, OpenApiConfig.APP_KEY_BEARER_SCHEME);
		assertThat(schemes(paths, "/api/analysis-jobs/status", "post"))
				.containsExactlyInAnyOrder(OpenApiConfig.APP_KEY_SCHEME, OpenApiConfig.APP_KEY_BEARER_SCHEME);
		assertThat(schemes(paths, "/api/system/schedules", "post")).isNotEmpty();
		assertThat(schemes(paths, "/api/system/backfill", "post")).isNotEmpty();
		// 색인 적재는 나라장터 쿼터를 태우는 경로라 앱 키를 요구한다.
		assertThat(schemes(paths, "/api/search/notices/sync", "post"))
				.containsExactlyInAnyOrder(OpenApiConfig.APP_KEY_SCHEME, OpenApiConfig.APP_KEY_BEARER_SCHEME);

		// 가격 DB — 쓰기(등록/수정/삭제/적재)는 잠그고, 읽기(검색·이력)는 연다.
		assertThat(schemes(paths, "/api/price-catalog", "post"))
				.containsExactlyInAnyOrder(OpenApiConfig.APP_KEY_SCHEME, OpenApiConfig.APP_KEY_BEARER_SCHEME);
		assertThat(schemes(paths, "/api/price-catalog/{id}", "put")).isNotEmpty();
		assertThat(schemes(paths, "/api/price-catalog/{id}", "delete")).isNotEmpty();
		assertThat(schemes(paths, "/api/price-catalog/ingest", "post")).isNotEmpty();
		assertThat(schemes(paths, "/api/price-catalog", "get")).isEmpty();
		assertThat(schemes(paths, "/api/price-catalog/history", "get")).isEmpty();

		assertThat(schemes(paths, "/api/bid-announce", "get")).isEmpty();
		// 조회는 로컬 DB만 보므로 비용이 없다 — 잠그지 않는다.
		assertThat(schemes(paths, "/api/search/notices", "get")).isEmpty();
		assertThat(schemes(paths, "/api/system/status", "get")).isEmpty();
		assertThat(schemes(paths, "/api/system/schedules", "get")).isEmpty();
		assertThat(schemes(paths, "/healthz", "get")).isEmpty();
	}

	@Test
	void 인증_방식_두_갈래가_문서에_있다() throws Exception {
		JsonNode schemes = apiDocs().path("components").path("securitySchemes");

		assertThat(schemes.path(OpenApiConfig.APP_KEY_SCHEME).path("name").asString()).isEqualTo("X-API-Key");
		assertThat(schemes.path(OpenApiConfig.APP_KEY_BEARER_SCHEME).path("scheme").asString()).isEqualTo("bearer");
	}

	/** Swagger UI 는 {@code /swagger-ui.html} 에서 실제 화면으로 넘겨준다. */
	@Test
	void swagger_ui_가_응답한다() throws Exception {
		MvcResult result = mockMvc.perform(get("/swagger-ui.html")).andReturn();

		assertThat(result.getResponse().getStatus()).isBetween(200, 399);
		if (result.getResponse().getStatus() >= 300) {
			assertThat(result.getResponse().getRedirectedUrl()).contains("swagger-ui");
		}
	}

	private JsonNode apiDocs() throws Exception {
		MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return MAPPER.readTree(result.getResponse().getContentAsString());
	}

	/** 문서에 실린 {@code "METHOD 경로"} 전부. springdoc 이 끼워 넣는 {@code /error} 는 뺀다. */
	private static Set<String> documented(JsonNode doc) {
		Set<String> found = new LinkedHashSet<>();
		JsonNode paths = doc.path("paths");
		for (Iterator<String> it = paths.propertyNames().iterator(); it.hasNext(); ) {
			String path = it.next();
			if (path.startsWith("/error")) {
				continue;
			}
			for (Iterator<String> m = paths.path(path).propertyNames().iterator(); m.hasNext(); ) {
				found.add(m.next().toUpperCase() + " " + path);
			}
		}
		return found;
	}

	/** 해당 오퍼레이션에 걸린 보안 스킴 이름들. 없으면 빈 집합. */
	private static Set<String> schemes(JsonNode paths, String path, String method) {
		Set<String> names = new LinkedHashSet<>();
		for (JsonNode requirement : paths.path(path).path(method).path("security")) {
			requirement.propertyNames().forEach(names::add);
		}
		return names;
	}
}

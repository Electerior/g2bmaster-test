package com.electerior.g2bmaster.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.electerior.g2bmaster.common.GlobalExceptionHandler;
import com.electerior.g2bmaster.integration.g2b.G2bErrorTranslator;
import com.electerior.g2bmaster.notice.SearchCriteria;
import com.electerior.g2bmaster.trend.TrendProfiles.TrendProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 트렌드 3종의 라우팅 — 경로 변수 하나로 프로파일을 고른다.
 *
 * <p>원본은 라우트 세 개가 각각 팩토리 핸들러를 부르는 구조였다. 합친 뒤에도 세 종류가
 * 각자 다른 프로파일을 받는지, 모르는 종류가 404 로 떨어지는지를 여기서 지킨다.
 */
class TrendControllerTest {

	private BidTrendService trendService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		trendService = mock(BidTrendService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new TrendController(trendService))
				.setControllerAdvice(new GlobalExceptionHandler(new G2bErrorTranslator()))
				.build();
	}

	private static Map<String, Object> stubResponse() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("period", Map.of("from", "2026-07-01", "to", "2026-07-31"));
		response.put("summary", Map.of("totalCount", 3));
		response.put("byDay", List.of());
		response.put("topInstitutions", List.of());
		response.put("contractMethods", List.of());
		response.put("keywords", List.of());
		response.put("closingSoon", List.of());
		response.put("highValue", List.of());
		response.put("_cached", false);
		return response;
	}

	@Test
	void 종류마다_다른_프로파일이_전달된다() throws Exception {
		when(trendService.buildTrend(any(), any())).thenReturn(stubResponse());

		for (Map.Entry<String, String> pair : Map.of(
				"product", "물품", "service", "용역", "construction", "공사").entrySet()) {
			mockMvc.perform(get("/api/trends/" + pair.getKey())).andExpect(status().isOk());
		}

		ArgumentCaptor<TrendProfile> captor = ArgumentCaptor.forClass(TrendProfile.class);
		verify(trendService, org.mockito.Mockito.times(3)).buildTrend(captor.capture(), any());
		assertThat(captor.getAllValues())
				.extracting(TrendProfile::typeName)
				.containsExactlyInAnyOrder("물품", "용역", "공사");
	}

	@Test
	void 응답_봉투는_계약대로_8개_블록과_캐시_플래그를_갖는다() throws Exception {
		when(trendService.buildTrend(any(), any())).thenReturn(stubResponse());

		mockMvc.perform(get("/api/trends/product"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.period.from").value("2026-07-01"))
				.andExpect(jsonPath("$.summary.totalCount").value(3))
				.andExpect(jsonPath("$.byDay").isArray())
				.andExpect(jsonPath("$.topInstitutions").isArray())
				.andExpect(jsonPath("$.contractMethods").isArray())
				.andExpect(jsonPath("$.keywords").isArray())
				.andExpect(jsonPath("$.closingSoon").isArray())
				.andExpect(jsonPath("$.highValue").isArray())
				.andExpect(jsonPath("$._cached").value(false));
	}

	@Test
	void 검색_조건도_함께_바인딩된다() throws Exception {
		when(trendService.buildTrend(any(), any())).thenReturn(stubResponse());

		mockMvc.perform(get("/api/trends/service")
						.param("andTerms", "AI")
						.param("fromDate", "2026-07-01")
						.param("activeOnly", "true"))
				.andExpect(status().isOk());

		ArgumentCaptor<SearchCriteria> captor = ArgumentCaptor.forClass(SearchCriteria.class);
		verify(trendService).buildTrend(any(), captor.capture());
		assertThat(captor.getValue().and()).containsExactly("AI");
		assertThat(captor.getValue().dates().from()).isEqualTo("202607010000");
		assertThat(captor.getValue().activeOnlyEnabled()).isTrue();
	}

	@Test
	void 모르는_종류는_404_다() throws Exception {
		mockMvc.perform(get("/api/trends/food"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("지원하지 않는 트렌드 종류입니다: food"));
	}

	@Test
	void 키워드_그룹_목록을_내려_준다() throws Exception {
		// 라벨을 치면 키워드로 확장된다는 것을 사용자가 알 방법이 화면 말고는 없다.
		mockMvc.perform(get("/api/trends/product/keyword-groups"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("product"))
				.andExpect(jsonPath("$.typeName").value("물품"))
				.andExpect(jsonPath("$.groups.length()").value(12))
				.andExpect(jsonPath("$.groups[0].label").value("PC/노트북"))
				.andExpect(jsonPath("$.groups[0].keywords").isArray());
	}
}

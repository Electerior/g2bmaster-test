package com.electerior.g2bmaster.notice;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.config.G2bProperties;
import com.electerior.g2bmaster.market.MarketIntelController;
import com.electerior.g2bmaster.trend.TrendController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 검색·트렌드·시장정보 DI 그래프가 실제로 조립되는지 확인한다.
 *
 * <p>단위 테스트는 협력자를 손으로 넣어 주기 때문에 <b>스프링이 이 그래프를 만들 수 있는지는
 * 검증하지 않는다</b>. 실패하면 기동 시점에야 드러나고, 그때는 배포 중이다. 특히
 * {@code G2bEndpoints} 는 생성자가 둘이라(설정용/테스트용) 어느 쪽을 쓸지 스프링이 알아야 하고,
 * {@code PreSpecSource} 는 인터페이스라 구현이 정확히 하나여야 한다.
 *
 * <p>DB·웹 계층은 스캔하지 않는다 — 여기서 보려는 것은 조회 계층의 배선뿐이고,
 * 다른 계층을 끌어들이면 무관한 이유로 깨진다.
 */
class SearchWiringSmokeTest {

	@Configuration
	@ComponentScan(basePackages = {
			"com.electerior.g2bmaster.notice",
			"com.electerior.g2bmaster.trend",
			"com.electerior.g2bmaster.market",
			"com.electerior.g2bmaster.cache",
			// deal-analysis 가 시가·딜 계산·규격서 파싱을 엮으면서 market 컨트롤러가
			// pricing·attachment 빈에 의존하게 됐다 — 좁은 스캔에도 그 둘을 넣어야 조립된다.
			"com.electerior.g2bmaster.pricing",
			"com.electerior.g2bmaster.attachment",
			// 저장 공고 일괄 딜 분석(backfill)이 SavedNoticeRepository 에 의존한다.
			"com.electerior.g2bmaster.saved",
			"com.electerior.g2bmaster.integration.d2b",
			"com.electerior.g2bmaster.integration.g2b",
	})
	static class ScanConfig {

		@Bean
		G2bProperties g2bProperties() {
			return new G2bProperties(
					new G2bProperties.OpenApi("test-key", "https://apis.data.go.kr/1230000", 20000, 3, 100),
					new G2bProperties.D2b("", "https://openapi.d2b.go.kr/openapi/service", 20000),
					new G2bProperties.Ai("http://localhost:8000", 120000, false, ""),
					new G2bProperties.Cors(List.of("http://localhost:5173")),
					new G2bProperties.Security("", ""),
					new G2bProperties.Alert("", "", "", ""),
					new G2bProperties.Sync(false),
					new G2bProperties.Index(false, 600_000, 300_000, 7));
		}

		// deal-analysis 는 AI(HTTP)와 결과 저장(DB)에 의존한다. 이 스모크는 조회 계층 '조립'만
		// 보므로 그 둘은 mock 으로 채운다 — 실제 RestClient·DataSource 를 끌어들이면 무관한
		// 이유로 깨진다. DealAnalysisRepository 는 pricing 스캔으로 잡히므로 jdbc mock 만 준다.
		@Bean
		com.electerior.g2bmaster.integration.ai.AiClient aiClient() {
			return org.mockito.Mockito.mock(com.electerior.g2bmaster.integration.ai.AiClient.class);
		}

		@Bean
		org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
			return org.mockito.Mockito.mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
		}
	}

	@Test
	void 조회_계층_빈이_모두_조립된다() {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(ScanConfig.class)) {

			assertThat(context.getBean(NoticeController.class)).isNotNull();
			assertThat(context.getBean(TrendController.class)).isNotNull();
			assertThat(context.getBean(MarketIntelController.class)).isNotNull();
			assertThat(context.getBean(SearchResultCache.class)).isNotNull();

			// 사전규격 원자료 소스는 구현이 정확히 하나여야 한다 — 둘이 되면 라우터를 만들 때다.
			assertThat(context.getBeanNamesForType(PreSpecSource.class)).hasSize(1);
			assertThat(context.getBean(PreSpecSource.class)).isInstanceOf(LivePreSpecSource.class);
		}
	}

	@Test
	void 엔드포인트_URL_이_설정된_베이스에서_만들어진다() {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(ScanConfig.class)) {

			G2bEndpoints endpoints = context.getBean(G2bEndpoints.class);

			assertThat(endpoints.bidAnnounce().get("물품"))
					.isEqualTo("https://apis.data.go.kr/1230000"
							+ "/ad/BidPublicInfoService/getBidPblancListInfoThngPPSSrch");
			assertThat(endpoints.bidResult().get("공사"))
					.isEqualTo("https://apis.data.go.kr/1230000"
							+ "/as/ScsbidInfoService/getScsbidListSttusCnstwkPPSSrch");
			assertThat(endpoints.opengResultOf("용역"))
					.isEqualTo("https://apis.data.go.kr/1230000"
							+ "/ao/OpengResultInfoService/getOpengResultListServc");
			// 알 수 없는 구분은 물품으로 떨어진다(원본과 같다).
			assertThat(endpoints.opengResultOf("외자")).isEqualTo(endpoints.opengResult().get("물품"));

			// 맵 순서는 물품 → 용역 → 공사로 고정 — 응답 순서가 동점 정렬의 사실상 기본값이다.
			assertThat(endpoints.bidAnnounce().keySet()).containsExactly("물품", "용역", "공사");
			// 누리장터에는 '기타'가 하나 더 있다.
			assertThat(endpoints.privateNotice()).containsKey("기타");
			// 발주계획은 용역이 '일반용역'이라 사용자 필터 '용역'과 이름이 어긋난다.
			assertThat(endpoints.bidPlan()).extracting(java.util.Map.Entry::getKey)
					.containsExactly("물품", "일반용역", "공사", "외자");
			assertThat(G2bEndpoints.bidTypeMatches("일반용역", "용역")).isTrue();
			assertThat(G2bEndpoints.bidTypeMatches("물품", "용역")).isFalse();
		}
	}
}

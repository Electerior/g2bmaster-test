package com.electerior.g2bmaster.integration.g2b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.electerior.g2bmaster.config.G2bProperties;
import org.junit.jupiter.api.Test;

/**
 * 응답 해석부만 떼어 검증한다(네트워크 없음).
 *
 * <p>여기서 잡는 두 가지가 이 통합에서 가장 조용히 새는 지점이다.
 * <ol>
 *   <li>오류가 다른 루트 키로 오는 것 — '데이터 없음'으로 둔갑해 적재가 성공으로 기록된다.</li>
 *   <li>항목이 1건일 때 배열이 객체로 접히는 것 — 그 1건이 통째로 사라진다.</li>
 * </ol>
 */
class G2bApiClientParseTest {

	private final G2bApiClient client = new G2bApiClient(
			new G2bProperties(
					new G2bProperties.OpenApi("test-key", "https://apis.data.go.kr/1230000", 20_000, 3, 100),
					null, null, null, null, null, null, null),
			new G2bErrorTranslator());

	private static final String URL = "https://apis.data.go.kr/1230000/ad/BidPublicInfoService/getBidPblancListInfoThng";

	@Test
	void 정상_응답을_읽는다() {
		String body = """
				{"response":{"header":{"resultCode":"00","resultMsg":"정상"},
				 "body":{"totalCount":2,"pageNo":1,"numOfRows":10,
				  "items":[{"bidNtceNo":"A"},{"bidNtceNo":"B"}]}}}""";
		G2bResponse res = client.parse(URL, body);
		assertThat(res.items()).hasSize(2);
		assertThat(res.totalCount()).isEqualTo(2);
		assertThat(res.resultCode()).isEqualTo("00");
	}

	@Test
	void 항목이_1건이면_배열이_객체로_접혀_온다() {
		String body = """
				{"response":{"header":{"resultCode":"00"},
				 "body":{"totalCount":1,"items":{"item":{"bidNtceNo":"A"}}}}}""";
		G2bResponse res = client.parse(URL, body);
		assertThat(res.items()).hasSize(1);
		assertThat(res.items().get(0)).containsEntry("bidNtceNo", "A");
	}

	@Test
	void 구형_변환형식의_item_배열도_읽는다() {
		String body = """
				{"response":{"header":{"resultCode":"00"},
				 "body":{"totalCount":2,"items":{"item":[{"bidNtceNo":"A"},{"bidNtceNo":"B"}]}}}}""";
		assertThat(client.parse(URL, body).items()).hasSize(2);
	}

	@Test
	void 결과가_없으면_빈_리스트다() {
		String body = """
				{"response":{"header":{"resultCode":"00"},"body":{"totalCount":0,"items":""}}}""";
		assertThat(client.parse(URL, body).items()).isEmpty();
	}

	@Test
	void 다른_루트키에_담긴_오류를_놓치지_않는다() {
		// 실측: 240건 중 89건이 이 봉투로 새고 있었다. response 만 보면 '데이터 없음'이 된다.
		String body = """
				{"nkoneps.com.response.ResponseError":
				 {"header":{"resultCode":"07","resultMsg":"입력범위값 초과 에러"}}}""";
		assertThatThrownBy(() -> client.parse(URL, body))
				.isInstanceOf(G2bRangeException.class)
				.hasMessageContaining("G2B [07]")
				.extracting(e -> ((G2bException) e).getResultCode()).isEqualTo("07");
	}

	@Test
	void 결과코드가_00이_아니면_오류다() {
		String body = """
				{"response":{"header":{"resultCode":"07","resultMsg":"입력범위값 초과"},"body":{}}}""";
		assertThatThrownBy(() -> client.parse(URL, body)).isInstanceOf(G2bRangeException.class);
	}

	@Test
	void 인증실패는_XML로_오는데_본문을_예외에_실어_분류한다() {
		String body = """
				<OpenAPI_ServiceResponse><cmmMsgHeader>
				<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
				</cmmMsgHeader></OpenAPI_ServiceResponse>""";
		assertThatThrownBy(() -> client.parse(URL, body))
				.isInstanceOf(G2bException.class)
				.satisfies(e -> assertThat(((G2bException) e).getResponseBody()).contains("SERVICE_KEY"));
	}

	@Test
	void 예외_메시지에_서비스키가_절대_섞이지_않는다() {
		// 메시지는 로그·알림 메일·에러 응답으로 그대로 흘러나간다.
		String body = """
				{"response":{"header":{"resultCode":"07","resultMsg":"입력범위값 초과"},"body":{}}}""";
		assertThatThrownBy(() -> client.parse(URL, body))
				.hasMessageNotContaining("test-key")
				.hasMessageNotContaining("serviceKey");
	}

	@Test
	void 빈_응답도_조용히_넘기지_않는다() {
		assertThatThrownBy(() -> client.parse(URL, "")).isInstanceOf(G2bException.class);
		assertThatThrownBy(() -> client.parse(URL, null)).isInstanceOf(G2bException.class);
	}
}

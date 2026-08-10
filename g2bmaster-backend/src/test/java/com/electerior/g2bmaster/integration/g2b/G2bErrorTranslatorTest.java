package com.electerior.g2bmaster.integration.g2b;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@code lib/g2b-errors.js} 이식 검증.
 *
 * <p>여기서 확인하는 건 "어떤 실패에 어떤 안내를 내보내는가"다. 네 문구는 각각 사용자가 할
 * 일이 다르다(키 등록 / 잠깐 대기 / 자정까지 대기 / 기간 축소). 분류가 한 칸 밀리면 사용자는
 * 아무 의미 없는 행동을 반복하게 된다.
 */
class G2bErrorTranslatorTest {

	private final G2bErrorTranslator translator = new G2bErrorTranslator();

	@Nested
	@DisplayName("인증 오류")
	class Auth {

		@Test
		void HTTP_401은_인증오류다() {
			G2bException e = new G2bException("나라장터 HTTP 401", null, 401, null);
			assertThat(translator.isAuthError(e)).isTrue();
			assertThat(translator.errorStatus(e)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		}

		@Test
		void 메시지에_서비스키가_보이면_인증오류다() {
			assertThat(translator.isAuthError(new RuntimeException("SERVICE KEY IS NOT REGISTERED ERROR"))).isTrue();
			assertThat(translator.isAuthError(new RuntimeException("등록되지 않은 serviceKey"))).isTrue();
			assertThat(translator.isAuthError(new RuntimeException("권한이 없습니다"))).isTrue();
			assertThat(translator.isAuthError(new RuntimeException("unauthorized"))).isTrue();
		}

		@Test
		void 타입만으로도_인증오류다() {
			assertThat(translator.isAuthError(new G2bAuthException("키 없음"))).isTrue();
		}

		@Test
		void 안내는_환경변수를_가리킨다() {
			assertThat(translator.errorMessage(new G2bAuthException("키 없음")))
					.isEqualTo("나라장터 API 인증에 실패했습니다. G2B_SERVICE_KEY 환경변수를 확인해 주세요.");
		}
	}

	@Nested
	@DisplayName("레이트리밋 / 쿼터")
	class RateLimit {

		@Test
		void HTTP_429는_레이트리밋이고_상태코드도_429로_나간다() {
			G2bException e = new G2bException("나라장터 HTTP 429", null, 429, "Too Many Requests");
			assertThat(translator.isRateLimitError(e)).isTrue();
			assertThat(translator.errorStatus(e)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
			assertThat(translator.errorMessage(e))
					.isEqualTo("나라장터 API 요청이 순간적으로 많아 잠시 제한되었습니다. 1~2분 뒤 다시 시도해 주세요.");
		}

		@Test
		void 본문에_일일한도_흔적이_있으면_쿼터소진으로_안내가_바뀐다() {
			// 이건 자정 초기화까지 안 풀린다. '1~2분 뒤 재시도' 안내를 내보내면 거짓말이 된다.
			G2bException e = new G2bException(
					"나라장터 HTTP 429", null, 429,
					"<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>");
			assertThat(translator.isQuotaError(e)).isTrue();
			assertThat(translator.errorStatus(e)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
			assertThat(translator.errorMessage(e)).isEqualTo(
					"나라장터 공고목록 API의 일일 호출 한도(트래픽)를 초과했습니다. 한도는 매일 자정 무렵 초기화됩니다 — 그때 다시 시도하거나, data.go.kr에서 트래픽 증량 신청 또는 새 서비스키를 발급해 주세요.");
		}

		@Test
		void 쿼터예외는_레이트리밋의_하위분류다() {
			// 상태코드는 같은 429여야 하고, 재시도 판정에도 똑같이 걸려야 한다.
			G2bQuotaExceededException e = new G2bQuotaExceededException("일일 트래픽 초과");
			assertThat(e).isInstanceOf(G2bRateLimitException.class);
			assertThat(translator.isRateLimitError(e)).isTrue();
			assertThat(translator.errorStatus(e)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		}

		@Test
		void 범위오류는_초과라는_단어가_있어도_쿼터로_새지_않는다() {
			// 쿼터 패턴에 '초과'가 들어 있어, 레이트리밋 판정을 먼저 통과한 경우에만 물어봐야 한다.
			G2bRangeException range = new G2bRangeException("G2B [07]: 입력범위값 초과", "07", null, null, null);
			assertThat(translator.isRateLimitError(range)).isFalse();
			assertThat(translator.errorMessage(range)).startsWith("나라장터 API 조회 범위가 너무 큽니다.");
		}
	}

	@Nested
	@DisplayName("범위 오류")
	class Range {

		@Test
		void 결과코드_07은_범위오류다() {
			assertThat(translator.isRangeError(new G2bException("무슨 오류", "07", null, null))).isTrue();
		}

		@Test
		void 메시지_형식으로도_잡는다() {
			assertThat(translator.isRangeError(new RuntimeException("G2B [07]: 입력범위값 초과"))).isTrue();
			assertThat(translator.isRangeError(new RuntimeException("입력범위값 초과"))).isTrue();
		}

		@Test
		void 안내는_기간_축소를_요구한다() {
			assertThat(translator.errorMessage(new G2bRangeException("G2B [07]"))).isEqualTo(
					"나라장터 API 조회 범위가 너무 큽니다. 서버가 자동 분할 조회를 시도했지만 일부 API가 거절했습니다. 기간을 더 짧게 줄여 다시 검색해 주세요.");
		}

		@Test
		void 범위오류_자체는_500으로_나간다() {
			// 원본과 동일: 상태코드 분기는 인증/레이트리밋만 갖는다.
			assertThat(translator.errorStatus(new G2bRangeException("G2B [07]")))
					.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@Nested
	@DisplayName("타임아웃")
	class Timeout {

		@Test
		void 타입으로_잡는다() {
			assertThat(translator.isTimeoutError(new G2bTimeoutException("느림", null))).isTrue();
		}

		@Test
		void 감싸인_원인까지_뒤진다() {
			// 자바에서는 진짜 원인이 몇 겹 감싸여 올라온다. 겉껍데기만 보면 타임아웃이
			// '알 수 없는 오류'로 떨어져 이분 분할이 발동하지 않는다.
			RuntimeException wrapped = new RuntimeException("I/O error on GET request",
					new java.io.IOException("upstream", new SocketTimeoutException("Read timed out")));
			assertThat(translator.isTimeoutError(wrapped)).isTrue();
		}

		@Test
		void 메시지로도_잡는다() {
			assertThat(translator.isTimeoutError(new RuntimeException("connect ETIMEDOUT"))).isTrue();
			assertThat(translator.isTimeoutError(new RuntimeException("timeout of 20000ms exceeded"))).isTrue();
		}

		@Test
		void 타임아웃은_인증도_레이트리밋도_아니다() {
			G2bTimeoutException e = new G2bTimeoutException("timed out", null);
			assertThat(translator.isAuthError(e)).isFalse();
			assertThat(translator.isRateLimitError(e)).isFalse();
			assertThat(translator.errorStatus(e)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@Nested
	@DisplayName("분류되지 않는 오류")
	class Fallback {

		@Test
		void 원래_메시지를_그대로_보여준다() {
			assertThat(translator.errorMessage(new RuntimeException("DB 연결 실패"))).isEqualTo("DB 연결 실패");
		}

		@Test
		void 메시지가_없으면_기본_문구() {
			assertThat(translator.errorMessage(new RuntimeException())).isEqualTo("알 수 없는 오류가 발생했습니다.");
			assertThat(translator.errorMessage(null)).isEqualTo("알 수 없는 오류가 발생했습니다.");
		}

		@Test
		void ApiException으로_변환하면_상태코드와_문구가_함께_실린다() {
			var api = translator.toApiException(new G2bException("무슨 오류", "07", null, null));
			assertThat(api.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(api.getCode()).isEqualTo("07");
			assertThat(api.getMessage()).startsWith("나라장터 API 조회 범위가 너무 큽니다.");
		}
	}
}

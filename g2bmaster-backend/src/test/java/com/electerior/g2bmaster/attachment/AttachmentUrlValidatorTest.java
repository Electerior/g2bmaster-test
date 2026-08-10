package com.electerior.g2bmaster.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.electerior.g2bmaster.common.ApiException;
import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * 첨부 다운로드 SSRF 가드.
 *
 * <p>이 엔드포인트는 사용자가 준 URL 을 서버가 대신 요청한다. 가드가 무너지면
 * 사내망 어디로든 요청을 유도할 수 있으므로, 허용/차단 경계를 테스트로 고정한다.
 */
class AttachmentUrlValidatorTest {

	/**
	 * DNS 를 타지 않는다 — 허용목록 판정은 네트워크와 무관해야 하고,
	 * 실제 조회를 하면 오프라인 CI 에서 통과 케이스가 통째로 깨진다.
	 * 사설 대역 차단은 {@link #rejectsHostResolvingToPrivateAddress()} 에서 따로 본다.
	 */
	private final AttachmentUrlValidator validator =
			new AttachmentUrlValidator(host -> new InetAddress[] {
					InetAddress.getByAddress(host, new byte[] {(byte) 203, 0, 113, 10})   // TEST-NET-3
			});

	@ParameterizedTest
	@ValueSource(strings = {
			"https://www.g2b.go.kr/downloadFile?id=1",
			"https://apis.data.go.kr/1230000/file.hwp",
			"http://openapi.d2b.go.kr/attach/3",
			"https://naramarket.go.kr/doc.pdf",
	})
	@DisplayName("조달 관련 공식 도메인은 통과한다")
	void allowsProcurementHosts(String url) {
		assertThat(validator.validate(url)).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://evil.com/payload",
			"https://g2b.go.kr.evil.com/payload",      // 접미사 위장
			"https://notg2b.go.kr/payload",            // 경계 없는 부분일치 방지
			"ftp://www.g2b.go.kr/file",                // http(s) 외 스킴
			"file:///etc/passwd",
			"https://127.0.0.1/admin",                 // IPv4 리터럴
			"https://169.254.169.254/latest/meta-data", // 클라우드 메타데이터
			"https://[::1]/admin",                     // IPv6 리터럴
	})
	@DisplayName("허용목록 밖·IP 리터럴·비 http 스킴은 403")
	void rejectsEverythingElse(String url) {
		assertThatThrownBy(() -> validator.validate(url))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	@DisplayName("빈 URL 은 400 — 잘못된 요청이지 차단 대상이 아니다")
	void blankUrlIsBadRequest() {
		assertThatThrownBy(() -> validator.validate(""))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> validator.validate(null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("서브도메인은 허용한다 — 나라장터는 여러 호스트로 파일을 준다")
	void allowsSubdomains() {
		assertThat(validator.validate("https://file.g2b.go.kr/a.hwp")).isNotNull();
		assertThat(validator.validate("https://www1.g2b.go.kr/a.hwp")).isNotNull();
	}

	@Test
	@DisplayName("허용 도메인이라도 사설 대역으로 해석되면 차단한다 — DNS 오염 방어")
	void rejectsHostResolvingToPrivateAddress() {
		var poisoned = new AttachmentUrlValidator(host -> new InetAddress[] {
				InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})
		});

		assertThatThrownBy(() -> poisoned.validate("https://www.g2b.go.kr/downloadFile?id=1"))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	@DisplayName("루프백으로 해석돼도 차단한다")
	void rejectsHostResolvingToLoopback() {
		var poisoned = new AttachmentUrlValidator(host -> new InetAddress[] {
				InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})
		});

		assertThatThrownBy(() -> poisoned.validate("https://www.g2b.go.kr/a.hwp"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("리다이렉트 홉 상한은 원본과 같은 5")
	void redirectLimitMatchesOriginal() {
		assertThat(AttachmentUrlValidator.MAX_REDIRECTS).isEqualTo(5);
	}
}

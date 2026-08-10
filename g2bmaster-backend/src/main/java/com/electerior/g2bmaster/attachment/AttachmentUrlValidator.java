package com.electerior.g2bmaster.attachment;

import com.electerior.g2bmaster.common.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 첨부 다운로드 대상 URL 검증 — SSRF 방어.
 *
 * <p>이 엔드포인트는 사용자가 준 URL 을 <b>서버가 대신 요청</b>한다. 막지 않으면
 * 사내망 어디로든 요청을 유도할 수 있는 구멍이 된다.
 *
 * <p>원본에는 같은 성격의 허용목록이 <b>두 벌</b> 있었고
 * ({@code FILE_ENTRY_HOST_ALLOW} server.js:176, {@code ATTACH_HOST_ALLOW} server.js:2711)
 * 정규식은 같은데 사설 IP 처리가 서로 달랐다. 하나로 합친다.
 *
 * <p>가장 중요한 규칙: <b>리다이렉트 홉마다 다시 검증한다.</b> 허용된 호스트가
 * 302 로 내부 주소를 가리키면 첫 검사만으로는 아무것도 막지 못한다.
 */
@Component
public class AttachmentUrlValidator {

	/** 조달 관련 공식 도메인만 허용한다. 서브도메인은 허용, 유사 도메인은 차단. */
	private static final Pattern ALLOWED_HOST =
			Pattern.compile("(^|\\.)(g2b\\.go\\.kr|data\\.go\\.kr|d2b\\.go\\.kr|naramarket\\.go\\.kr)$",
					Pattern.CASE_INSENSITIVE);

	/** IP 리터럴은 호스트명 검사를 통째로 우회하므로 형태 자체를 거부한다. */
	private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

	private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

	/** 최대 리다이렉트 홉 수. 원본과 동일. */
	public static final int MAX_REDIRECTS = 5;

	/**
	 * 호스트 이름 → 주소 해석. 테스트에서 갈아끼울 수 있게 분리했다 —
	 * 그러지 않으면 허용목록 테스트가 실제 DNS 에 의존해 네트워크 없이는 깨진다.
	 */
	@FunctionalInterface
	public interface HostResolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	private final HostResolver resolver;

	/** 스프링이 쓰는 생성자 — 실제 DNS 로 해석한다. */
	@Autowired
	public AttachmentUrlValidator() {
		this(InetAddress::getAllByName);
	}

	/** 테스트 전용 — 해석 결과를 고정한다. */
	public AttachmentUrlValidator(HostResolver resolver) {
		this.resolver = resolver;
	}

	/**
	 * 통과하지 못하면 403 으로 던진다.
	 *
	 * @param url 검사할 절대 URL (최초 요청이든 리다이렉트 대상이든 동일하게 적용)
	 * @return 정규화된 URI
	 */
	public URI validate(String url) {
		if (url == null || url.isBlank()) {
			throw ApiException.badRequest("url 파라미터가 필요합니다.");
		}

		URI uri;
		try {
			uri = new URI(url.trim());
		}
		catch (URISyntaxException e) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "허용되지 않은 주소입니다.");
		}

		String scheme = uri.getScheme();
		if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "허용되지 않은 주소입니다.");
		}

		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "허용되지 않은 주소입니다.");
		}

		// IPv6 는 URI.getHost() 가 대괄호를 포함해 돌려준다. 둘 다 거부.
		if (host.startsWith("[") || IPV4_LITERAL.matcher(host).matches()) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "허용되지 않은 주소입니다.");
		}

		if (!ALLOWED_HOST.matcher(host).find()) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "허용되지 않은 주소입니다.");
		}

		// 허용 도메인이 사설 대역을 가리키도록 DNS 를 오염시키는 경우까지 막는다.
		// 원본의 두 허용목록이 갈렸던 지점이 정확히 여기다.
		requirePublicAddress(host);

		return uri;
	}

	private void requirePublicAddress(String host) {
		try {
			for (InetAddress address : resolver.resolve(host)) {
				if (address.isLoopbackAddress() || address.isSiteLocalAddress()
						|| address.isLinkLocalAddress() || address.isAnyLocalAddress()
						|| address.isMulticastAddress()) {
					throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
							"허용되지 않은 주소입니다.");
				}
			}
		}
		catch (UnknownHostException e) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "허용되지 않은 주소입니다.");
		}
	}
}

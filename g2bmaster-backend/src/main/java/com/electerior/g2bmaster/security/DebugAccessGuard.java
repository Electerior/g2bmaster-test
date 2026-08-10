package com.electerior.g2bmaster.security;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.config.G2bProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 디버그 엔드포인트 접근 판정 — 기존 {@code requireDebugAccess(req, res)} 의 이식.
 *
 * <p>운영이 아니면 통과. 운영이면 {@code X-Debug-Secret} 헤더가 설정값과 같아야 한다.
 * 비밀값이 아예 없으면 <b>401 이 아니라 404</b> 를 준다 — 경로의 존재 자체를 감추기 위함이고,
 * 원본의 의도적 선택이므로 유지한다.
 *
 * <p>원본에는 호출부 버그가 하나 있었다({@code server.js:2759} 의
 * {@code requireDebugAccess(res)} — 인자를 하나만 넘겨 운영에서 항상 401). 이식하면서 고친다.
 */
@Component
public class DebugAccessGuard {

	private final G2bProperties properties;
	private final Environment environment;

	public DebugAccessGuard(G2bProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	/** 통과하지 못하면 던진다. 호출부는 첫 줄에서 이 메서드를 부르기만 하면 된다. */
	public void check(HttpServletRequest request) {
		if (!environment.matchesProfiles("prod")) {
			return;
		}
		String secret = properties.security().debugSecret();
		if (secret == null || secret.isBlank()) {
			throw ApiException.notFound("not found");
		}
		if (!secret.equals(request.getHeader("X-Debug-Secret"))) {
			throw ApiException.unauthorized("unauthorized");
		}
	}
}

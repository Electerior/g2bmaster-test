package com.electerior.g2bmaster.security;

import com.electerior.g2bmaster.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequireAppAuth} 가 붙은 핸들러를 API 키로 가린다.
 *
 * <p>받는 형식은 기존과 동일하다 — {@code Authorization: Bearer <key>} 또는
 * {@code X-API-Key: <key>}. 프론트가 두 형식을 섞어 쓰고 있어 한쪽만 지원하면 깨진다.
 */
@Component
public class AppAuthInterceptor implements HandlerInterceptor {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AuthProperties properties;

	public AppAuthInterceptor(AuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!properties.enabled()) {
			return true;   // 키 미설정 = 개발 모드. AuthProperties 의 주석 참고.
		}
		if (!(handler instanceof HandlerMethod method) || !requiresAuth(method)) {
			return true;
		}
		if (!properties.appApiKey().equals(presentedKey(request))) {
			throw ApiException.unauthorized("Unauthorized");
		}
		return true;
	}

	/**
	 * 이 핸들러가 앱 키를 요구하는가.
	 *
	 * <p>{@code static public} 인 이유는 Swagger 문서가 자물쇠를 <b>같은 판정</b>으로 달기
	 * 때문이다({@code config.OpenApiConfig}). 두 벌로 두면 인증을 새로 건 경로에서 문서만
	 * 열려 있다고 적히게 된다.
	 */
	public static boolean requiresAuth(HandlerMethod method) {
		return method.hasMethodAnnotation(RequireAppAuth.class)
				|| method.getBeanType().isAnnotationPresent(RequireAppAuth.class);
	}

	private String presentedKey(HttpServletRequest request) {
		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
			return authorization.substring(BEARER_PREFIX.length()).trim();
		}
		return request.getHeader("X-API-Key");
	}
}

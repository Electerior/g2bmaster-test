package com.electerior.g2bmaster.config;

import com.electerior.g2bmaster.security.AppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 저장소를 나누면서 프론트가 다른 오리진(기본 http://localhost:5173)에서 뜨게 됐다.
 * 모놀리스 시절에는 같은 오리진이라 CORS 자체가 필요 없었다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final G2bProperties properties;
	private final AppAuthInterceptor appAuthInterceptor;

	public WebConfig(G2bProperties properties, AppAuthInterceptor appAuthInterceptor) {
		this.properties = properties;
		this.appAuthInterceptor = appAuthInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(appAuthInterceptor).addPathPatterns("/api/**");
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		String[] origins = properties.cors().allowedOrigins().toArray(String[]::new);
		registry.addMapping("/api/**")
				.allowedOrigins(origins)
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.exposedHeaders("Content-Disposition")
				.allowCredentials(true)
				.maxAge(3600);
	}
}

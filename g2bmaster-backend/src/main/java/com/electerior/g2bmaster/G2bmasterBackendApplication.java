package com.electerior.g2bmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 나라장터(G2B) 입찰정보 백엔드.
 *
 * <p>기존 모놀리스(g2bmastersopen)의 server.js + lib/*.js 를 이식한 것으로,
 * 화면은 g2bmaster-frontend(React), 추론은 g2bmaster-AI 가 맡는다.
 * 이 애플리케이션은 AI 추론을 직접 수행하지 않고 HTTP 로 위임한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class G2bmasterBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(G2bmasterBackendApplication.class, args);
	}

}

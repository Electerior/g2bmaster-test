package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 리포지토리의 <b>순수</b> 부분(낱말 파싱)만 검증한다. SQL 은 실 MySQL 로 따로 확인한다
 * (V10 이 STORED 생성컬럼·CHECK 등 MySQL 전용 기능을 쓰고, 이 저장소엔 MySQL 테스트 하네스가 없다).
 */
class PriceCatalogRepositoryTest {

	@Test
	@DisplayName("parseTerms — 공백 분리, 최대 8개, 빈 입력은 빈 목록")
	void parseTerms() {
		assertThat(PriceCatalogRepository.parseTerms(null)).isEmpty();
		assertThat(PriceCatalogRepository.parseTerms("   ")).isEmpty();
		assertThat(PriceCatalogRepository.parseTerms("RTX 5090")).containsExactly("RTX", "5090");
		assertThat(PriceCatalogRepository.parseTerms("a b c d e f g h i j"))
				.hasSize(PriceCatalogRepository.MAX_TERMS);
	}
}

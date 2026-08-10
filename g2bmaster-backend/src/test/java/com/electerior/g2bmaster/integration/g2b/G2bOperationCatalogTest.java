package com.electerior.g2bmaster.integration.g2b;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.integration.g2b.G2bOperationCatalog.Scope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code lib/g2b-operations.js} 의 self-check 를 옮긴 것.
 *
 * <p>자료는 {@code tools/gen-g2b-operations.js} 가 뽑은 JSON이라 사람이 검토하지 않는다.
 * 그래서 "생성물이 실제로 쓸 수 있는 모양인가"를 여기서 확인한다 — 경로 없는 항목, 테이블
 * 없는 항목, 범위 포함관계가 깨지는 것이 대표적인 재생성 사고다.
 */
class G2bOperationCatalogTest {

	private G2bOperationCatalog catalog;

	@BeforeEach
	void setUp() {
		catalog = new G2bOperationCatalog();
		catalog.load();
	}

	@Test
	void 범위는_포함관계를_유지한다() {
		List<G2bOperation> curated = catalog.selectOperations(Scope.CURATED);
		List<G2bOperation> important = catalog.selectOperations(Scope.IMPORTANT);
		List<G2bOperation> all = catalog.selectOperations(Scope.ALL);

		assertThat(curated).isNotEmpty();
		assertThat(important.size()).isGreaterThanOrEqualTo(curated.size());
		assertThat(all.size()).isGreaterThanOrEqualTo(important.size());
	}

	@Test
	void 원본_실측_개수와_일치한다() {
		// 재생성으로 이 수가 흔들리면 적재량·호출량 예측이 통째로 어긋난다.
		assertThat(catalog.selectOperations(Scope.CURATED)).hasSize(68);
		assertThat(catalog.selectOperations(Scope.IMPORTANT)).hasSize(72);
		assertThat(catalog.selectOperations(Scope.ALL)).hasSize(189);
	}

	@Test
	void 모든_선택_항목은_경로와_테이블을_가진다() {
		assertThat(catalog.selectOperations(Scope.ALL)).allSatisfy(op -> {
			assertThat(op.path()).isNotBlank();
			assertThat(op.table()).isNotBlank();
			assertThat(op.op()).isNotBlank();
		});
	}

	@Test
	void important에_복수예비가격이_들어간다() {
		// 낙찰가 예측의 실제 메커니즘이라 큐레이티드에 없더라도 반드시 포함돼야 한다.
		assertThat(catalog.selectOperations(Scope.IMPORTANT))
				.anyMatch(op -> op.op().endsWith("PreparPcDetail"));
		assertThat(catalog.selectOperations(Scope.CURATED))
				.noneMatch(op -> op.op().endsWith("PreparPcDetail"));
	}

	@Test
	void 사전규격_전량적재는_조회구분_1과_3만_쓴다() {
		List<G2bOperation> preSpecBulk = catalog.selectOperations(Scope.CURATED).stream()
				.filter(op -> op.op().matches("getPublicPrcureThngInfo(?:Thng|Servc)(?:PPSSrch)?"))
				.toList();
		assertThat(preSpecBulk).hasSize(4);
		// 나머지 조회구분은 날짜 조건이 무효라 기간 조회에 쓰면 응답이 비어 온다.
		assertThat(preSpecBulk).allSatisfy(op -> assertThat(op.inqryDivs()).containsExactly("1", "3"));
	}

	@Test
	void 테이블_결정_우선순위가_지켜진다() {
		// 오퍼레이션별 명시 매핑 > 서비스 전용 테이블 > 제네릭 JSONB.
		List<G2bOperation> all = catalog.selectOperations(Scope.ALL);
		assertThat(all)
				.filteredOn(op -> op.op().equals("getBidPblancListInfoThng"))
				.singleElement()
				.satisfies(op -> assertThat(op.table()).isEqualTo("dwt_bid_notice"));
		// 전용 테이블이 없는 서비스는 원본 JSONB로 떨어진다.
		assertThat(all).anyMatch(op -> op.table().equals(catalog.genericTable()));
	}

	@Test
	void 서비스_경로맵이_비어있지_않다() {
		assertThat(catalog.servicePath()).isNotEmpty();
		assertThat(catalog.pathOf("BidPublicInfoService")).isEqualTo("/ad/BidPublicInfoService");
		assertThat(catalog.servicePath().values()).allSatisfy(path -> assertThat(path).startsWith("/"));
	}

	@Test
	void 알_수_없는_범위값은_important로_떨어진다() {
		assertThat(G2bOperationCatalog.parseScope(null)).isEqualTo(Scope.IMPORTANT);
		assertThat(G2bOperationCatalog.parseScope("nonsense")).isEqualTo(Scope.IMPORTANT);
		assertThat(G2bOperationCatalog.parseScope("  ALL ")).isEqualTo(Scope.ALL);
		assertThat(G2bOperationCatalog.parseScope("curated")).isEqualTo(Scope.CURATED);
	}

	@Test
	void 오퍼레이션_URL은_baseUrl과_경로를_이어붙인다() {
		G2bOperation op = catalog.selectOperations(Scope.CURATED).get(0);
		assertThat(op.url("https://apis.data.go.kr/1230000"))
				.isEqualTo("https://apis.data.go.kr/1230000" + op.path() + "/" + op.op());
	}
}

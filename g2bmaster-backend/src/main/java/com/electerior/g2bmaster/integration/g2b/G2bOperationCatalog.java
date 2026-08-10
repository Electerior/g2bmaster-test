package com.electerior.g2bmaster.integration.g2b;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * G2B 오퍼레이션·테이블 매핑 카탈로그 ({@code lib/g2b-operations.js} 이식).
 *
 * <p>자료(189개 오퍼레이션 매핑)는 Java 소스로 옮겨 적지 않는다. 원본이 BaseG2B
 * {@code g2b_loader.py} 에서 자동 생성되는 산출물이라, 손번역해 두면 원본이 갱신될 때마다
 * 사람이 다시 대조해야 하고 그 대조는 반드시 틀린다. 대신 {@code tools/gen-g2b-operations.js}
 * 가 {@code g2b-operations.json} 을 뽑고, 여기서는 <em>선택 규칙</em>만 다시 구현한다.
 *
 * <p>적재 범위(2026-08-03 7일 표본 실측 근거):
 * <ul>
 *   <li>{@code curated}   68개 오퍼레이션 · 162콜/7일 · 13.1만행/7일</li>
 *   <li>{@code important} 위 + 복수예비가격 4종 · 약 224콜/7일 — 낙찰가 예측의 실제 메커니즘</li>
 *   <li>{@code all}       189개 · 671콜/7일 · 57.5만행/7일 (상위 10개가 호출의 80%, 대부분 소비처 없음)</li>
 * </ul>
 *
 * <p>주의: {@code getOpengResultListInfoOpengCompt}(개찰경쟁/참가자)는 날짜범위 조회가 불가하다.
 * 일괄 적재 대상이 아니며 공고 단위로 수집한다.
 */
@Component
public class G2bOperationCatalog {

	/** 적재 범위. 문자열을 그대로 받는 호출부가 많아 {@link #parseScope(String)} 로 관대하게 해석한다. */
	public enum Scope {
		CURATED, IMPORTANT, ALL
	}

	private static final String RESOURCE = "g2b-operations.json";

	/**
	 * 이 리소스 전용 매퍼.
	 *
	 * <p>스프링이 관리하는 매퍼를 주입받지 않는 것이 의도다. 그 매퍼는 HTTP 응답 직렬화 정책
	 * (null 제외 등)을 따라가는데, 정적 카탈로그를 읽는 일이 그 정책 변경에 흔들릴 이유가 없다.
	 * {@code _generatedBy} 같은 생성기 메타 필드가 섞여 있으므로 미지 필드는 무시한다.
	 */
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	private CatalogFile catalog = CatalogFile.empty();
	private Set<String> importantExtra = Set.of();

	@PostConstruct
	void load() {
		try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
			this.catalog = MAPPER.readValue(in, CatalogFile.class);
		}
		catch (IOException | RuntimeException ex) {
			// 이 리소스가 없거나 깨지면 적재가 통째로 불가능하다. 기동 시점에 터뜨려야
			// 운영에서 "왜 아무것도 안 들어오지"를 새벽에 디버깅하지 않는다.
			throw new IllegalStateException(
					RESOURCE + " 을 읽을 수 없습니다. tools/gen-g2b-operations.js 로 재생성하세요.", ex);
		}
		this.importantExtra = new LinkedHashSet<>(catalog.importantExtra());
	}

	/**
	 * 범위에 해당하는 오퍼레이션 목록.
	 *
	 * <p>경로({@code servicePath})가 없는 서비스는 호출 URL을 만들 수 없으므로 걸러낸다 —
	 * 원본의 {@code .filter(p => p.path)} 와 같다.
	 */
	public List<G2bOperation> selectOperations(Scope scope) {
		List<G2bOperation> picked = new ArrayList<>();
		catalog.curatedOperations().forEach((service, entries) ->
				entries.forEach(entry -> push(picked, service, entry)));

		if (scope != Scope.CURATED) {
			catalog.specOperations().forEach((service, entries) -> entries.forEach(entry -> {
				if (scope == Scope.ALL || importantExtra.contains(entry.op())) {
					push(picked, service, entry);
				}
			}));
		}
		return picked.stream().filter(o -> o.path() != null).toList();
	}

	/** 문자열 범위. 원본 기본값과 같이 알 수 없는 값은 {@code important} 로 떨어진다. */
	public List<G2bOperation> selectOperations(String scope) {
		return selectOperations(parseScope(scope));
	}

	public static Scope parseScope(String scope) {
		if (scope == null) {
			return Scope.IMPORTANT;
		}
		return switch (scope.trim().toLowerCase(Locale.ROOT)) {
			case "curated" -> Scope.CURATED;
			case "all" -> Scope.ALL;
			default -> Scope.IMPORTANT;
		};
	}

	/** 서비스명 → 경로. */
	public Map<String, String> servicePath() {
		return catalog.servicePath();
	}

	public String pathOf(String service) {
		return catalog.servicePath().get(service);
	}

	/** 전용 테이블이 없는 응답을 담는 JSONB 테이블 이름. */
	public String genericTable() {
		return catalog.genericTable();
	}

	public Set<String> importantExtra() {
		return importantExtra;
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private void push(List<G2bOperation> out, String service, Entry entry) {
		// 우선순위: 오퍼레이션별 명시 매핑 > 서비스 전용 테이블 > 제네릭 JSONB.
		// 큐레이티드가 항상 스펙 자동생성을 이긴다는 원본 규칙이 이 순서에 들어 있다.
		String table = catalog.operationTable().get(entry.op());
		if (table == null) {
			table = catalog.serviceTable().get(service);
		}
		if (table == null) {
			table = catalog.genericTable();
		}
		out.add(new G2bOperation(
				service,
				catalog.servicePath().get(service),
				entry.op(),
				table,
				catalog.pkColumns().getOrDefault(table, List.of()),
				entry.inqryDivs(),
				entry.windowed()));
	}

	/** JSON 안의 오퍼레이션 항목 원형. */
	record Entry(String op, List<String> inqryDivs, boolean windowed) {}

	/** {@code g2b-operations.json} 전체 구조. 필드명은 생성 스크립트가 쓰는 이름과 같아야 한다. */
	record CatalogFile(
			String genericTable,
			Map<String, List<Entry>> curatedOperations,
			Map<String, List<Entry>> specOperations,
			Map<String, String> servicePath,
			Map<String, String> operationTable,
			Map<String, List<String>> pkColumns,
			Map<String, String> serviceTable,
			List<String> importantExtra) {

		CatalogFile {
			genericTable = genericTable == null ? "raw_generic_list" : genericTable;
			curatedOperations = curatedOperations == null ? Map.of() : curatedOperations;
			specOperations = specOperations == null ? Map.of() : specOperations;
			servicePath = servicePath == null ? Map.of() : servicePath;
			operationTable = operationTable == null ? Map.of() : operationTable;
			pkColumns = pkColumns == null ? Map.of() : pkColumns;
			serviceTable = serviceTable == null ? Map.of() : serviceTable;
			importantExtra = importantExtra == null ? List.of() : importantExtra;
		}

		static CatalogFile empty() {
			return new CatalogFile(null, null, null, null, null, null, null, null);
		}
	}
}

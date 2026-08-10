package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.common.G2bDates;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.common.MapLimit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 나라장터 실시간 API 에서 사전규격을 읽는다 ({@code HrcspSsstndrdInfoService}).
 *
 * <p>{@link PreSpecSource} 의 유일한 구현이며, 현재 운영 경로다.
 *
 * <p>부분 실패 정책이 다른 탭과 <b>미묘하게 다르다</b>: 세 구분(물품·일반용역·공사) 중
 * 일부만 실패하면 나머지를 살리지만, <em>전부</em> 실패하면 첫 오류를 그대로 올린다.
 * 빈 결과로 조용히 성공시키면 "사전규격이 원래 없는 기간"과 "API가 죽은 기간"이 화면에서
 * 똑같이 보이기 때문이다.
 */
@Component
public class LivePreSpecSource implements PreSpecSource {

	private final G2bEndpoints endpoints;
	private final NoticeFetchSupport support;

	public LivePreSpecSource(G2bEndpoints endpoints, NoticeFetchSupport support) {
		this.endpoints = endpoints;
		this.support = support;
	}

	@Override
	public List<Map<String, Object>> load(DateWindow dates, String bidType) {
		List<DateWindow> windows = G2bDates.splitG2bDateRange(dates.from(), dates.to());
		List<Map.Entry<String, String>> selected = endpoints.preSpec().stream()
				.filter(entry -> G2bEndpoints.bidTypeMatches(entry.getKey(), bidType))
				.toList();
		if (selected.isEmpty()) {
			return List.of();
		}

		List<Outcome> results = MapLimit.map(selected, 3, entry -> {
			try {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (DateWindow window : windows) {
					rows.addAll(support.fetchPaged(entry.getValue(), pagedParams(window)));
				}
				List<Map<String, Object>> typed = new ArrayList<>(rows.size());
				for (Map<String, Object> row : rows) {
					Map<String, Object> copy = new LinkedHashMap<>(row);
					if (str(copy.get("bsnsDivNm")).isEmpty()) {
						copy.put("bsnsDivNm", entry.getKey());
					}
					typed.add(copy);
				}
				return new Outcome(typed, null);
			}
			catch (RuntimeException ex) {
				return new Outcome(List.of(), ex);
			}
		});

		List<Map<String, Object>> items = new ArrayList<>();
		RuntimeException firstError = null;
		int failed = 0;
		for (Outcome outcome : results) {
			items.addAll(outcome.items());
			if (outcome.error() != null) {
				failed++;
				if (firstError == null) {
					firstError = outcome.error();
				}
			}
		}
		if (firstError != null && failed == results.size()) {
			throw firstError;
		}
		return items;
	}

	private static Map<String, Object> pagedParams(DateWindow window) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryDiv", 1);
		params.put("inqryBgnDt", window.from());
		params.put("inqryEndDt", window.to());
		return params;
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private record Outcome(List<Map<String, Object>> items, RuntimeException error) {}
}

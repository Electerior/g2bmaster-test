package com.electerior.g2bmaster.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * G2B 날짜/기간 파싱·분할 순수 헬퍼 ({@code lib/dates.js} 이식).
 *
 * <p>나라장터는 {@code inqryBgnDt}/{@code inqryEndDt} 를 {@code YYYYMMDDHHmm} 로만 받고,
 * 오퍼레이션마다 허용 기간 상한이 다르다(넘기면 resultCode {@code 07} 입력범위값 초과).
 * 그래서 "요청 기간을 API가 삼킬 수 있는 창으로 자르는" 규칙이 도메인 로직이 됐고,
 * 이 클래스가 그 규칙의 유일한 출처다.
 *
 * <p>모든 메서드는 부수효과가 없다. 시각대는 시스템 기본 존을 쓴다(운영은 Asia/Seoul).
 */
public final class G2bDates {

	/** 조회 창 하나. G2B 파라미터 형식({@code YYYYMMDDHHmm}) 문자열 그대로 담는다. */
	public record DateWindow(String from, String to) {}

	/**
	 * 기본 분할 폭(일).
	 *
	 * <p>30이 아니라 31인 것이 의도다. G2B는 "31일 이내"를 허용하는데 30으로 자르면
	 * 한 달치 조회가 항상 두 창으로 쪼개져 호출 수가 배로 늘고, 반대로 32 이상이면
	 * {@code [07]} 입력범위값 초과가 난다. 이 경계는 실측으로 잡힌 값이므로 건드리지 말 것.
	 */
	public static final int DEFAULT_MAX_DAYS = 31;

	private static final DateTimeFormatter G2B_DT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private G2bDates() {}

	/**
	 * {@code YYYY-MM-DD} → {@code YYYYMMDD0000} / {@code YYYYMMDD2359}.
	 *
	 * <p>끝 시각을 23:59로 채우지 않으면 종료일 하루가 통째로 빠진다(G2B는 시각까지 비교한다).
	 */
	public static String toG2bDt(String dateStr, boolean isEnd) {
		if (dateStr == null || dateStr.isEmpty()) {
			return null;
		}
		String d = dateStr.replace("-", "");
		return isEnd ? d + "2359" : d + "0000";
	}

	public static String toG2bDt(String dateStr) {
		return toG2bDt(dateStr, false);
	}

	/**
	 * 30일을 넘는 범위를 뒤쪽(최신) 기준으로 잘라낸다.
	 *
	 * <p>원본과 동일하게 <em>시각 부분은 {@code from} 의 것을 유지</em>한다 — 잘린 시작일이
	 * 갑자기 00:00으로 바뀌면 호출자가 세운 창 경계가 어긋난다.
	 */
	public static DateWindow clampTo30Days(String from, String to) {
		if (isBlank(from) || isBlank(to)) {
			return new DateWindow(from, to);
		}
		LocalDate f = parseYmd(digitsOf(from));
		LocalDate t = parseYmd(digitsOf(to));
		if (f == null || t == null) {
			return new DateWindow(from, to);
		}
		if (ChronoUnit.DAYS.between(f, t) > 30) {
			// 시각 접미사(HHmm)는 원본 from 것을 그대로 붙인다. 없으면 창 시작이므로 00:00.
			String timeSuffix = from.length() > 8 ? from.substring(8) : "0000";
			return new DateWindow(t.minusDays(30).format(YMD) + timeSuffix, to);
		}
		return new DateWindow(from, to);
	}

	/**
	 * {@code YYYYMMDDHHmm}(구분자 섞여 있어도 됨)을 파싱한다. 시·분이 없으면 00:00으로 채운다.
	 *
	 * <p>날짜 8자리가 안 되면 {@code null}. 원본은 {@code Invalid Date} 를 만들어 비교가
	 * 조용히 false 로 흐르는데, 여기서는 그 상태를 만들지 않는다.
	 */
	public static LocalDateTime parseG2bDt(Object value) {
		String s = digitsOf(value);
		if (s.length() < 8) {
			return null;
		}
		int year = Integer.parseInt(s.substring(0, 4));
		int month = Integer.parseInt(s.substring(4, 6));
		int day = Integer.parseInt(s.substring(6, 8));
		int hour = s.length() >= 10 ? Integer.parseInt(s.substring(8, 10)) : 0;
		int minute = s.length() >= 12 ? Integer.parseInt(s.substring(10, 12)) : 0;
		return LocalDateTime.of(year, month, day, hour, minute);
	}

	public static String formatG2bDt(LocalDateTime date) {
		return date == null ? null : date.format(G2B_DT);
	}

	/**
	 * 긴 기간을 API가 받아주는 창들로 나눈다.
	 *
	 * <p>{@code maxDays} 뿐 아니라 <em>월말에서도 무조건 끊는다</em>. 일부 오퍼레이션은
	 * 일수와 무관하게 "같은 달 안" 조회만 허용해서, 월을 걸치는 창은 길이가 짧아도
	 * {@code [07]} 로 거절당한다. 그래서 결과 창은 달력 월에 정렬된 모양이 된다.
	 */
	public static List<DateWindow> splitG2bDateRange(String from, String to, int maxDays) {
		if (isBlank(from) || isBlank(to)) {
			return List.of(new DateWindow(from, to));
		}
		LocalDateTime end = parseG2bDt(to);
		LocalDateTime cursor = parseG2bDt(from);
		if (end == null || cursor == null) {
			return List.of(new DateWindow(from, to));
		}

		List<DateWindow> windows = new ArrayList<>();
		while (!cursor.isAfter(end)) {
			LocalDateTime monthEnd = cursor.toLocalDate()
					.withDayOfMonth(cursor.toLocalDate().lengthOfMonth())
					.atTime(23, 59);
			LocalDateTime chunkEnd = cursor.toLocalDate()
					.plusDays(Math.max(1, maxDays) - 1L)
					.atTime(23, 59);
			if (chunkEnd.isAfter(monthEnd)) {
				chunkEnd = monthEnd;
			}
			if (chunkEnd.isAfter(end)) {
				chunkEnd = end;
			}
			windows.add(new DateWindow(formatG2bDt(cursor), formatG2bDt(chunkEnd)));
			cursor = chunkEnd.toLocalDate().plusDays(1).atStartOfDay();
		}

		return windows.isEmpty() ? List.of(new DateWindow(from, to)) : windows;
	}

	public static List<DateWindow> splitG2bDateRange(String from, String to) {
		return splitG2bDateRange(from, to, DEFAULT_MAX_DAYS);
	}

	/**
	 * 범위 길이(일). 원본의 {@code ceil(ms차/86400000) + 1} 을 그대로 옮긴 값이라
	 * "1일치 창"이 2를 돌려준다(00:00~23:59 → ceil(0.999)+1). 이 값은 순수 표시용이 아니라
	 * "더 쪼갤 여지가 있는가"({@code > 1}) 판정에만 쓰이므로 원본 수치를 유지한다.
	 */
	public static int g2bRangeDays(String from, String to) {
		LocalDateTime start = parseG2bDt(from);
		LocalDateTime end = parseG2bDt(to);
		if (start == null || end == null) {
			return 1;
		}
		long diffMs = ChronoUnit.MILLIS.between(start, end);
		long days = (long) Math.ceil(diffMs / 86_400_000.0) + 1;
		return (int) Math.max(1, days);
	}

	/**
	 * 범위를 반으로 가른다. 더 쪼갤 수 없으면 {@code null}.
	 *
	 * <p>{@code G2bFetchService} 의 재귀 이분 탐색이 쓰는 유일한 분할기다. 중간점을 23:59로
	 * 내림해 창 경계가 항상 하루 단위로 떨어지게 한다 — 시각 중간에서 자르면 같은 날 공고가
	 * 두 창 모두에 잡히거나(중복) 어느 쪽에도 안 잡힌다(누락).
	 */
	public static List<DateWindow> splitG2bRangeHalf(String from, String to) {
		if (isBlank(from) || isBlank(to)) {
			return null;
		}
		LocalDateTime start = parseG2bDt(from);
		LocalDateTime end = parseG2bDt(to);
		if (start == null || end == null || !start.isBefore(end)) {
			return null;
		}

		long halfMs = ChronoUnit.MILLIS.between(start, end) / 2;
		LocalDateTime mid = start.plus(halfMs, ChronoUnit.MILLIS).toLocalDate().atTime(23, 59);
		if (!mid.isBefore(end)) {
			return null;
		}
		LocalDateTime next = mid.toLocalDate().plusDays(1).atStartOfDay();
		return List.of(
				new DateWindow(formatG2bDt(start), formatG2bDt(mid)),
				new DateWindow(formatG2bDt(next), formatG2bDt(end)));
	}

	/**
	 * 기본 조회 구간. 검색 화면은 7일을 쓰고, 근거 탐색처럼 넓게 훑어야 하는 곳은 30일을 유지한다.
	 *
	 * <p>원본은 {@code toISOString()} 으로 포맷해서 KST 09:00 이전에는 하루 전 날짜가 나왔다
	 * (UTC 기준). 여기서는 시스템 기본 존의 오늘을 쓴다 — 사용자가 보는 "오늘"과 어긋나지 않게.
	 */
	public static DateWindow defaultDates(int days) {
		int span = Math.max(1, days <= 0 ? 30 : days);
		LocalDate today = LocalDate.now();
		LocalDate past = today.minusDays(span);
		return new DateWindow(past.format(YMD) + "0000", today.format(YMD) + "2359");
	}

	public static DateWindow defaultDates() {
		return defaultDates(30);
	}

	/** 마감일까지 남은 일수. 날짜를 못 읽으면 {@code null}(0이 아니다 — 0은 '오늘 마감'이다). */
	public static Integer daysUntil(Object value) {
		LocalDate dt = parseYmd(digitsOf(value));
		if (dt == null) {
			return null;
		}
		return (int) ChronoUnit.DAYS.between(LocalDate.now(), dt);
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static boolean isBlank(String s) {
		return s == null || s.isEmpty();
	}

	private static String digitsOf(Object value) {
		if (value == null) {
			return "";
		}
		return String.valueOf(value).replaceAll("\\D", "");
	}

	/** 앞 8자리(YYYYMMDD)만 읽는다. 8자리 미만이면 null. */
	private static LocalDate parseYmd(String digits) {
		if (digits == null || digits.length() < 8) {
			return null;
		}
		try {
			return LocalDate.of(
					Integer.parseInt(digits.substring(0, 4)),
					Integer.parseInt(digits.substring(4, 6)),
					Integer.parseInt(digits.substring(6, 8)));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}
}

package com.electerior.g2bmaster.common;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 공용 숫자 파서 ({@code lib/num.js} 이식). G2B 금액 문자열({@code '1,234,000원'} 등)에서 숫자만 뽑는다.
 *
 * <p><strong>미입력은 0이 아니라 {@code null} 이다.</strong> 나라장터 응답은 값이 없을 때 빈 문자열을
 * 준다. 이걸 0으로 접으면 "추정가격 미공개 공고"가 "0원 공고"가 되어 평균·최저가 집계를 통째로
 * 무너뜨린다. 계산에서 아예 빠지도록 null 을 유지한다.
 *
 * <p>반환형이 {@code double} 이 아니라 {@link BigDecimal} 인 이유: 조달 금액은 수천억 원(10^11)
 * 단위까지 가고 원 단위 비교(예정가격 대비 낙찰률)가 의미를 갖는다. double 의 유효자릿수로는
 * 마지막 자리가 흔들린다.
 */
public final class Numbers {

	private Numbers() {}

	/**
	 * 임의의 값에서 숫자를 뽑는다. 뽑을 수 없으면 {@code null}.
	 *
	 * <p>원본과 동일하게 숫자·점·마이너스 외 문자를 모두 지운 뒤 파싱한다.
	 * {@code '90.5%'} → 90.5, {@code '1,234,000원'} → 1234000, {@code ''}·{@code '-'} → null.
	 */
	public static BigDecimal toNumber(Object value) {
		if (value == null) {
			return null;
		}
		// 숫자 타입은 문자열을 거치지 않는다. Double 을 String 으로 만들면 지수표기('1.0E10')가
		// 나오고, 아래 문자 제거 규칙이 'E'를 지워 1.010 이라는 엉뚱한 값이 된다.
		if (value instanceof BigDecimal bd) {
			return bd;
		}
		if (value instanceof BigInteger bi) {
			return new BigDecimal(bi);
		}
		if (value instanceof Double || value instanceof Float) {
			double d = ((Number) value).doubleValue();
			return Double.isFinite(d) ? BigDecimal.valueOf(d) : null;
		}
		if (value instanceof Number n) {
			return BigDecimal.valueOf(n.longValue());
		}

		String raw = String.valueOf(value);
		if (raw.isEmpty()) {
			return null;
		}
		String s = raw.replaceAll("[^\\d.\\-]", "");
		if (s.isEmpty() || "-".equals(s) || ".".equals(s)) {
			return null;
		}
		try {
			return new BigDecimal(s);
		}
		catch (NumberFormatException ex) {
			// '2026-08-05'(마이너스 두 개), '1.2.3' 처럼 남은 문자가 숫자로 안 읽히는 경우.
			// 원본의 Number(...) → NaN → null 과 같은 결말.
			return null;
		}
	}

	/** {@link #toNumber(Object)} 의 long 편의판. 값이 없으면 {@code null}. */
	public static Long toLong(Object value) {
		BigDecimal n = toNumber(value);
		return n == null ? null : n.longValue();
	}

	/** {@link #toNumber(Object)} 의 int 편의판. 값이 없으면 {@code defaultValue}. */
	public static int toInt(Object value, int defaultValue) {
		BigDecimal n = toNumber(value);
		return n == null ? defaultValue : n.intValue();
	}
}

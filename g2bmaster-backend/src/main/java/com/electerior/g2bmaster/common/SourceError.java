package com.electerior.g2bmaster.common;

/**
 * 검색 팬아웃에서 <b>일부 출처만</b> 실패한 사실.
 *
 * <p>입찰공고 검색은 나라장터·D2B·누리장터를 동시에 훑는다. 그중 하나가 죽었을 때
 * 응답 전체를 5xx 로 떨어뜨리면, 살아 있는 두 출처의 결과까지 화면에서 사라진다.
 * 그래서 실패는 예외가 아니라 <em>데이터</em>로 응답에 실려 나가고
 * ({@code sourceErrors[]} · {@code sourceStatus}), 화면이 "국방 출처 일부 실패"를
 * 표시하면서도 나머지를 보여줄 수 있게 한다.
 *
 * @param source    출처 키 ({@code g2b} · {@code d2b} · {@code private-g2b})
 * @param operation 실패한 오퍼레이션명
 * @param message   원인 메시지
 */
public record SourceError(String source, String operation, String message) {
}

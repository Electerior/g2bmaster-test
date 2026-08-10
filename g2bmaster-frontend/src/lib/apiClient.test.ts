/*
 * 오류 문구 고르기.
 *
 * 이 자리가 틀리면 **화면에 원인이 남지 않는다.** 실제로 백엔드가 MySQL 인증에 막혀
 * 죽어 있었는데, vite dev 프록시가 ECONNREFUSED 를 본문 없는 500 으로 바꿔 내려주는 바람에
 * 화면에는 "오류: Request failed with status code 500" 만 떴다. 그 문구에는 백엔드가
 * 안 떠 있다는 사실이 어디에도 없어서, 원인을 찾으려면 개발 서버 로그를 열어야 했다.
 */
import { describe, expect, it } from 'vitest';
import { errorMessageFrom } from './apiClient';

const AXIOS = 'Request failed with status code 500';

describe('errorMessageFrom', () => {
  it('백엔드가 준 한국어 문구를 그대로 쓴다 — 사용자 대상 계약이다', () => {
    expect(errorMessageFrom(503, { error: '나라장터 API 인증에 실패했습니다.' }, AXIOS)).toBe(
      '나라장터 API 인증에 실패했습니다.',
    );
  });

  it('message 가 error 보다 우선한다', () => {
    expect(errorMessageFrom(400, { message: '이쪽', error: '저쪽' }, AXIOS)).toBe('이쪽');
  });

  it('응답 자체가 없으면 연결 실패로 말한다', () => {
    expect(errorMessageFrom(0, undefined, 'Network Error')).toBe('백엔드에 연결하지 못했습니다.');
  });

  it('본문 없는 5xx 는 프록시가 만든 것이다 — axios 문구를 내보내지 않는다', () => {
    // 백엔드의 5xx 는 GlobalExceptionHandler 를 거쳐 언제나 {error} 를 싣는다.
    // 그것이 없다는 것은 우리 백엔드까지 요청이 닿지 않았다는 뜻이다.
    expect(errorMessageFrom(500, undefined, AXIOS)).toBe('백엔드에 연결하지 못했습니다 (HTTP 500).');
    expect(errorMessageFrom(502, {}, AXIOS)).toBe('백엔드에 연결하지 못했습니다 (HTTP 502).');
    expect(errorMessageFrom(504, undefined, AXIOS)).toBe('백엔드에 연결하지 못했습니다 (HTTP 504).');
  });

  it('본문이 있는 5xx 는 프록시 문구로 덮지 않는다 — 백엔드가 말한 이유가 더 정확하다', () => {
    expect(errorMessageFrom(500, { error: '분석 중 오류가 발생했습니다.' }, AXIOS)).toBe(
      '분석 중 오류가 발생했습니다.',
    );
  });

  it('본문 없는 4xx 는 그대로 둔다 — 앞단이 아니라 요청 쪽 문제다', () => {
    // 404·401 을 "연결하지 못했습니다"로 바꾸면 없는 경로를 부른 것이 서버 장애로 보인다.
    expect(errorMessageFrom(404, undefined, 'Request failed with status code 404')).toBe(
      'Request failed with status code 404',
    );
  });
});

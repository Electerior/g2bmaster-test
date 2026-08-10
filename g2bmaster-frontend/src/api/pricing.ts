/*
 * 웹 단가 조회. 딜 카드와 업로드 분석이 같은 표를 쓰므로 응답 형태도 한 벌이다.
 * 판정(배지)은 domain/priceStatus.ts 가 담당한다 — 여기는 전송만 한다.
 */
import { useMutation } from '@tanstack/react-query';
import { get } from '@/lib/apiClient';
import { toSearchParams } from './types';

export interface PriceOption {
  label: string;
  price: number;
  url: string;
  basis: string;
}

export interface PriceResult {
  price: number;
  priceMin?: number;
  priceCount?: number;
  priceOptions?: PriceOption[];
  currency: 'KRW';
  sourceUrl: string;
  confidence: 'high' | 'medium' | 'low';
  note: string;
  /** 'page' = 상품 페이지를 실제로 열어 읽음, 'local-price-db' = 내부 단가표. */
  basis: string;
  misses?: string[];
  verifiedQuotes?: unknown[];
  rejectedQuoteCount?: number;
  provider: string;
  source: string;
  localMatch?: { product: string; category: string; option: string };
}

export interface PriceSearchInfo {
  status: 'found' | 'not-found' | 'error';
  misses: Array<{ site: string; reason: string }>;
  quoteCount: number;
  rejectedQuoteCount: number;
  queryUsed?: string;
  relaxed?: boolean;
  guard?: string;
}

export interface WebPriceResponse {
  name: string;
  /** 모델명 정규화를 거친 실제 질의어. 정규화 실패 시 name 그대로. */
  query: string;
  resolved: { kind: string; name: string; reason: string; source: string; url: string } | null;
  result: PriceResult | null;
  searchInfo: PriceSearchInfo;
  /** 형태가 세 갈래라 좁혀 쓸 때 kind 유무를 먼저 본다. */
  searchError:
    | null
    | { kind: 'client' | 'guard'; message: string }
    | { at: number; endpoint: string; status: number; kind: string | null };
}

export function fetchWebPrice(name: string): Promise<WebPriceResponse> {
  return get<WebPriceResponse>(`/api/web-price?${toSearchParams({ name }).toString()}`);
}

/** 상품 링크를 직접 준 경우. basis 가 'link' 라 신뢰도가 가장 높다. */
export interface WebPriceUrlResponse {
  url: string;
  result: {
    price: number | null;
    currency: 'KRW';
    sourceUrl: string;
    confidence: 'high' | 'medium' | 'low';
    note: string;
    basis: 'link';
    provider: string;
    source: string;
  } | null;
}

export function fetchWebPriceByUrl(url: string, name?: string): Promise<WebPriceUrlResponse> {
  return get<WebPriceUrlResponse>(`/api/web-price-url?${toSearchParams({ url, name }).toString()}`);
}

/**
 * 단가 조회는 사용자가 행마다 눌러 실행하는 것이라 쿼리가 아니라 뮤테이션이다.
 * (동시성 3 제한 + 서킷 브레이커는 features/deal 의 usePriceLookup 이 얹는다.)
 */
export function useWebPrice() {
  return useMutation({ mutationFn: fetchWebPrice });
}

export function useWebPriceByUrl() {
  return useMutation({
    mutationFn: (vars: { url: string; name?: string }) => fetchWebPriceByUrl(vars.url, vars.name),
  });
}

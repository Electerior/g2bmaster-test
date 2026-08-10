/*
 * 서랍 메타 격자 아래에 붙는 접이식 블록들 —
 * 원본 openPlanModal/openSpecModal 의 specBlock · rawHtml · productDetailHtml.
 *
 * 값 포매터(pick · moneyText …)는 metaValues.tsx 에 있다.
 */
import { Collapsible, RawFieldGrid } from '@/components/common/Collapsible';
import type { DecoratedRow } from '@/api';
import { parseProductDetailList } from '../rows';

/** 물품 상세 칩 — 원본 productDetailHtml(app.js:4880). */
export function ProductDetailChips({ value }: { value: unknown }) {
  const items = parseProductDetailList(value);
  if (!items.length) return null;
  return (
    <div className="product-detail-list">
      {items.map((item) => (
        <span key={`${item.seq}-${item.code}`} className="product-detail-chip">
          <strong>{item.code}</strong>
          <span>{item.name}</span>
        </span>
      ))}
    </div>
  );
}

interface SpecContentBlockProps {
  /** [라벨, 값] 쌍. 빈 값은 여기서 거른다 — 호출부가 걸렀다고 믿지 않는다. */
  rows: ReadonlyArray<[string, unknown]>;
  /** 물품 상세 원문(칩으로 풀어 보여준다). */
  productDetailList?: unknown;
}

/** 접이식 '원본 규격 내용 보기' — 원본 openPlanModal/openSpecModal 의 specBlock. */
export function SpecContentBlock({ rows, productDetailList }: SpecContentBlockProps) {
  const filled = rows.filter(([, value]) => value != null && String(value).trim() !== '');
  if (!filled.length) return null;
  return (
    <Collapsible summary="📄 원본 규격 내용 보기">
      <ProductDetailChips value={productDetailList} />
      {filled.map(([label, value]) => (
        <div className="spec-row" key={label}>
          <strong>{label}</strong>
          <br />
          <span>{String(value)}</span>
        </div>
      ))}
    </Collapsible>
  );
}

/** 접이식 '원본 API 전체 필드 보기'. 디버깅용이 아니라 실제로 쓰인다 — G2B 필드가 많다. */
export function RawFieldsBlock({ item }: { item: DecoratedRow }) {
  return (
    <Collapsible quiet summary="원본 API 전체 필드 보기">
      <RawFieldGrid fields={item} />
    </Collapsible>
  );
}

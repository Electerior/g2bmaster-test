/*
 * 페이지네이션 — 원본 renderPagination(app.js:5410).
 * 10페이지 블록 창(현재 페이지가 속한 10개 묶음)과 페이지당 행 수 선택이 한 줄에 온다.
 */
import { PER_PAGE_OPTIONS, type PerPageOption } from './perPage';
import './table.css';

const BLOCK_SIZE = 10;

interface PaginationProps {
  page: number;
  totalPages: number;
  perPage: number;
  onPage: (page: number) => void;
  onPerPage: (perPage: number) => void;
  /** 생략하면 기존 4탭의 선택지(20·50·100·전체). */
  options?: readonly PerPageOption[];
}

export function Pagination({
  page,
  totalPages,
  perPage,
  onPage,
  onPerPage,
  options = PER_PAGE_OPTIONS,
}: PaginationProps) {
  const block = Math.floor((page - 1) / BLOCK_SIZE);
  const start = block * BLOCK_SIZE + 1;
  const end = Math.min(start + BLOCK_SIZE - 1, totalPages);
  const pages: number[] = [];
  for (let p = start; p <= end; p += 1) pages.push(p);

  return (
    <nav className="pagination" aria-label="페이지 이동">
      {totalPages > 1 ? (
        <>
          <button
            type="button"
            disabled={page === 1}
            onClick={() => onPage(page - 1)}
            aria-label="이전 페이지"
          >
            &#8249;
          </button>
          {pages.map((p) => (
            <button
              key={p}
              type="button"
              className={p === page ? 'active' : undefined}
              aria-current={p === page ? 'page' : undefined}
              onClick={() => onPage(p)}
            >
              {p}
            </button>
          ))}
          <button
            type="button"
            disabled={page === totalPages}
            onClick={() => onPage(page + 1)}
            aria-label="다음 페이지"
          >
            &#8250;
          </button>
        </>
      ) : null}
      <select
        className="per-page-select"
        aria-label="페이지당 표시 건수"
        value={String(perPage)}
        onChange={(e) => onPerPage(Number(e.target.value))}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </nav>
  );
}

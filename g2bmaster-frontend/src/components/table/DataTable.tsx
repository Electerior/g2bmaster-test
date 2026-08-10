/*
 * 결과 표. 원본 renderTable(app.js:3908) + showLoading(3962) 을 하나로 합쳤다.
 *
 * 원본은 표를 innerHTML 문자열로 만들고, 정렬·행 클릭을 thead/tbody 에 위임 리스너로 붙였다.
 * 그래서 "이 행이 어떤 항목인가"를 data-id 로 다시 찾아야 했다(bidItemMap). React 에서는
 * 셀 렌더러가 행 객체를 그대로 받으므로 그 간접 참조가 통째로 사라진다.
 */
import { Fragment, type ReactNode } from 'react';
import type { ColumnDef, SortSpec } from '@/domain/columns';
import { columnClass } from './columnClass';
import './table.css';

export interface DataTableProps<T> {
  columns: readonly ColumnDef[];
  rows: readonly T[];
  rowKey: (row: T, index: number) => string;
  sort: SortSpec;
  onSort: (key: string) => void;
  renderCell: (row: T, column: ColumnDef, columnIndex: number) => ReactNode;
  /** 행에 붙일 추가 클래스(file-match-row · blocking-row). */
  rowClassName?: (row: T) => string | undefined;
  /**
   * 본 행 바로 뒤에 붙는 확장 행. `<tr>` 을 통째로 돌려줘야 한다 — 표 구조를 컴포넌트가
   * 대신 정해 버리면 발췌 행처럼 colSpan·클래스가 다른 변형을 만들 수 없다.
   */
  renderSubRow?: (row: T, colSpan: number) => ReactNode;
  loading?: boolean;
  /** 결과가 없을 때 표 아래에 보일 것. 표 자체는 헤더만 남는다. */
  empty?: ReactNode;
  /**
   * 이 값이 바뀌면 표 본문을 부드럽게 새로 그린다(페이드-인). 단계·구분 필터를 바꿔
   * 결과가 통째로 교체될 때, 데이터가 툭 갈리지 않고 젠틀하게 전환되게 하는 용도다.
   * 정렬·페이지처럼 "같은 결과의 순서/조각"만 바뀔 때는 넘기지 않아야 깜빡이지 않는다.
   */
  transitionKey?: string;
}

const SKELETON_ROWS = 5;

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  sort,
  onSort,
  renderCell,
  rowClassName,
  renderSubRow,
  loading = false,
  empty,
  transitionKey,
}: DataTableProps<T>) {
  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((column, i) => {
              // sortKey 를 생략하면 컬럼 키로 정렬한다(기존 4탭). null 이면 정렬 불가라
              // 머리글을 버튼으로 그리지 않는다 — 눌러도 아무 일이 없는 버튼은 거짓말이다.
              const sortKey = column.sortKey === undefined ? column.key : column.sortKey;
              const active = sortKey !== null && sortKey === sort.key;
              // 같은 키가 두 번 나오는 표가 있다(사전 규격의 opninRgstClseDt = 의견마감 +
              // D-DAY). key 에 인덱스를 섞어야 React 가 두 컬럼을 구분한다.
              return (
                <th key={`${column.key}-${i}`} className={columnClass(column.key)}>
                  {sortKey === null ? (
                    column.label
                  ) : (
                    <button
                      type="button"
                      className="th-sortable"
                      onClick={() => onSort(sortKey)}
                      aria-label={`${column.label} 정렬`}
                    >
                      {column.label}
                      <span className={active ? 'sort-icon active' : 'sort-icon'}>
                        {active ? (sort.dir === 'asc' ? '▲' : '▼') : '⇅'}
                      </span>
                    </button>
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        {loading ? (
          <tbody>
            {Array.from({ length: SKELETON_ROWS }, (_, r) => (
              <tr key={`skeleton-${r}`} className="loading-row">
                {columns.map((column, c) => (
                  <td key={`${column.key}-${c}`} className={columnClass(column.key)}>
                    {/* 셀 전체를 블록으로 칠하지 않고 콘텐츠 자리에 옅은 막대만 둔다 —
                        빈 칸이 '고장'처럼 보이지 않게. 빈 칸(★ 컬럼)은 막대를 그리지 않는다. */}
                    {column.fmt === 'save-star' ? null : (
                      <span className="skeleton-bar" aria-hidden="true" />
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        ) : (
          /*
           * transitionKey 가 바뀌면 이 tbody 가 새 key 로 리마운트되며 fade-in 을 재생한다.
           * 단계·구분 필터를 바꿔 결과가 통째로 갈릴 때 툭 바뀌지 않고 부드럽게 나타난다.
           * key 를 안 주면(정렬·페이지 변경) 리마운트가 없어 깜빡이지 않는다.
           */
          <tbody key={transitionKey} className="table-body-fade">
            {rows.map((row, index) => (
              <Fragment key={rowKey(row, index)}>
                <tr className={rowClassName?.(row)}>
                  {columns.map((column, columnIndex) => (
                    <td key={`${column.key}-${columnIndex}`} className={columnClass(column.key)}>
                      {renderCell(row, column, columnIndex)}
                    </td>
                  ))}
                </tr>
                {renderSubRow?.(row, columns.length)}
              </Fragment>
            ))}
          </tbody>
        )}
      </table>
      {loading ? null : empty}
    </div>
  );
}

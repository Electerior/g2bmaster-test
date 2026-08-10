#!/usr/bin/env node
/*
 * convert-stg-tables.js — PostgreSQL stg_* DDL → MySQL 8 Flyway migration (V4).
 *
 * ── WHY THIS IS A SCRIPT AND NOT A HAND-WRITTEN MIGRATION ────────────────────
 * The 75 stg_* staging tables were never hand-written on the Postgres side
 * either: `tools/gen_spec_tables.py` in the g2bmastersopen repo generates
 * `db/schema_spec_tables.sql` (2,650 lines) from `g2b_api_spec.json`. When a
 * 나라장터 OpenAPI response shape changes, the Python generator is re-run and the
 * Postgres DDL is regenerated wholesale. This script is the MySQL leg of that
 * same pipeline, so the regeneration path stays intact:
 *
 *     g2b_api_spec.json
 *       └─(g2bmastersopen/tools/gen_spec_tables.py)─> db/schema_spec_tables.sql   [Postgres]
 *            └─(this script)────────────────────────> V4__staging_tables.sql      [MySQL 8]
 *
 * Regenerate with:
 *   node tools/convert-stg-tables.js \
 *     --in  ../g2bmastersopen/db/schema_spec_tables.sql \
 *     --out src/main/resources/db/migration/V4__staging_tables.sql
 *
 * NOTE: V4 is an *applied* Flyway migration. Once it has run anywhere, editing
 * it in place breaks the checksum. Regenerating is for the pre-release window
 * only; after that, emit the delta as a new V-numbered migration instead.
 *
 * ── CONVERSION RULES (see spec-db.md §1, §2, §10) ────────────────────────────
 * The stg_* shape is uniform, which is what makes this mechanical:
 *   stg_id BIGSERIAL PK, operation TEXT NOT NULL, <N TEXT business columns>,
 *   api_call_id UUID FK, raw_json JSONB, created_at/updated_at TIMESTAMPTZ,
 *   INDEX(operation)
 *
 *   BIGSERIAL PRIMARY KEY        -> BIGINT NOT NULL AUTO_INCREMENT (+ PRIMARY KEY)
 *   TEXT (business columns)      -> TEXT, verbatim.  *** DO NOT "UPGRADE" TO VARCHAR ***
 *   UUID REFERENCES api_call_log -> CHAR(36) CHARACTER SET ascii + FK constraint
 *   JSONB                        -> JSON
 *   TIMESTAMPTZ DEFAULT now()    -> DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)
 *
 * ── ⚠ THE ROW-SIZE TRAP — THE ONE RULE THAT MUST NOT BE RELAXED ──────────────
 * MySQL's row definition limit is 65,535 bytes and VARCHAR counts in full,
 * while TEXT/BLOB contribute only a 9–12 byte pointer. `stg_m_a_s_cntrct_prdct_
 * info_list` has 94 columns; at VARCHAR(255) utf8mb4 (1,022 B each) that is
 * ~96 KB and CREATE TABLE fails outright with ERROR 1118 "Row size too large".
 * Four other stg_* tables (the three stg_prvt_bid_pblanc_list_info_* at 66–67
 * columns and stg_data_set_opn_std_bid_pblanc_info at 58) are in the same
 * bracket. Business columns therefore stay TEXT. Only columns that carry a key
 * or an index are promoted — see PROMOTED_COLUMNS below, which is deliberately
 * tiny. A guard at the bottom of this file re-checks every emitted table.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// ─────────────────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────────────────

const TABLE_SUFFIX =
  'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC';

const UUID_TYPE = 'CHAR(36) CHARACTER SET ascii COLLATE ascii_general_ci';

/*
 * Columns promoted off TEXT, per spec-db.md §2 rule 2 ("promote ONLY columns
 * appearing in a PK / UNIQUE / FK / index"). `operation` is indexed on all 75
 * tables. The other three exist solely because migration 003 put indexes on
 * stg_openg_result_list_info_openg_compt — the single stg_* table the
 * application actually reads (담합 분석, see spec-db.md §12 Tier 1).
 * Indexed columns must be VARCHAR: MySQL cannot index a TEXT column without a
 * prefix length, and a prefix index would not serve the equality lookups here.
 */
const PROMOTED_COLUMNS = {
  // applies to every stg_* table
  '*': {
    operation: 'VARCHAR(64)', // §2 sizing table: service_id/operation/... -> VARCHAR(64)
  },
  // migration 003 (003_openg_compt_rank.sql) indexes these
  stg_openg_result_list_info_openg_compt: {
    bid_ntce_no: 'VARCHAR(64)', // §2: bid_ntce_no group -> VARCHAR(64)
    bid_ntce_ord: 'VARCHAR(64)',
    prcbdr_bizno: 'VARCHAR(32)', // §2: biz_no group -> VARCHAR(32)
  },
};

/*
 * Extra columns and indexes folded in from db/migrations/001..017. The MySQL
 * schema is a fresh baseline, not a replay, so these land directly in the
 * CREATE TABLE rather than as a later ALTER.
 */
const FOLDED_IN = {
  stg_openg_result_list_info_openg_compt: {
    // 003_openg_compt_rank.sql: 개찰경쟁 응답의 opengRank(개찰순위)가 생성 스키마에
    // 없어 버려지고 있었다. 담합 분석은 "누가 낙찰이고 누가 차순위인가"로 짝을
    // 만들기 때문에 순위가 없으면 기능 자체가 성립하지 않는다.
    columns: [{ name: 'openg_rank', type: 'TEXT', comment: '개찰순위 (migration 003)' }],
    indexes: [
      // 참가자 수집은 공고 단위로 지우고 다시 넣는다(갱신 시 중복 방지). 그 삭제·조회용.
      { name: 'idx_openg_compt_notice', cols: ['bid_ntce_no', 'bid_ntce_ord'] },
      // 담합 분석은 업체 단위로 짝을 세므로 그 방향 조회도 잦다.
      { name: 'idx_openg_compt_bidder', cols: ['prcbdr_bizno'] },
    ],
  },
};

const MYSQL_MAX_IDENTIFIER = 64;
const MYSQL_MAX_ROW_BYTES = 65535;
const UTF8MB4_BYTES_PER_CHAR = 4;

// ─────────────────────────────────────────────────────────────────────────────
// Parser — the input is generator output, so its shape is rigidly predictable.
// Anything that does not match is a hard error rather than a silent skip.
// ─────────────────────────────────────────────────────────────────────────────

/** @returns {{name:string, leadingComments:string[], columns:Array, indexes:Array}[]} */
function parse(sql) {
  const lines = sql.split('\n');
  const tables = [];
  let pendingComments = [];
  let current = null;

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const line = raw.trim();

    if (current === null) {
      if (line.startsWith('--')) {
        // Header comments name the service and the operations sharing the shape.
        // They are the only documentation these tables have — keep them.
        pendingComments.push(line);
        continue;
      }
      if (line === '') {
        pendingComments = [];
        continue;
      }
      const create = /^CREATE TABLE (\w+) \($/.exec(line);
      if (create) {
        current = { name: create[1], leadingComments: pendingComments, columns: [], indexes: [] };
        pendingComments = [];
        continue;
      }
      const index = /^CREATE INDEX (\w+) ON (\w+)\(([^)]+)\);$/.exec(line);
      if (index) {
        const owner = tables.find((t) => t.name === index[2]);
        if (!owner) throw new Error(`line ${i + 1}: index ${index[1]} for unknown table ${index[2]}`);
        owner.indexes.push({ name: index[1], cols: index[3].split(',').map((c) => c.trim()) });
        continue;
      }
      throw new Error(`line ${i + 1}: unparsed statement: ${line}`);
    }

    if (line === ');') {
      tables.push(current);
      current = null;
      continue;
    }
    current.columns.push(parseColumn(line, i + 1));
  }

  if (current) throw new Error(`unterminated CREATE TABLE ${current.name}`);
  return tables;
}

function parseColumn(line, lineNo) {
  // e.g. "bid_ntce_no    TEXT,  -- 입찰공고번호"
  const m = /^([a-z_0-9]+)\s+(.*?),?(?:\s*--\s*(.*))?$/.exec(line);
  if (!m) throw new Error(`line ${lineNo}: unparsed column: ${line}`);
  const [, name, rawType, comment] = m;
  return { name, pgType: rawType.replace(/,\s*$/, '').trim(), comment: (comment || '').trim() };
}

// ─────────────────────────────────────────────────────────────────────────────
// Type mapping
// ─────────────────────────────────────────────────────────────────────────────

/** @returns {{sql:string, bytes:number, isAutoInc:boolean, isFk:boolean}} */
function mapType(table, column) {
  const promoted =
    (PROMOTED_COLUMNS[table] && PROMOTED_COLUMNS[table][column.name]) ||
    PROMOTED_COLUMNS['*'][column.name];

  switch (column.pgType) {
    case 'BIGSERIAL PRIMARY KEY':
      return { sql: 'BIGINT NOT NULL AUTO_INCREMENT', bytes: 8, isAutoInc: true, isFk: false };

    case 'TEXT NOT NULL':
      if (!promoted) throw new Error(`${table}.${column.name}: NOT NULL TEXT must be promoted`);
      return { sql: `${promoted} NOT NULL`, bytes: varcharBytes(promoted), isAutoInc: false, isFk: false };

    case 'TEXT':
      // The row-size trap. Business columns stay TEXT.
      return promoted
        ? { sql: promoted, bytes: varcharBytes(promoted), isAutoInc: false, isFk: false }
        : { sql: 'TEXT', bytes: 12, isAutoInc: false, isFk: false };

    case 'UUID REFERENCES api_call_log(call_id)':
      // App-generated UUIDs (crypto.randomUUID / java.util.UUID). CHAR(36) over
      // BINARY(16) because BINARY(16) makes JPA mapping and ad-hoc SQL painful.
      return { sql: UUID_TYPE, bytes: 36, isAutoInc: false, isFk: true };

    case 'JSONB':
      // MySQL JSON. GIN indexing / containment operators are lost, but nothing
      // in the loader indexes raw_json — it is an audit column.
      return { sql: 'JSON', bytes: 12, isAutoInc: false, isFk: false };

    case 'TIMESTAMPTZ DEFAULT now()':
      // DATETIME(6), never TIMESTAMP: TIMESTAMP tops out in 2038 and this
      // dataset carries 9999-12-31 sentinels. Values are stored in UTC.
      return {
        sql: 'DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)',
        bytes: 8,
        isAutoInc: false,
        isFk: false,
      };

    default:
      throw new Error(`${table}.${column.name}: unmapped Postgres type "${column.pgType}"`);
  }
}

function varcharBytes(decl) {
  const m = /^VARCHAR\((\d+)\)$/.exec(decl);
  if (!m) throw new Error(`cannot size "${decl}"`);
  // length prefix (1 B if <=255 chars of storage, else 2 B) + payload
  const chars = Number(m[1]);
  return chars * UTF8MB4_BYTES_PER_CHAR + (chars * UTF8MB4_BYTES_PER_CHAR > 255 ? 2 : 1);
}

// ─────────────────────────────────────────────────────────────────────────────
// Emitter
// ─────────────────────────────────────────────────────────────────────────────

function emitTable(table) {
  const folded = FOLDED_IN[table.name];
  const columns = folded ? [...table.columns, ...foldedColumns(folded)] : table.columns;

  const out = [];
  for (const c of table.leadingComments) out.push(c);

  // Column definitions first, then keys, then FK constraints. MySQL permits
  // interleaving, but grouping keeps the generated file readable and diffable.
  const defs = [];
  const keys = [];
  const constraints = [];
  let rowBytes = 0;

  const mapped = columns.map((c) => ({ column: c, type: mapType(table.name, c) }));
  const widest = Math.max(...mapped.map(({ column }) => column.name.length + 2)); // + backticks

  for (const { column, type } of mapped) {
    rowBytes += type.bytes;
    const name = `\`${column.name}\``.padEnd(widest);
    const comment = column.comment ? ` COMMENT ${quote(column.comment)}` : '';
    defs.push(`  ${name} ${type.sql}${comment}`);
    if (type.isAutoInc) keys.push(`  PRIMARY KEY (\`${column.name}\`)`);
    if (type.isFk) {
      const fk = `fk_${table.name}_api_call`;
      assertIdentifier(fk);
      constraints.push(
        `  CONSTRAINT \`${fk}\` FOREIGN KEY (\`${column.name}\`) REFERENCES \`api_call_log\` (\`call_id\`)`
      );
    }
  }
  if (keys.length !== 1) throw new Error(`${table.name}: expected exactly one AUTO_INCREMENT PK`);

  const indexes = folded ? [...table.indexes, ...folded.indexes] : table.indexes;
  for (const idx of indexes) {
    assertIdentifier(idx.name);
    keys.push(`  KEY \`${idx.name}\` (${idx.cols.map((c) => `\`${c}\``).join(', ')})`);
  }

  assertRowSize(table.name, rowBytes);
  assertIdentifier(table.name);

  out.push(`CREATE TABLE \`${table.name}\` (`);
  out.push([...defs, ...keys, ...constraints].join(',\n'));
  out.push(`) ${TABLE_SUFFIX};`);
  return { sql: out.join('\n'), rowBytes, columnCount: columns.length };
}

function foldedColumns(folded) {
  return folded.columns.map((c) => ({ name: c.name, pgType: c.type, comment: c.comment }));
}

function quote(s) {
  return `'${s.replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}

function assertIdentifier(name) {
  if (name.length > MYSQL_MAX_IDENTIFIER) {
    throw new Error(`identifier "${name}" is ${name.length} chars (MySQL limit ${MYSQL_MAX_IDENTIFIER})`);
  }
}

function assertRowSize(table, bytes) {
  // Belt and braces: the whole reason business columns stay TEXT.
  if (bytes > MYSQL_MAX_ROW_BYTES) {
    throw new Error(
      `${table}: estimated row size ${bytes} B exceeds MySQL's ${MYSQL_MAX_ROW_BYTES} B limit ` +
        `— a column was promoted to VARCHAR that should have stayed TEXT (ERROR 1118)`
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────────────────────────────────────

function arg(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i === -1 ? fallback : process.argv[i + 1];
}

function main() {
  const inPath = path.resolve(
    arg('--in', path.join(__dirname, '..', '..', 'g2bmastersopen', 'db', 'schema_spec_tables.sql'))
  );
  const outPath = path.resolve(
    arg('--out', path.join(__dirname, '..', 'src', 'main', 'resources', 'db', 'migration', 'V4__staging_tables.sql'))
  );

  const tables = parse(fs.readFileSync(inPath, 'utf8'));

  const header = `-- ============================================================
-- V4__staging_tables.sql — 스펙 파생 스테이징 테이블 (stg_*) ${tables.length}종
--
-- GENERATED FILE — DO NOT HAND-EDIT.
--   원본: g2bmastersopen/db/schema_spec_tables.sql
--          (그 자체가 tools/gen_spec_tables.py 가 g2b_api_spec.json 에서 생성한 것)
--   변환: g2bmaster-backend/tools/convert-stg-tables.js
--
-- Append-only staging sinks. Surrogate key, every business column TEXT, one
-- row per API item with the untouched payload in raw_json. 나라장터 OpenAPI 는
-- 모든 값을 문자열로 돌려주므로 여기서는 타입을 강제하지 않는다 — 정규화는
-- dwt_* 로 넘어갈 때 한다.
--
-- ⚠ 업무 컬럼은 반드시 TEXT 로 남긴다. MySQL 행 정의 상한은 65,535 바이트이고
--   VARCHAR 는 전액이 계산에 들어간다(TEXT 는 9~12바이트 포인터만).
--   stg_m_a_s_cntrct_prdct_info_list 는 94컬럼이라 VARCHAR(255) utf8mb4 로
--   일괄 변환하면 약 96KB 가 되어 ERROR 1118 로 테이블 생성 자체가 실패한다.
--   키·인덱스에 쓰이는 컬럼만 VARCHAR 로 승격한다.
--
-- db/migrations/003_openg_compt_rank.sql 은 이 파일에 접어 넣었다
-- (stg_openg_result_list_info_openg_compt.openg_rank + 인덱스 2종).
-- ============================================================
`;

  const chunks = [header];
  let totalColumns = 0;
  let maxRow = { table: null, bytes: 0, columns: 0 };

  for (const table of tables) {
    const { sql, rowBytes, columnCount } = emitTable(table);
    chunks.push(sql);
    totalColumns += columnCount;
    if (rowBytes > maxRow.bytes) maxRow = { table: table.name, bytes: rowBytes, columns: columnCount };
  }

  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, chunks.join('\n\n') + '\n', 'utf8');

  process.stdout.write(
    `wrote ${outPath}\n` +
      `  tables:  ${tables.length}\n` +
      `  columns: ${totalColumns}\n` +
      `  widest row: ${maxRow.table} (${maxRow.columns} cols, ~${maxRow.bytes} B of ${MYSQL_MAX_ROW_BYTES})\n`
  );
}

if (require.main === module) {
  try {
    main();
  } catch (err) {
    process.stderr.write(`convert-stg-tables: ${err.message}\n`);
    process.exit(1);
  }
}

module.exports = { parse, mapType, emitTable };

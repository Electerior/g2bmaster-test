#!/usr/bin/env node
// lib/g2b-operations.js(1527줄 생성물)를 Java 소스로 옮겨 적지 않고 리소스 JSON으로 뽑는다.
//
// 왜 JSON인가:
//   - 원본이 BaseG2B g2b_loader.py에서 자동 생성되는 산출물이다. Java 상수로 손번역해 두면
//     원본이 갱신될 때마다 189개 오퍼레이션을 사람이 다시 대조해야 하고, 그 대조는 반드시 틀린다.
//   - 여기서는 매핑 테이블(원자료)만 옮기고, 선택 규칙(selectOperations)은 Java 쪽에 다시 구현한다.
//     규칙은 짧고 테스트 가능하지만 자료는 길고 기계적이기 때문이다.
//
// 재생성:
//   node tools/gen-g2b-operations.js [g2bmastersopen 경로]
//
// 기본 경로는 이 저장소와 형제로 놓인 ../g2bmastersopen 이다.

const fs = require('fs');
const path = require('path');

const sourceRoot = process.argv[2] || path.resolve(__dirname, '..', '..', 'g2bmastersopen');
const modulePath = path.join(sourceRoot, 'lib', 'g2b-operations.js');

if (!fs.existsSync(modulePath)) {
  console.error(`원본을 찾을 수 없습니다: ${modulePath}`);
  console.error('사용법: node tools/gen-g2b-operations.js <g2bmastersopen 경로>');
  process.exit(1);
}

const ops = require(modulePath);

// 선택 규칙은 Java(G2bOperationCatalog)가 다시 구현하므로 여기서는 원자료만 넘긴다.
// GENERIC_TABLE 은 원본에서 export 되지 않아 값을 그대로 박아 둔다(원본 lib/g2b-operations.js 참조).
const payload = {
  _generatedBy: 'tools/gen-g2b-operations.js',
  _source: 'g2bmastersopen/lib/g2b-operations.js',
  genericTable: 'raw_generic_list',
  curatedOperations: ops.CURATED_OPERATIONS,
  specOperations: ops.SPEC_OPERATIONS,
  servicePath: ops.SERVICE_PATH,
  operationTable: ops.OPERATION_TABLE,
  pkColumns: ops.PK_COLUMNS,
  serviceTable: ops.SERVICE_TABLE,
  importantExtra: ops.IMPORTANT_EXTRA,
};

const outPath = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'g2b-operations.json');
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(payload, null, 2) + '\n', 'utf8');

// 생성 직후 원본의 selectOperations 로 기대 개수를 찍어 둔다. Java 쪽 테스트가 이 수와 맞아야 한다.
const c = ops.selectOperations('curated').length;
const i = ops.selectOperations('important').length;
const a = ops.selectOperations('all').length;
console.log(`wrote ${outPath}`);
console.log(`expected counts — curated=${c} important=${i} all=${a}, services=${Object.keys(ops.SERVICE_PATH).length}`);

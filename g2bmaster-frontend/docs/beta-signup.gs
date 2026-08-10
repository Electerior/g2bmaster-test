/**
 * G2B MASTERS 베타 모집 — 구글 시트 접수 엔드포인트 (Google Apps Script)
 *
 * 이 파일은 저장소에서 빌드되지 않는다. Apps Script 편집기에 붙여 넣고 웹앱으로 배포한 뒤,
 * 배포 URL 을 프론트의 VITE_BETA_SHEET_URL 에 넣어 쓴다. 설치 절차는 맨 아래 주석 참고.
 *
 * 계약은 Spring 쪽 /api/beta/* 와 같은 모양을 유지한다(docs/api-contract.md §1.1).
 *   GET  → { total, remaining, deadline, open }
 *   POST → { ok: true, receivedAt }
 *   오류 → { error: "한국어 문구", code? }
 * 그래야 프론트가 목적지를 바꿔도 화면 코드가 그대로다.
 */

/* ── 설정 ─────────────────────────────────────────────────────────────────── */

var CONFIG = {
  /*
   * 접수 시트의 문서 ID는 여기 두지 않는다 — spreadsheetId_() 참고.
   * g2bmaster-frontend 는 공개 저장소이고, 이 시트에는 신청자의 이름·연락처·이메일이
   * 쌓인다. ID 만으로 열리지는 않지만, 공유 설정이 한 번만 느슨해지면 그 즉시 찾아갈 수
   * 있는 주소가 된다. 공개된 곳에 남겨 둘 이유가 없다.
   */

  /** 접수 탭 이름. 없으면 만든다. */
  SHEET_NAME: '시트1',

  /** 화면에 내거는 정원. "50개사만 받습니다" 문구와 슬롯 막대에 쓰인다. */
  TOTAL: 50,

  /**
   * 실제로 받는 수. 잔여 자리는 이 수에서 시트 행 개수를 뺀 값이다.
   * TOTAL 과 다른 이유: 50개사가 전체 규모이고 이번 회차에 실제로 받는 건 12개사다.
   * 잔여가 0 이 되면 open:false 가 되어 프론트 폼이 잠긴다.
   */
  CAPACITY: 12,

  /** ISO-8601, offset 포함. 이 시각을 지나면 open:false. */
  DEADLINE: '2026-08-31T23:59:59+09:00',

  /** 한 필드가 이보다 길면 거절한다. 폼을 통한 대량 주입을 막는 최소 방어다. */
  MAX_FIELD: 200,
};

/**
 * 시트 열 정의.
 *
 * 위치가 아니라 헤더 이름으로 찾는다. 시트에 이미 '이름 · 연락처 · 소속기관/기업 · 이메일'이
 * 그 순서로 들어 있고, 운영하다 보면 열을 옮기거나 사이에 끼워 넣게 된다 — 열 번호를
 * 코드에 박아 두면 그때마다 조용히 엉뚱한 칸에 쓴다.
 *
 * header 문자열은 시트에 있는 것과 글자까지 같아야 한다. 다르면 같은 뜻의 열이 하나 더
 * 생긴다. 여기 없는 열(메모 등)은 건드리지 않는다.
 */
var COLUMNS = [
  { header: '접수시각', value: function (b, now) { return now; } },
  { header: '이름', value: function (b) { return String(b.name).trim(); } },
  { header: '연락처', value: function (b) { return String(b.phone).trim(); } },
  { header: '소속기관/기업', value: function (b) { return String(b.organization).trim(); } },
  { header: '업종', value: function (b) { return String(b.industry || '').trim(); } },
  { header: '이메일', value: function (b) { return normalizedEmail_(b.email); } },
  // PIPA 상 동의 증빙. 접수 시각과 값이 같아도 열을 따로 두는 이유는, 나중에 동의 항목이
  // 늘어나면 접수와 동의 시점이 갈리기 때문이다. 원본 DB 스키마도 같은 이유로 나눠 뒀다.
  { header: '개인정보동의', value: function (b, now) { return now; } },
  /*
   * 재시도 식별자. 사람이 읽을 일은 없지만 지우면 안 된다.
   *
   * 프론트는 응답이 안 오면 같은 신청을 다시 보낸다(구글 리다이렉트가 자주 실패한다).
   * 이 값이 없으면 서버는 "이미 있는 이메일"만 보고, 그게 방금 자기가 저장한 것인지
   * 남이 예전에 넣은 것인지 구분할 수 없다. 같은 요청이면 조용히 성공으로 돌려주고,
   * 다른 요청이면 중복으로 막기 위해 필요하다.
   */
  { header: '요청ID', value: function (b) { return String(b.requestId || ''); } },
];

/** 중복 검사와 행 세기의 기준 열. */
var EMAIL_HEADER = '이메일';
var REQUEST_ID_HEADER = '요청ID';

/* ── 조회 ─────────────────────────────────────────────────────────────────── */

function doGet() {
  return json(status_());
}

function status_() {
  var rows = countRows_();
  var remaining = Math.max(0, CONFIG.CAPACITY - rows);
  var open = remaining > 0 && new Date().getTime() < new Date(CONFIG.DEADLINE).getTime();
  return {
    total: CONFIG.TOTAL,
    remaining: remaining,
    deadline: CONFIG.DEADLINE,
    open: open,
  };
}

/**
 * 신청자 수 = 이메일이 실제로 들어 있는 행의 수.
 *
 * getLastRow() 만 보면 맨 아래 빈 행이나 서식만 남은 행까지 세어 실제보다 크게 잡힌다.
 * 운영자가 시트에서 행을 지우면 수가 줄고 자리가 다시 열리는 게 의도한 동작이다.
 */
function countRows_() {
  var sheet = sheet_();
  var col = columnIndex_(sheet, EMAIL_HEADER);
  var last = sheet.getLastRow();
  if (last < 2) return 0;

  var values = sheet.getRange(2, col, last - 1, 1).getValues();
  var n = 0;
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]).trim() !== '') n++;
  }
  return n;
}

/* ── 접수 ─────────────────────────────────────────────────────────────────── */

function doPost(e) {
  // 동시 요청이 중복 검사를 함께 통과하는 것을 막는다. 시트에는 UNIQUE 제약이 없어서
  // 애플리케이션 검사만으로는 경쟁 조건이 남는다.
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
  } catch (err) {
    return json({ error: '접수가 몰리고 있습니다. 잠시 후 다시 시도해 주세요.' });
  }

  try {
    var body = parseBody_(e);
    if (!body) return json({ error: '요청을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.' });

    // 봇이면 저장하지 않고 성공한 것처럼 응답한다. 실패를 알려주면 우회를 학습한다.
    if (String(body.website || '').trim() !== '') {
      return json({ ok: true, receivedAt: new Date().toISOString() });
    }

    var invalid = validate_(body);
    if (invalid) return json({ error: invalid });

    /*
     * 같은 요청이 이미 저장돼 있으면 성공으로 돌려준다.
     *
     * 응답이 프론트에 닿지 못해 다시 보낸 경우다. 스크립트는 이미 끝났고 행도 들어갔는데
     * 프론트만 결과를 못 받은 것이라, 여기서 중복이라고 막으면 정상 신청자에게 "이미
     * 신청된 이메일"이 뜬다. 이 검사가 이메일 중복 검사보다 먼저 와야 한다.
     */
    var requestId = String(body.requestId || '').trim();
    if (requestId && findRow_(REQUEST_ID_HEADER, requestId) > 0) {
      return json({ ok: true, receivedAt: new Date().toISOString() });
    }

    var email = normalizedEmail_(body.email);
    if (findRow_(EMAIL_HEADER, email, normalizedEmail_) > 0) {
      return json({
        error: '이미 신청된 이메일입니다. 결과 안내를 기다려 주세요.',
        code: 'DUPLICATE_EMAIL',
      });
    }

    appendSignup_(body);
    return json({ ok: true, receivedAt: new Date().toISOString() });
  } catch (err) {
    // 실패 내용은 실행 로그에만 남긴다. 사용자에게 개발자용 메시지를 보이지 않는다.
    console.error(err);
    return json({ error: '접수에 실패했습니다. 잠시 후 다시 시도해 주세요.' });
  } finally {
    lock.releaseLock();
  }
}

/** 헤더 위치에 맞춰 한 행을 만든다. 정의에 없는 열은 빈 칸으로 남긴다. */
function appendSignup_(body) {
  var sheet = sheet_();
  var now = new Date();

  // 열 번호를 먼저 다 구한다. columnIndex_ 가 없는 열을 새로 붙일 수 있어서, 행 너비를
  // 미리 정해 두면 마지막에 추가된 열이 배열 밖으로 나간다.
  var placed = [];
  for (var i = 0; i < COLUMNS.length; i++) {
    placed.push({ col: columnIndex_(sheet, COLUMNS[i].header), value: COLUMNS[i].value(body, now) });
  }

  var width = 0;
  for (var j = 0; j < placed.length; j++) width = Math.max(width, placed[j].col);

  var row = new Array(width).fill('');
  for (var k = 0; k < placed.length; k++) row[placed[k].col - 1] = placed[k].value;

  sheet.appendRow(row);
}

/**
 * 프론트는 preflight 를 피하려고 Content-Type: text/plain 으로 JSON 문자열을 보낸다
 * (Apps Script 웹앱은 OPTIONS 를 처리하지 못한다). 그래서 postData.contents 를 직접 파싱한다.
 * 폼 인코딩으로 들어오는 경우도 대비해 parameter 를 함께 본다.
 */
function parseBody_(e) {
  if (e && e.postData && e.postData.contents) {
    try {
      return JSON.parse(e.postData.contents);
    } catch (err) {
      return e.parameter && e.parameter.email ? e.parameter : null;
    }
  }
  return e && e.parameter && e.parameter.email ? e.parameter : null;
}

/** 통과하면 null, 아니면 화면에 그대로 뜰 한국어 문구. */
function validate_(b) {
  var required = [
    ['name', '이름을 입력해 주세요.'],
    ['organization', '소속 기관·기업을 입력해 주세요.'],
    ['industry', '업종을 입력해 주세요.'],
    ['email', '이메일을 입력해 주세요.'],
    ['phone', '연락처를 입력해 주세요.'],
  ];
  for (var i = 0; i < required.length; i++) {
    var v = String(b[required[i][0]] || '').trim();
    if (!v) return required[i][1];
    if (v.length > CONFIG.MAX_FIELD) return '입력값이 너무 깁니다. 다시 확인해 주세요.';
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(b.email).trim())) {
    return '이메일 형식을 확인해 주세요.';
  }
  if (b.privacyAgreed !== true && String(b.privacyAgreed) !== 'true') {
    return '개인정보 수집·이용 동의가 필요합니다.';
  }
  return null;
}

/**
 * 어느 열에서 값이 일치하는 행 번호. 없으면 0.
 * normalize 를 주면 저장된 값과 찾는 값 양쪽에 같은 정규화를 적용한다(이메일 대소문자 등).
 */
function findRow_(header, needle, normalize) {
  if (!needle) return 0;
  var sheet = sheet_();
  var col = columnIndex_(sheet, header);
  var last = sheet.getLastRow();
  if (last < 2) return 0;

  var values = sheet.getRange(2, col, last - 1, 1).getValues();
  for (var i = 0; i < values.length; i++) {
    var cell = normalize ? normalize(values[i][0]) : String(values[i][0]).trim();
    if (cell === needle) return i + 2;
  }
  return 0;
}

function normalizedEmail_(v) {
  return String(v == null ? '' : v).trim().toLowerCase();
}

/* ── 시트 접근 ────────────────────────────────────────────────────────────── */

/**
 * 실행 한 번 안에서만 사는 캐시.
 *
 * openById 와 헤더 행 읽기는 각각 왕복 비용이 있고, 접수 한 건에 sheet_() 가 세 번
 * (중복 검사 · 행 세기 · 쓰기), columnIndex_ 가 여섯 번 불린다. 캐시가 없으면 같은 것을
 * 열 번 가까이 다시 읽는다. Apps Script 는 요청마다 전역이 초기화되므로 이 값들이
 * 요청 사이에 남아 낡을 걱정은 없다.
 */
var cache_ = { sheet: null, headers: null };

/**
 * 접수 시트의 문서 ID.
 *
 * 코드가 아니라 스크립트 속성(프로젝트 설정 → 스크립트 속성)에서 읽는다. 이 파일은
 * 공개 저장소에 그대로 들어가지만 속성값은 프로젝트 안에만 남는다.
 *
 * 시트에 붙여 만든 스크립트라면 속성 없이도 돈다 — 그때는 활성 스프레드시트를 쓴다.
 */
function spreadsheetId_() {
  return PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID') || '';
}

function sheet_() {
  if (cache_.sheet) return cache_.sheet;

  var id = spreadsheetId_();
  var ss = id ? SpreadsheetApp.openById(id) : SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error(
      '스프레드시트를 찾지 못했습니다. 프로젝트 설정 → 스크립트 속성에 ' +
        'SPREADSHEET_ID 를 넣으세요(시트 주소 .../spreadsheets/d/<이 토막>/edit).',
    );
  }

  var sheet = ss.getSheetByName(CONFIG.SHEET_NAME);
  if (!sheet) sheet = ss.insertSheet(CONFIG.SHEET_NAME);
  cache_.sheet = sheet;
  return sheet;
}

/**
 * 헤더 이름의 열 번호(1부터). 없으면 헤더 행 오른쪽 끝에 만들어 붙인다.
 *
 * 기존 열은 옮기지 않는다 — 시트에 이미 있는 '이름 · 연락처 · 소속기관/기업 · 이메일'은
 * 그 자리에 그대로 두고, 없는 '접수시각 · 개인정보동의'만 뒤에 추가된다.
 * 첫 실행 때 한 번만 일어난다.
 */
function columnIndex_(sheet, header) {
  if (!cache_.headers) {
    var lastCol = sheet.getLastColumn();
    cache_.headers = lastCol > 0 ? sheet.getRange(1, 1, 1, lastCol).getValues()[0] : [];
  }
  var headers = cache_.headers;

  for (var i = 0; i < headers.length; i++) {
    if (String(headers[i]).trim() === header) return i + 1;
  }

  var col = headers.length + 1;
  sheet.getRange(1, col).setValue(header).setFontWeight('bold');
  if (sheet.getFrozenRows() === 0) sheet.setFrozenRows(1);
  headers.push(header); // 같은 실행 안에서 두 번 만들지 않도록 캐시도 함께 늘린다
  return col;
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(
    ContentService.MimeType.JSON,
  );
}

/* ============================================================================
   설치

   1. 이 파일 내용을 Apps Script 편집기에 통째로 붙여 넣고 저장한다
   2. 프로젝트 설정(왼쪽 톱니바퀴) → 스크립트 속성 → 속성 추가
        속성: SPREADSHEET_ID
        값  : 접수 시트 주소 .../spreadsheets/d/<이 토막>/edit
      시트에 붙여 만든 스크립트라면 이 단계를 건너뛰어도 된다.
      이 값을 코드에 적지 않는 이유는 이 파일이 공개 저장소에 들어가기 때문이다 —
      신청자 개인정보가 쌓이는 시트의 주소를 인터넷에 남기지 않는다.
   3. 편집기에서 함수 목록에 doGet 을 골라 한 번 실행한다 → 권한 승인 창이 뜬다
        여기서 승인해 두지 않으면 배포 후 첫 호출이 실패한다
        승인 후 시트에 '접수시각'·'개인정보동의' 두 열이 오른쪽에 붙는다
   4. 배포 → 새 배포 → 유형 '웹 앱'
        - 실행 계정: 나
        - 액세스 권한: 모든 사용자        ← 랜딩이 로그인 없이 부르므로 필요하다
   5. 나오는 URL(https://script.google.com/macros/s/…/exec)을 프론트 .env 에 넣는다
        VITE_BETA_SHEET_URL=https://script.google.com/macros/s/…/exec
      편집기 주소(script.google.com/home/projects/…/edit)가 아니다. 그건 코드를 여는
      링크일 뿐이라 프론트에서 부를 수 없다.
   6. 코드를 고치면 '배포 관리 → 편집(연필) → 버전: 새 버전'으로 다시 배포한다.
      저장만으로는 배포된 URL 의 동작이 바뀌지 않는다. '새 배포'를 누르면 URL 이 새로
      발급되어 .env 도 함께 고쳐야 하므로, 기존 배포를 편집하는 쪽이 낫다.

   붙었는지 확인

     브라우저 주소창에 …/exec 를 그대로 열어 본다. 아래처럼 나오면 정상이다.
       {"total":50,"remaining":12,"deadline":"2026-08-31T23:59:59+09:00","open":true}
     로그인 페이지나 HTML 오류가 나오면 3번의 '액세스 권한: 모든 사용자'를 다시 본다.

   알아 둘 것

   - 이 URL 은 공개된다. 아는 사람은 누구나 POST 할 수 있다. 허니팟·필수값·중복·길이
     검사로 막고 있지만 결심한 공격자를 막지는 못한다. 접수 폭주가 실제로 생기면
     Spring 뒤로 옮기는 게 맞다(그때는 .env 의 VITE_BETA_SHEET_URL 만 지우면 된다).
   - 신청자 IP 는 남기지 않는다. Apps Script 에서 클라이언트 IP 를 얻을 수 없다.
     원본 DB 스키마의 source_ip 는 이 경로에서 비어 있다.
   - 잔여 자리는 CONFIG.CAPACITY 에서 시트 행 수를 뺀 값이다. 받는 수를 늘리려면 시트가
     아니라 CAPACITY 를 고치고 4번대로 재배포한다.
   - 랜딩 하단이 "베타 종료 후 지체 없이 파기"를 고지하고 있다. 종료 후 이 시트를 지우는
     일은 자동화돼 있지 않다 — 사람이 해야 고지와 실제가 어긋나지 않는다.
   ============================================================================ */

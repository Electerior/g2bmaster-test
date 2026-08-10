# g2bmaster-backend

나라장터(G2B) 입찰정보 백엔드 — Spring Boot 4.1 / Java 25 / MySQL 8.

모놀리스 [`g2bmastersopen`](https://github.com/Electerior/g2bmastersopen) 를 세 저장소로
나눈 것 중 하나다.

| 저장소 | 역할 | 스택 |
|---|---|---|
| [`g2bmaster-frontend`](https://github.com/Electerior/g2bmaster-frontend) | 화면 | React 18 + TypeScript + Vite |
| **`g2bmaster-backend`** (이 저장소) | API·적재·영속 | Spring Boot 4.1 + MySQL 8 |
| [`g2bmaster-AI`](https://github.com/Electerior/g2bmaster-AI) | 추론 | (기존 Python/LLM 스택 유지) |

**이 백엔드는 AI 추론을 직접 하지 않는다.** LLM·임베딩·법령 MCP·가격 웹검색은
AI 저장소가 소유하고 HTTP 로 위임한다. 무엇이 어느 쪽인지는
[`docs/ai-boundary.md`](docs/ai-boundary.md) 에 정리했다.

---

## 실행

JDK 25 와 MySQL 8 만 있으면 된다 — Maven 은 저장소의 래퍼(`./mvnw`)를 쓴다.

DB 를 만들고,

```bash
mysql -u root -p -e "CREATE DATABASE g2b CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

띄운다. 스키마(`src/main/resources/db/migration/`)는 Flyway 가 기동하면서 적용하므로
따로 넣을 것이 없다.

```bash
./mvnw spring-boot:run
```

`http://localhost:8080` 에 뜬다. 확인은 이걸로 한다.

```bash
curl localhost:8080/healthz
```

**AI 저장소가 없어도 돈다.** `AI_ENABLED=false` 로 두면 검색·트렌드·저장 공고·운영
화면은 그대로 동작한다 (범위는 [`docs/ai-boundary.md`](docs/ai-boundary.md) §7).

| 명령 | 설명 |
|---|---|
| `./mvnw spring-boot:run` | 개발 서버 (기본 포트 8080) |
| `./mvnw test` | 단위 테스트 |
| `./mvnw -DskipTests package` | jar 패키징 |
| `java -jar target/*.jar` | 패키징한 jar 실행 |

### API 문서 (Swagger)

띄우면 바로 붙는다. 별도 설정은 없다.

| 주소 | 내용 |
|---|---|
| <http://localhost:8080/swagger-ui.html> | Swagger UI (`/swagger-ui/index.html` 로 넘어간다) |
| <http://localhost:8080/v3/api-docs> | OpenAPI 3.1 문서 (JSON) |

**여기 보이는 것이 지금 뜬 프로세스가 실제로 제공하는 전부다** — 현재 27개.
[`docs/api-contract.md`](docs/api-contract.md) 의 65개와 다른 것이 정상이고,
그 차이가 곧 이식 진도다. 계약을 볼 때는 그 문서를, 지금 되는 것을 볼 때는 Swagger 를 본다.

자물쇠(🔒)가 붙은 경로는 앱 키가 필요하다. `Authorize` 버튼에 `APP_API_KEY` 값을 넣으면
`X-API-Key` 와 `Authorization: Bearer` 둘 중 아무 쪽으로나 보낼 수 있다 — 서버가 둘 다 받는다.
**`APP_API_KEY` 를 설정하지 않고 띄웠다면 인증이 꺼져 있어** 자물쇠가 달린 경로도 그냥 호출된다.

자물쇠는 손으로 붙이지 않는다. `config/OpenApiConfig` 가 인증 인터셉터와 **같은 판정 함수**로
달기 때문에, 인증을 새로 건 경로에서 문서만 열려 있다고 적히는 일이 생기지 않는다.
이 대응은 `OpenApiDocumentTest` 가 확인한다.

운영에서 문서를 닫으려면 — **하이픈이 빠진 이름**이다(`api-docs` → `APIDOCS`).
스프링의 환경변수 완화 바인딩은 하이픈을 밑줄로 바꾸지 않고 지운다:

```bash
SPRINGDOC_APIDOCS_ENABLED=false SPRINGDOC_SWAGGERUI_ENABLED=false ./mvnw spring-boot:run
```

### 준비물 자세히

- MySQL 은 **8.0.13** 이 하한이다 (생성 컬럼 기본값 문법). `ON DUPLICATE KEY UPDATE`
  행 별칭 때문에 **8.0.19 이상을 권한다**
- root 대신 전용 계정을 쓸 거면:

```bash
mysql -u root -p -e "CREATE USER 'g2b'@'localhost' IDENTIFIED BY '비밀번호'; GRANT ALL ON g2b.* TO 'g2b'@'localhost';"
```

---

## 환경 변수

기존 `.env` 의 이름을 최대한 유지했다. `PG*` 만 `MYSQL_*` 로 바뀐다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` | `localhost` / `3306` | |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | `g2b` / `g2b` / — | |
| `MYSQL_POOL_MAX` | `10` | 원본 `PG_POOL_MAX` 대응 |
| `PORT` | `8080` | |
| `G2B_SERVICE_KEY` | — | 나라장터 OpenAPI 키. 없으면 검색이 503 |
| `D2B_SERVICE_KEY` | — | 국방전자조달. 키 없이도 일부 동작 |
| `APP_API_KEY` | — | **비워두면 앱 인증이 꺼진다**(개발 모드) |
| `DEBUG_SECRET` | — | 운영에서 디버그 경로 보호 |
| `ALERT_SECRET` | — | 알림 배치 트리거 보호 |
| `AI_BASE_URL` | `http://localhost:8000` | g2bmaster-AI 주소 |
| `AI_ENABLED` | `true` | `false` 면 AI 없는 기능만으로 동작 |
| `AI_TIMEOUT_MS` | `120000` | **AI 자체 데드라인 < 이 값 < `ANALYSIS_LEASE_MS`** 를 지킬 것 |
| `AI_SERVICE_SECRET` | — | AI 저장소의 같은 이름 값과 **한 쌍**이다. 한쪽만 설정하면 AI 호출이 전부 401 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 프론트 오리진 |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | — | 알림 메일 |
| `ALERT_EMAIL` / `ALERT_KEYWORDS` | — | |
| `SYNC_ENABLED` | `false` | 주기 동기화 스케줄러. 운영 인스턴스에서만 켠다 |

---

## 구조

```
com.electerior.g2bmaster
├── config/          설정 프로퍼티, CORS, 인터셉터 등록
├── common/          공통 응답·예외·날짜/숫자 유틸
├── security/        앱 API 키 인증, 디버그 게이트
├── integration/
│   ├── g2b/         나라장터 OpenAPI 클라이언트 (동시성 제한·재시도·창 분할·캐시)
│   ├── d2b/         국방전자조달
│   └── ai/          g2bmaster-AI 위임 클라이언트
├── notice/          입찰공고·개찰결과 검색
├── prespec/         사전규격
├── trend/           트렌드 집계
├── analysis/        분석 작업 큐·이력 (추론은 하지 않는다)
├── export/          엑셀 내보내기 작업
├── attachment/      첨부 다운로드·텍스트 추출·캐시
└── system/          운영 현황·적재·스케줄
```

---

## 문서

- [`docs/api-contract.md`](docs/api-contract.md) — 65개 엔드포인트의 계약.
  프론트와 공유하는 것이므로 필드명을 바꾸면 두 저장소가 함께 깨진다.
- [`docs/notice-search-index.md`](docs/notice-search-index.md) — 공고 검색 색인(`bid_notice`).
  나라장터를 주기 적재해 쌓고 검색은 로컬만 조회하는 계통의 스키마·파이프라인·API·운영.
- [`docs/ai-boundary.md`](docs/ai-boundary.md) — AI 저장소와의 경계.
- [`docs/migration-notes.md`](docs/migration-notes.md) — PostgreSQL → MySQL 변환에서
  의미가 달라진 지점들.

---

## 이식 상태

원본은 `server.js` 5363줄 + `lib/` 52개 모듈 + 113개 테이블이다. 한 번에 전부 옮기지
않았다. 어디까지 왔는지는 [`docs/porting-status.md`](docs/porting-status.md) 를 볼 것.

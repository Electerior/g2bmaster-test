#!/usr/bin/env bash
# g2bmaster-ai · g2bmaster-backend · g2bmaster-frontend 를 한 번에 띄운다.
#
# 이 스크립트가 값을 정하는 자리는 세 저장소가 **짝을 맞춰야 하는 설정**들이다.
# 각 저장소의 기본값을 그대로 두면 되는 것은 여기서 건드리지 않는다.
#
#   1) AI_SERVICE_SECRET  — 백엔드와 AI 가 같은 값을 봐야 한다. 한쪽만 설정하면 AI 호출이 전부 401
#   2) 데드라인 순서       — LLM_TIMEOUT_SECONDS < AI_TIMEOUT_MS < ANALYSIS_LEASE_MS
#   3) JAVA_HOME          — 백엔드는 Java 25 로 빌드한다. 기본 JDK 가 21 이면 컴파일 자체가 안 된다
#   4) APP_API_KEY        — 비워 둔다. 프론트에 인증 헤더 배선이 아직 없다(아래 주석 참고)
#
# 사용법:
#   bash start_all.sh                 이미 떠 있으면 멈춘다(기본)
#   bash start_all.sh --force         우리 트리에서 뜬 것을 내리고 다시 띄운다
#   bash start_all.sh --force-all     남의 작업 트리에서 뜬 것까지 내린다
#   SKIP_AI=1 bash start_all.sh       이미 떠 있는 AI 를 그대로 두고 나머지만 띄운다
#
# DB 는 docker-compose.yml 의 MySQL 을 기본으로 쓴다(3307). 스크립트가 알아서 띄우므로
# 자격증명을 넘기지 않아도 된다. 다른 MySQL 을 쓰려면 USE_DOCKER_DB=0 과 MYSQL_* 를 준다.
#
# 자주 쓰는 환경변수:
#   G2B_SERVICE_KEY   나라장터 OpenAPI 키. 없으면 검색 계열이 503
#   USE_DOCKER_DB=0   compose MySQL 을 쓰지 않고 MYSQL_* 로 준 DB 에 붙는다
#   MYSQL_USER / MYSQL_PASSWORD / MYSQL_HOST / MYSQL_PORT   외부 DB 를 쓸 때

set -euo pipefail

# 머리말 주석을 그대로 보여 준다. 줄 번호를 박으면 위를 한 줄 고칠 때마다 어긋난다.
usage() {
    sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \?//'
    exit 0
}

#: "" = 강제하지 않음 · "own" = 우리 트리 것만 내림 · "all" = 남의 것까지 내림
FORCE_RESTART="${FORCE_RESTART:-}"
case "${FORCE_RESTART}" in
    1|true|yes) FORCE_RESTART="own" ;;
esac

while (( $# )); do
    case "$1" in
        -f|--force)     FORCE_RESTART="own" ;;
        -F|--force-all) FORCE_RESTART="all" ;;
        -h|--help)      usage ;;
        *) echo "알 수 없는 인자: $1 (--help 참고)" >&2; exit 2 ;;
    esac
    shift
done

BASE_DIR="${BASE_DIR:-${HOME}/dev}"
AI_DIR="${BASE_DIR}/g2bmaster-AI"
BACKEND_DIR="${BASE_DIR}/g2bmaster-backend"
FRONTEND_DIR="${BASE_DIR}/g2bmaster-frontend"

PID_FILE="/tmp/g2bmaster_service_pids.txt"
LOG_DIR="${LOG_DIR:-/tmp/g2bmaster-logs}"
LAST_SERVICE_PID=""

AI_PORT="${AI_PORT:-8000}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
AI_BASE_URL="http://localhost:${AI_PORT}"

# ── 1) 두 저장소가 공유하는 호출자 인증 ──────────────────────────────────────
# AI 쪽 미들웨어(app/main.py service_secret_guard)가 이 값을 보고, 백엔드
# AiClientConfig 가 X-Internal-Secret 헤더로 같은 값을 보낸다. 한쪽만 설정하면
# AI 표면 11개가 전부 401 이 되므로 **한 변수에서 양쪽으로 내려보낸다.**
# 비워 두면 양쪽 다 인증을 쓰지 않는다(로컬 개발 기본값).
AI_SERVICE_SECRET="${AI_SERVICE_SECRET:-}"

# ── 2) 데드라인 순서 ─────────────────────────────────────────────────────────
# 뒤집히면 백엔드가 먼저 포기한 분석 작업을 리스 만료 후 다른 워커가 다시 집어
# 같은 공고를 두 번 추론한다 — LLM 비용이 두 배로 난다.
LLM_TIMEOUT_SECONDS="${LLM_TIMEOUT_SECONDS:-100}"      # AI 자체 데드라인(초)
AI_TIMEOUT_MS="${AI_TIMEOUT_MS:-120000}"               # 백엔드가 AI 를 기다리는 시간
ANALYSIS_LEASE_MS="${ANALYSIS_LEASE_MS:-300000}"       # 분석 작업 리스

if (( LLM_TIMEOUT_SECONDS * 1000 >= AI_TIMEOUT_MS )) || (( AI_TIMEOUT_MS >= ANALYSIS_LEASE_MS )); then
    echo "설정 오류: LLM_TIMEOUT_SECONDS(${LLM_TIMEOUT_SECONDS}초) < AI_TIMEOUT_MS(${AI_TIMEOUT_MS}ms) < ANALYSIS_LEASE_MS(${ANALYSIS_LEASE_MS}ms) 여야 합니다." >&2
    exit 1
fi

# ── 3) 백엔드 빌드용 JDK ─────────────────────────────────────────────────────
# pom.xml 의 java.version 이 25 다. mvn 이 잡는 기본 JDK 가 21 이면
# "release version 25 not supported" 로 컴파일 단계에서 죽는다.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
    for candidate in "${HOME}/.local/share/jdk-25"* /usr/lib/jvm/java-25-openjdk-* /usr/lib/jvm/jdk-25*; do
        if [[ -x "${candidate}/bin/javac" ]]; then
            export JAVA_HOME="${candidate}"
            break
        fi
    done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
    echo "Java 25 JDK 를 찾지 못했습니다. JAVA_HOME 을 직접 지정하세요 (JRE 가 아니라 javac 가 있는 JDK 여야 합니다)." >&2
    exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME} ($(javac -version 2>&1))"

mkdir -p "${BASE_DIR}" "${LOG_DIR}"

# 지난 실행이 남긴 pid. 비우기 **전에** 읽어 둔다 — 강제 재시작이면 이것부터 내린다.
# 포트를 안 쥔 래퍼(mvnw·npm)는 포트 검사에 걸리지 않아 여기서만 잡힌다.
declare -a PREVIOUS_PIDS=()
if [[ -s "${PID_FILE}" ]]; then
    mapfile -t PREVIOUS_PIDS < "${PID_FILE}"
fi
: > "${PID_FILE}"   # 지난 실행의 PID 가 남아 있으면 엉뚱한 프로세스를 죽이게 된다

clone_if_missing() {
    local repo_url=$1
    local target_dir=$2
    if [[ ! -d "${target_dir}" ]]; then
        echo "Cloning ${repo_url} into ${target_dir}"
        git clone "${repo_url}" "${target_dir}"
    else
        echo "Repository already exists at ${target_dir}, pulling latest changes"
        # 로컬 수정이 있으면 pull 이 실패한다. 그것 때문에 기동 전체를 멈추지는 않는다.
        (cd "${target_dir}" && git pull) || echo "  (pull 실패 — 현재 작업본으로 계속합니다)"
    fi
}

echo "=== Setting up repositories ==="
clone_if_missing "https://github.com/Electerior/g2bmaster-ai.git" "${AI_DIR}"
clone_if_missing "https://github.com/Electerior/g2bmaster-backend.git" "${BACKEND_DIR}"
clone_if_missing "https://github.com/Electerior/g2bmaster-frontend.git" "${FRONTEND_DIR}"

# 서비스마다 서브셸에서 돌린다. 예전에는 같은 셸에서 export 했기 때문에 AI 용 환경변수가
# 뒤의 백엔드·프론트에까지 그대로 새어 들어갔다.
start_service() {
    local service_name=$1
    local service_dir=$2
    local setup_cmd=$3
    local start_cmd=$4
    shift 4
    local env_vars=("$@")

    echo "=== Setting up ${service_name} ==="
    if [[ -n "${setup_cmd}" ]]; then
        echo "Running setup: ${setup_cmd}"
        (cd "${service_dir}" && eval "${setup_cmd}")
    fi

    echo "=== Starting ${service_name} ==="
    # 로그를 파일로 가른다. 셋을 한 터미널에 흘리면 스프링 배너와 vite 와 uvicorn 이
    # 뒤엉켜, 정작 중요한 "address already in use" 한 줄이 그 사이에 묻힌다.
    # exec 으로 서브셸을 실제 프로세스로 갈아 끼운다. 그냥 서브셸 안에서 실행하면 $! 가
    # 서브셸을 가리켜, 나중에 kill 해도 정작 java·node 는 살아남는다.
    (
        cd "${service_dir}"
        if (( ${#env_vars[@]} )); then
            export "${env_vars[@]}"
        fi
        exec bash -c "${start_cmd}" > "${LOG_DIR}/${service_name}.log" 2>&1
    ) &
    local pid=$!
    # 호출부가 헬스체크에 쓴다. 함수 밖에서 $! 를 다시 읽는 방식은 그 사이에 다른
    # 백그라운드 작업이 하나만 끼어도 엉뚱한 pid 를 집는다.
    LAST_SERVICE_PID="${pid}"
    echo "${service_name} started with PID ${pid} (로그: ${LOG_DIR}/${service_name}.log)"
    echo "${pid}" >> "${PID_FILE}"
}

# 포트가 이미 물려 있으면 여기서 멈춘다.
#
# 예전에는 그냥 띄웠는데, 그러면 새 프로세스는 "address already in use" 로 조용히 죽고
# 아래 헬스체크는 **먼저 떠 있던 남의 프로세스**에게서 200 을 받아 "up" 이라고 보고했다.
# 스크립트는 "All services started" 를 찍고 끝나지만 실제로 우리 것은 하나도 안 떠 있다.
# 다른 작업 트리에서 돌던 스택이 같은 포트를 쓰고 있을 때 실제로 이렇게 됐다.
# 포트를 듣고 있는 pid 들. mvnw 는 java 를 **자식으로 포크**하므로 우리가 띄운 래퍼 pid 를
# 죽여도 정작 포트를 쥔 프로세스는 살아남는다 — 그래서 pid 파일이 아니라 포트에서 찾는다.
holders_of_port() {
    ss -ltnp 2>/dev/null \
        | awk -v p=":$1\$" '$4 ~ p' \
        | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u
}

describe_pid() {
    local pid=$1 cmd cwd
    cmd=$(tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null | cut -c1-90)
    cwd=$(readlink -f "/proc/${pid}/cwd" 2>/dev/null)
    echo "pid=${pid} cwd=${cwd:-읽을 수 없음} :: ${cmd:-알 수 없음}"
}

#: 이 프로세스가 우리 작업 트리(BASE_DIR)에서 뜬 것인가. cwd 를 못 읽으면(다른 사용자 소유)
#: 우리 것이 아니라고 본다 — 모르는 것은 남의 것으로 취급하는 편이 안전하다.
is_ours() {
    local cwd
    cwd=$(readlink -f "/proc/$1/cwd" 2>/dev/null) || return 1
    [[ -n "${cwd}" && "${cwd}" == "${BASE_DIR}"* ]]
}

# SIGTERM 으로 먼저 부탁하고, 10초를 기다린 뒤에야 SIGKILL 한다.
# 스프링은 종료 훅에서 커넥션 풀을 정리하고, vite 는 캐시를 쓴다 — 바로 -9 하면 그걸 건너뛴다.
stop_pid() {
    local pid=$1
    kill "${pid}" 2>/dev/null || return 0
    for _ in $(seq 20); do
        kill -0 "${pid}" 2>/dev/null || { echo "    내려감 (pid ${pid})"; return 0; }
        sleep 0.5
    done
    echo "    SIGTERM 에 반응하지 않아 SIGKILL 합니다 (pid ${pid})"
    kill -9 "${pid}" 2>/dev/null || true
    sleep 1
}

# 포트 처리는 **두 단계**다. 먼저 셋을 전부 살펴 내릴 수 있는지 판단하고(check_port),
# 전부 통과했을 때에만 실제로 내린다(free_planned_ports).
#
# 한 포트씩 "보고 바로 죽이기"를 하면, 첫 포트를 죽인 뒤 두 번째에서 거부당해 중단되는
# 순간 **아무것도 안 뜬 채로 하나만 죽어 있는 상태**가 된다. 실제로 그렇게 됐다 —
# 우리 AI 를 내리고 나서 남의 백엔드에 막혀 멈췄고, 결과적으로 멀쩡하던 AI 만 사라졌다.
declare -a PIDS_TO_FREE=()
#: 정리 후 '비어 있어야 하는' 포트. SKIP 한 포트는 여기 들어가지 않는다 —
#: 그대로 쓰겠다고 한 포트가 비기를 기다리면 영원히 실패한다.
declare -a PORTS_TO_VERIFY=()

check_port() {
    local key=$1 name=$2 port=$3
    local skip="SKIP_${key}"
    if [[ "${!skip:-}" == "1" ]]; then
        echo "${name}: ${skip}=1 — 이미 떠 있는 것을 그대로 씁니다(포트 ${port})."
        return 0
    fi
    PORTS_TO_VERIFY+=("${port}")

    local pids
    mapfile -t pids < <(holders_of_port "${port}")
    (( ${#pids[@]} )) || return 0

    if [[ -z "${FORCE_RESTART}" ]]; then
        echo "포트 ${port} 이(가) 이미 사용 중입니다 — ${name} 을(를) 띄울 수 없습니다." >&2
        for pid in "${pids[@]}"; do
            echo "  점유: $(describe_pid "${pid}")" >&2
        done
        echo "  내리고 다시 띄우려면 --force, 그대로 쓰려면 ${skip}=1" >&2
        return 1
    fi

    # 남의 작업 트리에서 뜬 것은 --force 로 죽이지 않는다. 포트가 같다는 이유만으로
    # 다른 사람이 돌리던 스택을 말없이 내리면, 그쪽에서는 원인 모를 중단이 된다.
    # 이 환경에서 실제로 /home/user/hanbin5 의 스택이 같은 세 포트를 쓰고 있었다.
    for pid in "${pids[@]}"; do
        if ! is_ours "${pid}" && [[ "${FORCE_RESTART}" != "all" ]]; then
            echo "포트 ${port} 을(를) 잡고 있는 것이 이 작업 트리(${BASE_DIR})의 프로세스가 아닙니다." >&2
            echo "  $(describe_pid "${pid}")" >&2
            echo "  그래도 내리려면 --force-all, 건드리지 않으려면 ${skip}=1" >&2
            return 1
        fi
    done

    PIDS_TO_FREE+=("${pids[@]}")
    return 0
}

free_planned_ports() {
    (( ${#PIDS_TO_FREE[@]} )) || return 0
    echo "=== 이미 떠 있는 프로세스 정리 ==="
    for pid in "${PIDS_TO_FREE[@]}"; do
        kill -0 "${pid}" 2>/dev/null || continue
        echo "  내림: $(describe_pid "${pid}")"
        stop_pid "${pid}"
    done

    # 정말로 비었는지 확인한다. 여기서 안 비면 다음 서비스가 또 조용히 죽는다.
    local port pids
    for port in ${PORTS_TO_VERIFY[@]+"${PORTS_TO_VERIFY[@]}"}; do
        mapfile -t pids < <(holders_of_port "${port}")
        if (( ${#pids[@]} )); then
            echo "포트 ${port} 이(가) 아직 비지 않았습니다: ${pids[*]}" >&2
            return 1
        fi
    done
    return 0
}

#: SKIP_AI=1 처럼 지정하면 그 서비스는 띄우지 않는다. 이미 떠 있는 것에 붙일 때 쓴다.
skipped() {
    local skip="SKIP_$1"
    [[ "${!skip:-}" == "1" ]]
}

# HTTP 로 살아났는지 확인한다. 백엔드가 AI 보다 먼저 뜨면 첫 분석 요청이 연결 실패로 떨어진다.
# **우리가 띄운 프로세스가 살아 있는지 함께 본다** — 포트만 보면 남의 프로세스를 우리 것으로 착각한다.
wait_for_http() {
    local name=$1 url=$2 pid=$3 attempts=${4:-60}
    echo -n "Waiting for ${name} (${url}) "
    for ((i = 0; i < attempts; i++)); do
        if ! kill -0 "${pid}" 2>/dev/null; then
            echo " — 프로세스가 종료됐습니다. ${LOG_DIR}/${name}.log 를 확인하세요."
            return 1
        fi
        if curl -fsS --max-time 2 "${url}" > /dev/null 2>&1; then
            echo "— up (pid ${pid})"
            return 0
        fi
        echo -n "."
        sleep 1
    done
    echo " — 응답 없음. ${LOG_DIR}/${name}.log 를 확인하세요."
    return 1
}

# ── MySQL ────────────────────────────────────────────────────────────────────
# 순서가 중요하다: (1) DB 를 준비하고 → (2) 백엔드에 넘길 자격증명을 모으고 →
# (3) 백엔드가 붙을 바로 그 자리에 미리 붙어 본다. 셋을 뒤섞으면 "여기선 되는데
# 백엔드는 죽는" 상태가 숨는다.

# (1) DB 준비 — 기본은 compose MySQL(3307).
# 이 환경의 시스템 MySQL(3306)은 비밀번호를 모르고 sudo 로 고칠 권한도 없어서,
# 우리가 통제하는 인스턴스를 docker-compose.yml 로 띄운다.
# USE_DOCKER_DB=0 이거나 MYSQL_HOST 를 직접 주면 compose 를 건너뛰고 그 DB 를 쓴다.
COMPOSE_FILE="${BASE_DIR}/docker-compose.yml"
use_compose_db=0
if [[ "${USE_DOCKER_DB:-1}" == "1" && -z "${MYSQL_HOST:-}" && -f "${COMPOSE_FILE}" ]] && ! skipped BACKEND; then
    use_compose_db=1
fi

if skipped BACKEND; then
    :   # 백엔드를 안 띄우면 DB 도 필요 없다
elif (( use_compose_db )); then
    if ! docker compose version > /dev/null 2>&1; then
        echo "docker compose 를 찾지 못했습니다 — compose DB 를 띄울 수 없습니다." >&2
        echo "  MYSQL_HOST/PORT/USER/PASSWORD 로 외부 DB 를 주거나 USE_DOCKER_DB=0 으로 끄세요." >&2
        exit 1
    fi
    echo "=== compose MySQL 기동 (docker-compose.yml) ==="
    # 이미 건강하면 up 은 아무 일도 하지 않는다 — 매번 띄워도 안전하다.
    ( cd "${BASE_DIR}" && docker compose up -d db ) || { echo "compose MySQL 기동 실패." >&2; exit 1; }

    # 백엔드는 이 값으로 붙는다. compose 파일의 기본값과 같은 자리다.
    export MYSQL_HOST="127.0.0.1"
    export MYSQL_PORT="${MYSQL_PORT:-3307}"
    export MYSQL_USER="${MYSQL_USER:-g2b}"
    export MYSQL_PASSWORD="${MYSQL_PASSWORD:-g2b}"
    export MYSQL_DATABASE="${MYSQL_DATABASE:-g2b}"

    # 초기화 중인 MySQL 에 백엔드가 붙으면 Flyway 가 연결 거부로 죽는다. 헬스체크를 기다린다.
    echo -n "  MySQL 준비 대기 "
    for _ in $(seq 40); do
        state=$(docker inspect -f '{{.State.Health.Status}}' g2b-mysql 2>/dev/null || echo unknown)
        [[ "${state}" == "healthy" ]] && { echo "— ready"; break; }
        echo -n "."
        sleep 2
    done
else
    # 외부/시스템 MySQL 경로. compose 를 끄고 직접 DB 를 지정했을 때만 온다.
    echo "=== Checking MySQL (${MYSQL_HOST:-localhost}) ==="
    if ! mysqladmin ping -h"${MYSQL_HOST:-localhost}" --silent 2>/dev/null; then
        echo "MySQL 이 응답하지 않습니다. 서비스 시작을 시도합니다..."
        if command -v systemctl &> /dev/null; then sudo systemctl start mysql
        elif command -v service &> /dev/null; then sudo service mysql start
        else echo "MySQL 을 자동으로 시작하지 못했습니다. 직접 띄워 주세요." >&2; exit 1; fi
        sleep 5
    fi
fi

# (2) 백엔드로 넘길 자격증명을 모은다 — compose 가 export 한 것까지 여기서 한 번에 잡힌다.
# 기본값을 여기서 만들지 않는다: 설정된 것만 넘기고 나머지는 백엔드 application.yml 에 맡긴다.
# 빈 기본값을 넘기면 "설정 안 함"과 "빈 값으로 붙어라"가 구분되지 않는다.
mysql_env=()
for name in MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DATABASE; do
    [[ -n "${!name:-}" ]] && mysql_env+=("${name}=${!name}")
done

# (3) 접속을 **먼저** 확인한다.
db_user="${MYSQL_USER:-g2b}"
db_name="${MYSQL_DATABASE:-g2b}"
# 백엔드가 붙는 바로 그 호스트·포트로 확인한다. 여기서 -h/-P 를 빠뜨리면 클라이언트는
# 로컬 소켓(대개 시스템 MySQL 3306)으로 붙어, 백엔드가 쓸 인스턴스와 다른 것을 검사한다 —
# 3307 에 compose DB 를 띄운 경우 "여기선 되는데 백엔드는 죽는"(혹은 그 반대)이 된다.
# 백엔드가 붙는 바로 그 호스트·포트로 확인한다. 여기서 -h/-P 를 빠뜨리면 클라이언트는
# 로컬 소켓(대개 시스템 MySQL 3306)으로 붙어, 백엔드가 쓸 인스턴스와 다른 것을 검사한다 —
# Docker 등으로 3307 에 띄운 경우 "여기선 되는데 백엔드는 죽는"(혹은 그 반대) 상태가 된다.
db_host="${MYSQL_HOST:-127.0.0.1}"
db_port="${MYSQL_PORT:-3306}"

if skipped BACKEND; then
    echo "=== MySQL 확인 건너뜀 (SKIP_BACKEND=1) ==="
elif ! command -v mysql > /dev/null; then
    echo "=== mysql 클라이언트가 없어 접속 확인을 건너뜁니다 ==="
else
    echo "=== MySQL 접속 확인 (${db_user}@${db_host}:${db_port}, db=${db_name}) ==="
    if mysql_error=$(MYSQL_PWD="${MYSQL_PASSWORD:-}" mysql -h "${db_host}" -P "${db_port}" --protocol=TCP -u "${db_user}" \
            -e "CREATE DATABASE IF NOT EXISTS ${db_name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>&1); then
        echo "  접속 OK"
    else
        echo >&2
        echo "MySQL 에 붙지 못했습니다 — 백엔드도 같은 이유로 기동에 실패합니다." >&2
        echo "  ${mysql_error}" >&2
        echo >&2
        if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
            echo "  MYSQL_PASSWORD 가 비어 있습니다. 계정에 비밀번호가 있다면 그것이 원인입니다:" >&2
            echo "    MYSQL_PASSWORD='...' bash start_all.sh --force" >&2
            echo "  매번 치기 번거로우면 ~/.bashrc 에 export 해 두세요." >&2
        else
            echo "  MYSQL_USER/MYSQL_PASSWORD/MYSQL_DATABASE 를 확인하세요." >&2
        fi
        echo >&2
        echo "  백엔드 없이 AI·프론트만 띄우려면: SKIP_BACKEND=1 bash start_all.sh" >&2
        exit 1
    fi
fi

# ── 포트 선점 검사 ───────────────────────────────────────────────────────────
# 셋을 한꺼번에 본다. 하나씩 보다가 두 번째에서 멈추면 첫 번째는 이미 떠 버려서
# 반만 살아 있는 상태가 된다 — 그 상태가 가장 헷갈린다.
port_conflict=0
check_port AI       "g2bmaster-ai"       "${AI_PORT}"       || port_conflict=1
check_port BACKEND  "g2bmaster-backend"  "${BACKEND_PORT}"  || port_conflict=1
check_port FRONTEND "g2bmaster-frontend" "${FRONTEND_PORT}" || port_conflict=1
if (( port_conflict )); then
    echo >&2
    echo "아무것도 내리지 않고 멈춥니다 — 위 안내를 보고 --force / --force-all / SKIP_* 중에 고르세요." >&2
    exit 1
fi

# 강제 재시작이면 지난 실행의 래퍼도 함께 내린다. 포트를 직접 쥐지 않는 mvnw·npm 은
# 포트 검사에 걸리지 않으므로, 그냥 두면 고아가 되어 로그만 계속 쓴다.
#
# **단, SKIP_* 가 하나라도 있으면 이 정리를 건너뛴다.** 지난 PID 파일은 서비스 구분이 없어,
# 여기서 전부 kill 하면 SKIP 해서 살려 두려던 서비스(예: 이미 떠 있는 AI)까지 죽는다 —
# 백엔드만 재기동하려다 AI 를 함께 내리는 사고가 실제로 났다. 일부만 재기동할 때는
# 포트 정리(free_planned_ports)에만 맡기고, 지난 PID 일괄 정리는 전체 재기동일 때만 한다.
if [[ -n "${FORCE_RESTART}" ]] && ! skipped AI && ! skipped BACKEND && ! skipped FRONTEND; then
    for pid in ${PREVIOUS_PIDS[@]+"${PREVIOUS_PIDS[@]}"}; do
        [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" 2>/dev/null && PIDS_TO_FREE+=("${pid}")
    done
fi
free_planned_ports || exit 1

# ── LLM 워커 (4090 4장) — 아직 구현 안 함, 방향만 메모 ────────────────────────
# 목표: 같은 모델을 카드마다 하나씩 올려 4병렬로 돌린다(현재는 1모델이 4장에 분산돼 parallel=1).
#
# 실험으로 확인한 제약(이 환경, LM Studio):
#   - lms 는 **단일 백엔드 데몬**이다. `lms server start --port` 는 새 서버를 띄우는 게 아니라
#     그 데몬의 리스닝 포트를 옮길 뿐이다(1234→1236 이동 확인). GPU당 별도 포트 서버 불가.
#   - `lms load` 는 GPU **index 지정이 안 된다**(--gpu 는 offload 비율일 뿐). 어느 카드에
#     앉을지 강제할 수 없다.
# 따라서 우리 워커 풀(포트 단위 분산)과 lms 를 그대로는 4:1 로 못 붙인다.
#
# 채택 예정(미구현): **GPU당 별도 추론 서버**를 CUDA_VISIBLE_DEVICES 로 격리해 4개 띄운다.
# llama.cpp llama-server 나 vLLM 이 이 방식을 지원하고, 포트 분산이라 워커 풀을 그대로 쓴다.
#
#   for i in 0 1 2 3; do
#     CUDA_VISIBLE_DEVICES=$i llama-server -m "$LLM_MODEL_GGUF" --host 127.0.0.1 \
#       --port $((1234 + i)) --n-gpu-layers 999 &   # 이전 세션은 이 함수 진입에서 pkill 로 정리
#   done
#   export LLM_WORKERS="http://127.0.0.1:1234@1,http://127.0.0.1:1235@1,http://127.0.0.1:1236@1,http://127.0.0.1:1237@1"
#
# AI 서비스는 위 LLM_WORKERS 만 읽으면 되고(app/llm/worker_pool.py), 배치는 자동으로
# 네 대에 흩뿌려진다. 읽기-타임아웃 재시도는 기본 꺼져 있어 중복 생성이 없다(LLM_RETRY_ON_TIMEOUT).
# 이전 세션 정리: `pkill -f 'llama-server.*--port 123[4-7]'` 를 이 블록 맨 앞에 둔다.
#
# 구현은 GGUF 모델 경로·추론 서버 바이너리가 정해진 뒤에 한다(사용자 결정 대기).

# ── AI ───────────────────────────────────────────────────────────────────────
# 임베딩(/api/embed)과 module_a/module_b 까지 쓰려면 AI_INSTALL="make install-ml".
# 없어도 서비스는 정상으로 뜨고 임베딩 경로만 503 을 준다.
if ! skipped AI; then
    start_service "g2bmaster-ai" "${AI_DIR}" "${AI_INSTALL:-make install}" "make start" \
        "PORT=${AI_PORT}" \
        "AI_SERVICE_SECRET=${AI_SERVICE_SECRET}" \
        "LLM_TIMEOUT_SECONDS=${LLM_TIMEOUT_SECONDS}"
    wait_for_http "g2bmaster-ai" "${AI_BASE_URL}/healthz" "${LAST_SERVICE_PID}" || true
fi

# ── Backend ──────────────────────────────────────────────────────────────────
# APP_API_KEY 는 일부러 비워 둔다. 켜면 @RequireAppAuth 가 붙은 경로(/api/saved-notices
# 전체, /api/analysis-jobs/status, /api/system 쓰기, /api/search/notices/sync)가 401 이
# 되는데, 프론트 lib/apiClient.ts 에 인증 헤더 배선이 아직 없어 저장 공고 화면이 죽는다.
#
# ANALYSIS_RUNNER_ENABLED 도 기본 꺼짐이다. item-summary 가 아직 501 NOT_PORTED 라
# 켜 봐야 작업이 실패로 쌓이기만 한다(g2bmaster-AI/PORTING_STATUS.md).
if ! skipped BACKEND; then
    start_service "g2bmaster-backend" "${BACKEND_DIR}" "" "./mvnw spring-boot:run" \
        "AI_ENABLED=true" \
        "AI_BASE_URL=${AI_BASE_URL}" \
        "AI_TIMEOUT_MS=${AI_TIMEOUT_MS}" \
        "AI_SERVICE_SECRET=${AI_SERVICE_SECRET}" \
        "ANALYSIS_LEASE_MS=${ANALYSIS_LEASE_MS}" \
        "ANALYSIS_RUNNER_ENABLED=${ANALYSIS_RUNNER_ENABLED:-false}" \
        "CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS:-http://localhost:${FRONTEND_PORT}}" \
        ${mysql_env[@]+"${mysql_env[@]}"}
    wait_for_http "g2bmaster-backend" "http://localhost:${BACKEND_PORT}/healthz" "${LAST_SERVICE_PID}" 120 || true
fi

# ── Frontend ─────────────────────────────────────────────────────────────────
# package.json 이 node>=20 을 요구한다. 18 에서도 vite 는 뜨지만 EBADENGINE 경고가
# 무더기로 나오고, 그 안에 진짜 오류가 섞이면 알아보기 어렵다.
node_major=$(node -v 2>/dev/null | sed 's/^v\([0-9]*\).*/\1/')
if [[ -n "${node_major}" ]] && (( node_major < 20 )); then
    echo "경고: node $(node -v) 입니다 — 프론트는 node>=20 을 요구합니다(EBADENGINE)." >&2
    echo "  nvm 등으로 20 이상을 잡은 뒤 다시 실행하는 편이 좋습니다." >&2
fi

if ! skipped FRONTEND; then
    start_service "g2bmaster-frontend" "${FRONTEND_DIR}" "npm install" "npm run dev" \
        "VITE_PROXY_TARGET=http://localhost:${BACKEND_PORT}"
    # 프론트도 기다린다. 예전에는 여기서 바로 아래 상태표로 내려가는 바람에, 200ms 뒤에
    # 멀쩡히 뜨는 vite 를 "응답 없음"으로 찍었다 — 거짓 실패도 거짓 성공만큼 나쁘다.
    # dev 서버에는 헬스 경로가 없으므로 루트가 200 이면 뜬 것으로 본다.
    wait_for_http "g2bmaster-frontend" "http://localhost:${FRONTEND_PORT}/" "${LAST_SERVICE_PID}" || true
fi

# ── 최종 상태 ────────────────────────────────────────────────────────────────
# **뜬 것과 못 뜬 것을 나눠 적는다.** 예전에는 헬스체크가 실패해도 그 아래에
# "All services started" 를 그대로 찍었다. 백엔드가 MySQL 인증에 막혀 죽었는데도
# 성공처럼 끝나서, 사용자는 화면에서 "Request failed with status code 500"(vite 프록시가
# ECONNREFUSED 를 500 으로 바꾼 것)만 보고 원인을 여기서 찾을 수 없었다.
echo
echo "=== 기동 결과 ==="
report() {
    local key=$1 name=$2 url=$3 label=$4
    if curl -fsS --max-time 3 "${url}" > /dev/null 2>&1; then
        # SKIP 한 것이 응답한다면 그것은 우리가 띄운 것이 아니다 — 그 사실을 적어 둔다.
        if skipped "${key}"; then
            printf '  ✅ %-18s %s  (SKIP_%s=1 — 원래 떠 있던 것)\n' "${label}" "${url%/healthz}" "${key}"
        else
            printf '  ✅ %-18s %s\n' "${label}" "${url%/healthz}"
        fi
        return
    fi
    # 일부러 띄우지 않은 것을 실패로 세면, 진짜 실패가 그 사이에 묻힌다.
    if skipped "${key}"; then
        printf '  ⏭  %-18s 띄우지 않음 (SKIP_%s=1)\n' "${label}" "${key}"
        return
    fi
    printf '  ❌ %-18s 응답 없음 — %s/%s.log\n' "${label}" "${LOG_DIR}" "${name}"
    STACK_HEALTHY=0
}

STACK_HEALTHY=1
report AI       g2bmaster-ai       "${AI_BASE_URL}/healthz"                   "AI"
report BACKEND  g2bmaster-backend  "http://localhost:${BACKEND_PORT}/healthz" "Backend"
# 프론트는 헬스 경로가 없다. dev 서버는 루트가 200 이면 뜬 것이다.
report FRONTEND g2bmaster-frontend "http://localhost:${FRONTEND_PORT}/"       "Frontend"

echo
echo "인증:   AI_SERVICE_SECRET=$([[ -n "${AI_SERVICE_SECRET}" ]] && echo '설정됨(양쪽 공유)' || echo '없음(개발 모드)')"
echo "데드라인: LLM ${LLM_TIMEOUT_SECONDS}초 < 백엔드 ${AI_TIMEOUT_MS}ms < 리스 ${ANALYSIS_LEASE_MS}ms"
echo "로그:   ${LOG_DIR}/"
echo "종료:   kill \$(cat ${PID_FILE})"

if (( ! STACK_HEALTHY )); then
    echo
    echo "일부 서비스가 뜨지 않았습니다. 위 로그를 확인하세요." >&2
    echo "  백엔드가 'Access denied for user ...' 로 죽었다면 MySQL 자격증명 문제입니다:" >&2
    echo "  MYSQL_USER=g2b MYSQL_PASSWORD=... bash start_all.sh --force" >&2
    echo >&2
    echo "  ※ 백엔드가 죽어 있으면 화면에는 'Request failed with status code 500' 만 뜹니다 —" >&2
    echo "     vite 프록시가 연결 거부를 500 으로 바꾸기 때문이라, 원인은 여기 로그에 있습니다." >&2
fi

# 서비스 하나가 죽으면 나머지도 함께 내린다 — 반만 살아 있는 상태가 가장 헷갈린다.
trap 'kill $(cat "${PID_FILE}") 2>/dev/null || true' INT TERM
wait

#!/usr/bin/env bash
#
# 로컬 self-signed 인증서를 만든다 (Task 039_1).
#
# 왜 필요한가: HTTP/2를 브라우저로 재려면 TLS가 반드시 끼어야 한다. 브라우저는 평문
# HTTP/2(h2c)를 지원하지 않아서 `listen 80 http2;`로는 Lighthouse 측정이 성립하지 않는다.
# 그 TLS는 클라우드 배포(Task 034)를 기다릴 필요 없이 여기서 세울 수 있다.
#
# 산출물은 커밋하지 않는다(.gitignore). 기계마다 이 스크립트를 한 번 돌리면 된다.
#
#   bash nginx/generate-local-cert.sh
#
# 이 인증서는 **운영용이 아니다.** 체인도 OCSP도 없고 브라우저가 경고를 띄운다.
# 넘기는 방법이 클라이언트마다 다르다:
#
#   Lighthouse (Chrome)  LH_CHROME_EXTRA=--ignore-certificate-errors
#   Playwright (브라우저)  playwright.config.ts의 use.ignoreHTTPSErrors
#   Node 전역 fetch       NODE_EXTRA_CA_CERTS=./nginx/certs/localhost-cert.pem
#
# 마지막 줄 때문에 아래에서 CA:TRUE로 만든다 — 자기 자신이 신뢰 앵커가 될 수 있어야
# NODE_EXTRA_CA_CERTS로 **검증을 끄지 않고** 통과시킬 수 있다. e2e/perf 중
# stream-markers.spec.ts만 브라우저가 아니라 Node fetch로 스트림을 읽어서 이 경로가 필요하다.
set -euo pipefail

CERT_DIR="$(cd "$(dirname "$0")" && pwd)/certs"
CERT="$CERT_DIR/localhost-cert.pem"
KEY="$CERT_DIR/localhost-key.pem"
CONF="$CERT_DIR/openssl.cnf"
DAYS=825

mkdir -p "$CERT_DIR"

if [ -f "$CERT" ] && [ -f "$KEY" ] && [ "${FORCE:-}" != "1" ]; then
  echo "이미 있다: $CERT"
  echo "다시 만들려면 FORCE=1 bash nginx/generate-local-cert.sh"
  exit 0
fi

# `-subj "/CN=localhost"`를 쓰지 않고 설정 파일로 가는 이유 (Windows 함정 2단):
#
#   1) Git Bash의 MSYS는 `/CN=localhost`를 경로로 보고 `C:/Program Files/Git/CN=localhost`로
#      바꿔 버린다. openssl은 그걸 subject로 못 읽는다.
#   2) 그래서 `MSYS_NO_PATHCONV=1`을 붙이면 **`-keyout`/`-out` 경로 변환까지 같이 꺼진다.**
#      native openssl이 `/c/Users/...` 형식을 못 열어서 "No such file or directory"로 죽는다.
#      실제로 이 순서로 밟았다.
#
# 설정 파일에는 경로도 슬래시 인자도 없어서 두 문제를 동시에 비킨다. 리눅스/맥에서도 같다.
#
# SAN이 없으면 Chrome이 거부한다 — CN만 있는 인증서는 Chrome 58부터 안 받는다.
#
# CA:TRUE + keyCertSign은 서버 인증서로는 드문 조합인데, **자기 자신을 신뢰 앵커로 쓰려면**
# 필요하다(체인 길이 1). CA:FALSE로 만들면 NODE_EXTRA_CA_CERTS에 넣어도 OpenSSL이
# 신뢰 앵커로 받아주지 않아 Node fetch가 계속 거부한다. 로컬 전용이라 이 타협을 한다.
# IPv6 ::1까지 넣는 이유는 compose의 nginx healthcheck가 밟은 자리와 같다
# (docker-compose.prod.yml 주석 — busybox wget이 `::1 localhost`를 먼저 시도한다).
cat > "$CONF" <<'EOF'
[req]
default_bits       = 2048
default_md         = sha256
prompt             = no
distinguished_name = dn
x509_extensions    = v3_req

[dn]
CN = localhost

[v3_req]
subjectAltName   = @alt_names
basicConstraints = critical,CA:TRUE
keyUsage         = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = localhost
IP.1  = 127.0.0.1
IP.2  = ::1
EOF

openssl req -x509 \
  -newkey rsa:2048 \
  -nodes \
  -days "$DAYS" \
  -config "$CONF" \
  -keyout "$KEY" \
  -out "$CERT"

chmod 600 "$KEY"

echo "만들었다:"
echo "  $CERT"
echo "  $KEY"
echo
openssl x509 -in "$CERT" -noout -subject -dates -ext subjectAltName

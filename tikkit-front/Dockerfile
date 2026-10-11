# syntax=docker/dockerfile:1

# CI의 frontend 잡과 같은 Node 22로 맞춘다.
# alpine이 아니라 slim(Debian)을 고른 이유: tailwindcss v4(@tailwindcss/oxide)와 lightningcss가
# 네이티브 바이너리를 쓴다. musl 빌드도 배포되지만 glibc 쪽이 변수가 없다.
# 이미지가 더 크지만 "한 번에 뜨는 것"을 선택했다. 크기 최적화는 나중에 따로.
ARG NODE_VERSION=22-bookworm-slim

# ── 1단계: 의존성 설치 ─────────────────────────────────────────────────────
FROM node:${NODE_VERSION} AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# devDependencies를 포함해 설치한다. tailwindcss / @tailwindcss/postcss / typescript가
# devDependencies에 있고 `next build`가 이들을 쓴다 — --omit=dev를 주면 빌드가 깨진다.
RUN npm ci --no-audit --no-fund

# ── 2단계: 빌드 ────────────────────────────────────────────────────────────
FROM node:${NODE_VERSION} AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NODE_ENV=production
# API_BASE_URL을 빌드 ARG로 받지 않는다: NEXT_PUBLIC_* 사용이 0건이라 번들에 인라인될 값이
# 없고, API를 쓰는 페이지는 전부 cookies()를 거쳐 동적 렌더링이라 빌드 중에 BE를 때리지 않는다.
# 덕분에 같은 이미지를 환경만 바꿔 재사용할 수 있다.
RUN npm run build

# ── 3단계: 런타임 ──────────────────────────────────────────────────────────
FROM node:${NODE_VERSION} AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000
# standalone의 server.js는 HOSTNAME을 보고 바인딩한다. 지정하지 않으면
# 컨테이너 밖에서 접속이 안 될 수 있다.
ENV HOSTNAME="0.0.0.0"

# 공식 Next 템플릿의 `COPY --from=builder /app/public ./public`은 여기서 쓸 수 없다 —
# 이 프로젝트에는 public/ 디렉터리가 없다 (파비콘은 app/favicon.ico, 폰트는 node_modules 경유).
# 그 둘은 빌드 중 .next/static/media로 emit되므로 아래 static 복사가 커버한다.

# prerender 캐시를 쓸 쓰기 가능한 .next를 미리 만든다
RUN mkdir .next && chown node:node .next

COPY --from=builder --chown=node:node /app/.next/standalone ./
# standalone 산출물에는 정적 자산이 없다. 이 줄이 빠지면 CSS·폰트·파비콘이 전부 404가 된다.
COPY --from=builder --chown=node:node /app/.next/static ./.next/static

# node 이미지에 이미 있는 비루트 계정을 쓴다 (별도 생성 불필요)
USER node
EXPOSE 3000

CMD ["node", "server.js"]

import { cookies } from "next/headers";

// BE가 로그인 성공 시 내려주는 세션 쿠키명과 그대로 맞춘다. 브라우저는 BE(8080)를 직접 호출하지 않고
// Next 서버(3000)만 호출하므로, 여기 저장하는 건 "BE 세션을 대신 들고 있는 Next 도메인 쿠키"다.
// 값(BE의 세션 ID) 자체는 BE가 매 요청마다 검증하므로, 이 쿠키가 위조되거나 만료돼도 BE가 401로 걸러낸다.
export const SESSION_COOKIE_NAME = "JSESSIONID";

// BE application.yml의 server.servlet.session.timeout(30m)과 맞춘 값이다. 둘 중 하나만 바뀌면
// Next 쿠키가 BE 세션보다 오래 남아있거나 먼저 지워지므로, 바꿀 때 서로 맞춰줘야 한다.
const SESSION_MAX_AGE_SECONDS = 30 * 60;

// Secure 플래그 (Task 039_1 — 로컬 TLS가 서면서 켤 수 있게 됐다).
//
// **이 쿠키가 브라우저가 보는 유일한 세션 쿠키다.** 로드맵은 이 항목을 BE
// `server.servlet.session.cookie.secure`로 적어 뒀는데 그 자리가 아니다 — BE의 Set-Cookie는
// lib/api/auth.ts가 정규식으로 값만 뽑고 버리므로 브라우저까지 가지 않는다. BE에 켜면
// RedisSessionAuthIntegrationTest의 Set-Cookie 어서션만 깨지고 얻는 게 없다.
//
// 기본값을 false로 두는 이유. 처음에 적었던 근거는 **반증됐으니** 그것부터 적어 둔다
// (교훈 4 — 반증된 설명을 주석에 남기면 다음 사람이 그걸 근거로 움직인다).
//
// 틀린 근거: "true로 고정하면 http로 들어오는 경로에서 쿠키가 저장되지 않아 로그인이 조용히
// 실패한다." **아니다.** Task 039_1에서 `secure: true`로 띄우고 `http://localhost`로 가입·로그인을
// 해 봤는데 쿠키가 `secure: true`로 **정상 저장되고 로그인도 된다**(e2e 3개 통과). Chrome이
// localhost를 trustworthy origin으로 취급해서 Secure 쿠키를 예외적으로 허용하기 때문이다.
// 그래서 CI(http://localhost:3000)도 하드코딩해도 안 깨진다.
//
// 그래도 토글로 남기는 이유는 둘이다.
//   1) 그 통과가 **Chrome의 localhost 예외 덕분**이고, 호스트가 localhost가 아닌 평문 주소
//      (LAN IP로 모바일 실기 확인 등 — Task 041의 모바일 n=1 항목)가 되는 순간 사라진다.
//      "로컬에서 되니까 된다"를 코드의 전제로 삼지 않는다.
//   2) Task 034(배포)에서 켤 값이 코드에 미리 있어야 한다. 그때 추가하면 그게 미검증 변경이다.
//
// 이 레포가 CACHE_TYPE·SEATMAP_STRATEGY·QUEUE_ENABLED에 쓰는 것과 같은 방식이다
// (보수적 기본값 + 환경변수).
//
// 런타임에 읽힌다는 것도 확인했다 — Next가 `process.env`를 빌드 시점에 인라인하면 컨테이너
// 환경변수가 안 먹는데, 빌드 때 없던 값을 런타임에 주고 쿠키를 읽어 `secure: true`를 봤다.
//
// 서버에서만 읽힌다. NEXT_PUBLIC_ 접두사가 없어서 브라우저 번들에 들어가지 않는다.
const SESSION_COOKIE_SECURE = process.env.SESSION_COOKIE_SECURE === "true";

export async function getSessionCookie(): Promise<string | undefined> {
  const store = await cookies();
  return store.get(SESSION_COOKIE_NAME)?.value;
}

// 쿠키 set/delete는 Server Action(또는 Route Handler)에서만 가능하다 — Server Component 렌더링 중에는 못 쓴다.
export async function setSessionCookie(value: string): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE_NAME, value, {
    httpOnly: true,
    sameSite: "lax",
    secure: SESSION_COOKIE_SECURE,
    path: "/",
    maxAge: SESSION_MAX_AGE_SECONDS,
  });
}

export async function clearSessionCookie(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE_NAME);
}

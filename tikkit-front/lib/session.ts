import { cookies } from "next/headers";

// BE가 로그인 성공 시 내려주는 세션 쿠키명과 그대로 맞춘다. 브라우저는 BE(8080)를 직접 호출하지 않고
// Next 서버(3000)만 호출하므로, 여기 저장하는 건 "BE 세션을 대신 들고 있는 Next 도메인 쿠키"다.
// 값(BE의 세션 ID) 자체는 BE가 매 요청마다 검증하므로, 이 쿠키가 위조되거나 만료돼도 BE가 401로 걸러낸다.
export const SESSION_COOKIE_NAME = "JSESSIONID";

// BE application.yml의 server.servlet.session.timeout(30m)과 맞춘 값이다. 둘 중 하나만 바뀌면
// Next 쿠키가 BE 세션보다 오래 남아있거나 먼저 지워지므로, 바꿀 때 서로 맞춰줘야 한다.
const SESSION_MAX_AGE_SECONDS = 30 * 60;

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
    path: "/",
    maxAge: SESSION_MAX_AGE_SECONDS,
  });
}

export async function clearSessionCookie(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE_NAME);
}

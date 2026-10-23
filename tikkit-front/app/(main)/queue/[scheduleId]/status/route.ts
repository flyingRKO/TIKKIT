import { ApiError } from "@/lib/api/client";
import { getQueueStatus } from "@/lib/api/queue";

/**
 * 대기열 상태 폴링 중계 (Task 032). 이 레포의 첫 route handler다.
 *
 * <h3>왜 app/api/ 가 아니라 /queue/[scheduleId]/status 인가</h3>
 * prod 스택의 nginx가 `location /api/ { proxy_pass http://tikkit_backend; }`로 접두사 전체를
 * 백엔드로 보낸다(nginx/nginx.conf). app/api/ 밑에 두면 next dev(3000 직결)에서는 돌고
 * **prod에서는 Spring이 404를 돌려준다** — 증상이 "로컬에선 됐는데 배포하면 대기열만 안
 * 움직인다"라서 nginx를 의심하기까지 오래 걸린다. 그래서 대기열 페이지의 자식 세그먼트에 둔다.
 * route.ts는 같은 세그먼트의 page.tsx와만 공존할 수 없고 자식 세그먼트는 합법이다.
 * nginx를 고치지 않는 이유: 그 파일이 접두사 단위 관리를 선언해 뒀고(Task 034 소관),
 * /api/ 안에 프론트 예외를 파는 건 그 결정을 거스른다.
 *
 * <h3>왜 브라우저가 BE를 직접 부르지 않는가</h3>
 * API_BASE_URL에 NEXT_PUBLIC_ 접두사가 없어 브라우저 번들에 들어가지 않고, BE 세션은 Next
 * 도메인의 httpOnly 쿠키라 브라우저가 8080으로 실어 보낼 수 없다. 쿠키 중계는 apiFetch가 한다.
 *
 * <h3>왜 Server Action이 아닌가</h3>
 * Server Action 응답에는 현재 라우트의 RSC 페이로드가 딸려 와서 폴링마다 페이지 트리가 다시
 * 그려지고, Next가 액션을 직렬화해 한 번에 하나만 보낸다. Cache-Control도 직접 못 건다.
 * 이 레포에서 Server Action은 "사용자 의도 + 상태 변경 + redirect"용이다 — 폴링은 셋 다 아니다.
 */
export const dynamic = "force-dynamic";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ scheduleId: string }> }
) {
  const { scheduleId } = await params;
  const id = Number(scheduleId);

  if (!Number.isInteger(id) || id <= 0) {
    return Response.json({ code: "VALIDATION_ERROR" }, { status: 400 });
  }

  try {
    const status = await getQueueStatus(id);
    // prod에 nginx가 앞에 있다. 폴링 GET이 어딘가에 캐시되면 순번이 얼어붙고,
    // 증상은 "입장이 안 된다"로만 보여서 캐시를 의심하기 어렵다.
    return Response.json(status, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (error instanceof ApiError) {
      // 바디에 code만 담는다 — BE 메시지를 중계하면 화면 문구가 BE에 묶인다. 문구는 FE가 가진다.
      const status = error.code === "UNAUTHORIZED" ? 401 : 502;
      return Response.json({ code: error.code }, { status });
    }
    throw error;
  }
}

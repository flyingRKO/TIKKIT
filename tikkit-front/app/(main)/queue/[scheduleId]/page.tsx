import { redirect } from "next/navigation";
import { QueueWaitingRoom } from "@/components/queue/queue-waiting-room";
import { ApiError } from "@/lib/api/client";
import { getPerformanceDetail } from "@/lib/api/performances";
import { enterQueue } from "@/lib/api/queue";
import { buildQueuePath, buildSeatsPath, parseQueueParams } from "@/lib/queue-rules";

type SearchParams = Record<string, string | string[] | undefined>;

/**
 * 대기열 화면 (Task 032).
 *
 * <h3>진입을 렌더 중에 한다</h3>
 * 서버 컴포넌트에서 POST .../entries를 부른다. 렌더 중 부작용이지만 ZADD NX라 멱등이고,
 * 그 대가로 DISABLED/ADMITTED면 **픽셀 하나 그리기 전에** 좌석으로 redirect할 수 있다.
 * 클라이언트에서 부르면 대기열이 아닌 회차에서도 대기 화면이 한 번 깜빡인다.
 *
 * <h3>여기로 누가 보내는가</h3>
 * "좌석 선택" CTA는 좌석 페이지를 그대로 가리킨다. 대기열 API가 전부 로그인 필수라서 CTA를
 * 여기로 돌리면 **대기열을 켜지 않은 회차에서도 로그인이 강제되고**, Task 022가 일부러 연
 * "자리 보고 가입하는 흐름"이 죽는다. 그래서 좌석 조회·예매 선점이 401/403을 받을 때만
 * 이 화면으로 우회한다.
 */
export default async function QueuePage({
  params,
  searchParams,
}: {
  params: Promise<{ scheduleId: string }>;
  searchParams: Promise<SearchParams>;
}) {
  const { scheduleId } = await params;
  const query = await searchParams;

  const parsed = parseQueueParams(scheduleId, query);
  // 값이 어긋나면 404가 아니다 — 회차 자체는 존재할 수 있고, 다시 고르면 되는 상황이다.
  // (좌석 페이지가 등급·매수 불일치에 쓰는 판단과 같다.)
  if (parsed === null) {
    const fallback = Number(query.performanceId);
    redirect(Number.isInteger(fallback) && fallback > 0 ? `/performances/${fallback}` : "/performances");
  }

  const seatsPath = buildSeatsPath(parsed);
  const queuePath = buildQueuePath(parsed);
  const loginPath = `/login?redirect=${encodeURIComponent(queuePath)}`;

  let status;
  let performance;

  try {
    // 공연 제목·회차 시각은 "무엇을 기다리는지" 보여주기 위한 부가 정보다. 실패해도 줄 서기를
    // 막지 않는다. 거부를 Promise.all 안에서 흡수해야 한다 — 아래 redirect()가 throw되면
    // 아무도 await하지 않는 거부가 남아 unhandled rejection이 된다
    // (좌석 페이지의 seatsPromise.catch(() => {})가 같은 이유로 있다).
    [status, performance] = await Promise.all([
      enterQueue(parsed.scheduleId),
      getPerformanceDetail(parsed.performanceId).catch(() => null),
    ]);
  } catch (error) {
    // 대기열 API는 전부 authenticated()라 비로그인은 401이다. 로그인 후 다시 여기로 돌아온다.
    if (error instanceof ApiError && error.code === "UNAUTHORIZED") {
      redirect(loginPath);
    }
    throw error;
  }

  // 대기열을 쓰지 않는 회차(DISABLED)와 이미 입장한 사람(ADMITTED)은 좌석으로 직행한다.
  // 분기가 하나인 이유: BE가 queue_enabled를 응답에 노출하지 않아서, FE는 "대기열 회차인가"를
  // 묻지 않고 항상 진입을 호출한 뒤 state로 판단한다.
  if (status.state === "DISABLED" || status.state === "ADMITTED") {
    redirect(seatsPath);
  }

  const schedule = performance?.schedules.find((item) => item.id === parsed.scheduleId) ?? null;

  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col px-4 pt-8 pb-16">
      <QueueWaitingRoom
        initialStatus={status}
        scheduleId={parsed.scheduleId}
        performanceId={parsed.performanceId}
        seatsPath={seatsPath}
        loginPath={loginPath}
        performanceTitle={performance?.title ?? null}
        showAt={schedule?.showAt ?? null}
      />
    </main>
  );
}

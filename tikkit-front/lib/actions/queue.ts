"use server";

import { redirect } from "next/navigation";
import { ApiError } from "@/lib/api/client";
import { leaveQueue } from "@/lib/api/queue";
import type { QueueActionState } from "@/lib/actions/queue-state";

/**
 * 대기열에서 나가기 (Task 032).
 *
 * <b>이탈은 자동으로 보내지 않는다.</b> pagehide에서 sendBeacon으로 보내면 탭을 닫을 때와
 * 새로고침할 때가 구분되지 않는다 — 새로고침이면 "이탈 → 재진입"이 되고 재진입은 ZADD NX라
 * 꼬리로 가므로, 새로고침 한 번에 순번이 맨 뒤로 간다. 화면에는 그냥 큰 숫자가 보일 뿐이라
 * 원인을 못 찾는다. BE Javadoc도 "이탈 경고에서 확인을 눌렀을 때 호출한다"로 적고 있다.
 *
 * 그래서 "사용자 의도 + 상태 변경 + redirect"인 Server Action으로 둔다 — 이 레포가
 * Server Action을 쓰는 바로 그 모양이다.
 */
export async function leaveQueueAction(
  _prevState: QueueActionState,
  formData: FormData
): Promise<QueueActionState> {
  const scheduleId = Number(formData.get("scheduleId"));
  const performanceId = Number(formData.get("performanceId"));

  if (!Number.isInteger(scheduleId) || scheduleId <= 0) {
    return { error: "잘못된 회차입니다." };
  }

  // redirect()는 내부적으로 예외를 던지므로 try/catch 안에서 부르면 안 된다.
  let redirectTo: string;

  try {
    await leaveQueue(scheduleId);
    redirectTo =
      Number.isInteger(performanceId) && performanceId > 0
        ? `/performances/${performanceId}`
        : "/performances";
  } catch (error) {
    if (error instanceof ApiError) {
      // 세션이 끊긴 채로 나가기를 눌렀다. 이미 큐에서도 의미가 없으므로 목록으로 보낸다.
      if (error.code === "UNAUTHORIZED") {
        redirect("/login");
      }
      return { error: error.message };
    }
    throw error;
  }

  redirect(redirectTo);
}

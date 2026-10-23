"use client";

import { useActionState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { FormError } from "@/components/layout/form-error";
import { Button } from "@/components/ui/button";
import { leaveQueueAction } from "@/lib/actions/queue";
import { initialQueueActionState } from "@/lib/actions/queue-state";

interface QueueLeaveButtonProps {
  scheduleId: number;
  performanceId: number;
}

/**
 * 대기열에서 나가기 (Task 032). components/reservation/cancel-reservation-button.tsx와 같은 모양이다.
 *
 * 이탈을 자동으로 보내지 않기 때문에 이 버튼이 유일한 이탈 경로다 — 근거는
 * lib/actions/queue.ts의 주석에 있다(새로고침과 탭 닫기를 구분할 수 없다).
 */
export function QueueLeaveButton({ scheduleId, performanceId }: QueueLeaveButtonProps) {
  const [state, formAction, pending] = useActionState(leaveQueueAction, initialQueueActionState);

  return (
    <AlertDialog>
      <AlertDialogTrigger
        render={<Button type="button" variant="outline" className="w-full sm:w-auto" />}
      >
        대기열에서 나가기
      </AlertDialogTrigger>
      <AlertDialogContent>
        {/* Popup의 grid 레이아웃이 깨지지 않게 form은 레이아웃에 참여하지 않도록(contents) 둔다 */}
        <form action={formAction} className="contents">
          <input type="hidden" name="scheduleId" value={scheduleId} />
          <input type="hidden" name="performanceId" value={performanceId} />
          <AlertDialogHeader>
            <AlertDialogTitle>대기열에서 나갈까요?</AlertDialogTitle>
            <AlertDialogDescription>
              지금 순번이 사라집니다. 다시 참여하면 맨 뒤에서 시작합니다.
            </AlertDialogDescription>
            <FormError message={state.error} />
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={pending}>계속 기다리기</AlertDialogCancel>
            <AlertDialogAction type="submit" variant="destructive" disabled={pending}>
              {pending ? "나가는 중..." : "나가기"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}

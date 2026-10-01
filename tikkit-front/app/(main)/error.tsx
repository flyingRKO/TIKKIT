"use client";

import Link from "next/link";
import { useEffect } from "react";
import { Button, buttonVariants } from "@/components/ui/button";

// (main) 안의 페이지에서 난 에러를 여기서 받아서 Header/Footer는 유지한다.
// 서버에서 던진 에러는 프로덕션에서 메시지·code가 지워지고 digest만 남기 때문에, 연결 문제인지 여기서 구분할 수 없다.
// 그래서 연결 실패도 포함하는 일반 문구를 쓴다.
export default function MainError({
  error,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  unstable_retry: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-4 px-6 py-24 text-center">
      <h1 className="text-2xl font-bold">문제가 발생했습니다</h1>
      <p className="max-w-md text-muted-foreground">
        서버와 연결이 불안정하거나 일시적인 오류일 수 있습니다. 잠시 후 다시 시도해 주세요.
      </p>
      <div className="flex gap-2">
        <Button onClick={() => unstable_retry()}>다시 시도</Button>
        <Link href="/" className={buttonVariants({ variant: "outline" })}>
          홈으로
        </Link>
      </div>
    </main>
  );
}

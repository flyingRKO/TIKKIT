import { Skeleton } from "@/components/ui/skeleton";

// 자리표시자 높이를 실제 화면과 억지로 맞추지 않는다. Task 030_2가 CLS 0.1996을 만들었던
// 이유가 "자리표시자가 실제보다 짧아서 푸터가 밀린 것"이었고, 해법은 높이를 맞추는 게 아니라
// 두 상태 모두에서 푸터를 접힘선 아래에 두는 것이었다. 여기도 flex-1로 영역을 채운다.
export default function Loading() {
  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-48 w-full rounded-xl" />
      <Skeleton className="h-10 w-full rounded-md sm:w-40" />
    </main>
  );
}

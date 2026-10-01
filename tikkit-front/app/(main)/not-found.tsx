import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";

// (main) 안에서 notFound()가 호출되면 Header/Footer가 있는 이 화면이 뜬다. 앱 전체 404는 app/not-found.tsx가 처리한다.
export default function MainNotFound() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-4 px-6 py-24 text-center">
      <h1 className="text-2xl font-bold">페이지를 찾을 수 없습니다</h1>
      <p className="max-w-md text-muted-foreground">
        요청하신 페이지가 존재하지 않거나 이동되었습니다.
      </p>
      <Link href="/performances" className={buttonVariants({})}>
        공연 둘러보기
      </Link>
    </main>
  );
}

export default function Home() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-4 px-6 text-center">
      <h1 className="bg-linear-to-r from-primary to-highlight bg-clip-text text-4xl font-bold text-transparent">
        TIKKIT
      </h1>
      <p className="max-w-md text-muted-foreground">
        티켓 예매/관리 서비스를 준비하고 있습니다.
      </p>
    </main>
  );
}
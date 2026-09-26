export default function Loading() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-8 px-4 py-8 md:flex-row">
      <div className="flex flex-1 flex-col gap-3">
        <div className="h-6 w-24 animate-pulse rounded-md bg-muted" />
        <div className="h-8 w-2/3 animate-pulse rounded-md bg-muted" />
        <div className="h-4 w-full animate-pulse rounded-md bg-muted" />
        <div className="h-4 w-5/6 animate-pulse rounded-md bg-muted" />
      </div>
      <div className="h-80 w-full animate-pulse rounded-xl bg-muted md:w-80" />
    </main>
  );
}

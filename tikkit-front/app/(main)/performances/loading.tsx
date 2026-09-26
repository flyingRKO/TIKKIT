export default function Loading() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="h-8 w-32 animate-pulse rounded-md bg-muted" />
      <div className="h-9 w-full animate-pulse rounded-md bg-muted" />
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
        {Array.from({ length: 8 }).map((_, index) => (
          <div key={index} className="flex flex-col gap-2">
            <div className="aspect-[3/4] animate-pulse rounded-xl bg-muted" />
            <div className="h-4 w-3/4 animate-pulse rounded-md bg-muted" />
            <div className="h-3 w-1/2 animate-pulse rounded-md bg-muted" />
          </div>
        ))}
      </div>
    </main>
  );
}

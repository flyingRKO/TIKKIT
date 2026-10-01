export default function Loading() {
  return (
    <div role="status" className="flex flex-1 items-center justify-center py-24">
      <div className="size-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      <span className="sr-only">불러오는 중</span>
    </div>
  );
}

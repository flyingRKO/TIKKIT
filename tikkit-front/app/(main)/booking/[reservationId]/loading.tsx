import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
  return (
    <main className="mx-auto flex w-full max-w-lg flex-1 flex-col gap-6 px-4 py-8">
      <Skeleton className="h-8 w-32" />
      <Skeleton className="h-44 w-full rounded-xl" />
      <Skeleton className="h-32 w-full rounded-xl" />
    </main>
  );
}

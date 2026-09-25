import { Placeholder } from "@/components/layout/placeholder";

export default async function MyReservationDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <Placeholder
      title="예매 상세"
      description={`예매 상세 화면을 준비하고 있습니다. (Task 015, id: ${id})`}
    />
  );
}

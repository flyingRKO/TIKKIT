import { Placeholder } from "@/components/layout/placeholder";

export default async function BookingCompletePage({
  params,
}: {
  params: Promise<{ reservationId: string }>;
}) {
  const { reservationId } = await params;
  return (
    <Placeholder
      title="예매 완료"
      description={`예매 완료 화면을 준비하고 있습니다. (Task 014, 예약 ID: ${reservationId})`}
    />
  );
}

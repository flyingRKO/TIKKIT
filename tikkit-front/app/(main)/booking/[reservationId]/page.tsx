import { Placeholder } from "@/components/layout/placeholder";

export default async function BookingPage({
  params,
}: {
  params: Promise<{ reservationId: string }>;
}) {
  const { reservationId } = await params;
  return (
    <Placeholder
      title="결제"
      description={`결제 화면을 준비하고 있습니다. (Task 014, 예약 ID: ${reservationId})`}
    />
  );
}

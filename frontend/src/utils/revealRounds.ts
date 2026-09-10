// Mirrors the backend's GameService.REVEAL_ROUNDS (Set.of(2, 8, 13, 18, 24)).
// Display-only: the server remains the sole authority on when a reveal
// actually happens, so this doesn't affect game logic — just lets the UI
// show an upcoming-reveal hint without a round-trip.
export const REVEAL_ROUNDS = [2, 8, 13, 18, 24] as const

/** Next scheduled reveal round strictly after `currentRound`, or null if none remain. */
export function nextRevealRound(currentRound: number): number | null {
  return REVEAL_ROUNDS.find((r) => r > currentRound) ?? null
}

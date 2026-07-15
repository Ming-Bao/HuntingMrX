export const MODE_COLORS: Record<string, string> = {
  ESCOOTER: '#22c55e',
  BUS:      '#ef4444',
  TRAIN:    '#8b5cf6',
  FERRY:    '#06b6d4',
  BLACK:    '#64748b',
}

const MODE_LABELS: Record<string, string> = {
  ESCOOTER: 'Escooter',
  BUS:      'Bus',
  TRAIN:    'Train',
  FERRY:    'Ferry',
  BLACK:    'Invisible',
}

export function modeColor(mode: string): string {
  return MODE_COLORS[mode] ?? '#6b7280'
}

export function modeLabel(mode: string): string {
  return MODE_LABELS[mode] ?? mode
}

export const modeLegend = [
  { mode: 'ESCOOTER', label: 'Escooter', color: MODE_COLORS.ESCOOTER },
  { mode: 'BUS',      label: 'Bus',      color: MODE_COLORS.BUS },
  { mode: 'TRAIN',    label: 'Train',    color: MODE_COLORS.TRAIN },
  { mode: 'FERRY',    label: 'Ferry',    color: MODE_COLORS.FERRY },
]

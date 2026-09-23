// Everything the screen shows for a ticket: its colour, its name and its
// icon. BLACK is shown to players as the "Invisible" ticket.
import { Scooter, Bus, TrainFront, Ship, EyeOff, type LucideIcon } from 'lucide-vue-next'

export const TICKET_COLORS: Record<string, string> = {
  ESCOOTER: '#22c55e',
  BUS:      '#ef4444',
  TRAIN:    '#8b5cf6',
  FERRY:    '#06b6d4',
  BLACK:    '#64748b',
  DOUBLE:   '#f59e0b',
}

const TICKET_NAMES: Record<string, string> = {
  ESCOOTER: 'Escooter',
  BUS:      'Bus',
  TRAIN:    'Train',
  FERRY:    'Ferry',
  BLACK:    'Invisible',
  DOUBLE:   'Double',
}

// No DOUBLE icon: it isn't a way of travelling, just an extra turn
const TICKET_ICONS: Record<string, LucideIcon> = {
  ESCOOTER: Scooter,
  BUS:      Bus,
  TRAIN:    TrainFront,
  FERRY:    Ship,
  BLACK:    EyeOff,
}

/** The kinds of transport a connection on the map can have, in the order used for the legend and node icons. */
export const TRANSPORT_TYPES = ['ESCOOTER', 'BUS', 'TRAIN', 'FERRY']

/** The order tickets are listed in the side panel. */
export const TICKET_ORDER = [...TRANSPORT_TYPES, 'BLACK', 'DOUBLE']

export const ticketColor = (ticket: string) => TICKET_COLORS[ticket] ?? '#6b7280'
export const ticketName = (ticket: string) => TICKET_NAMES[ticket] ?? ticket
export const ticketIcon = (ticket: string): LucideIcon | undefined => TICKET_ICONS[ticket]

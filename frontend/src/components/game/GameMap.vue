<template>
  <div class="map-panel">
    <div ref="mapContainer" class="map-canvas" />
    <button
      v-if="myNode"
      class="jump-btn"
      title="Jump to my position"
      aria-label="Jump to my position"
      @click="flyToMyNode"
    ><LocateFixed :size="18" /></button>
    <div class="style-switcher">
      <button
        v-for="s in MAP_STYLES" :key="s.id"
        class="style-btn"
        :class="{ 'style-btn--active': currentStyleId === s.id }"
        :disabled="switchingStyle"
        @click="switchStyle(s.id)"
      >{{ s.label }}</button>
    </div>
    <div class="search-box">
      <input
        v-model="searchQuery"
        type="text"
        inputmode="numeric"
        placeholder="Search node…"
        class="search-input"
        @focus="showResults = true"
        @blur="showResults = false"
        @keydown.enter="searchResults.length && selectSearchResult(searchResults[0])"
        @keydown.escape="($event.target as HTMLInputElement).blur()"
      />
      <div v-if="showResults && searchResults.length" class="search-results">
        <button
          v-for="n in searchResults"
          :key="n.id"
          class="search-result"
          @mousedown.prevent="selectSearchResult(n)"
        >Node {{ n.id }}</button>
      </div>
    </div>
    <div class="legend">
      <div v-for="m in modeLegend" :key="m.mode" class="legend-item">
        <div class="legend-line" :style="{ backgroundColor: m.color }"></div>
        <span class="legend-label" :style="{ color: m.color }">{{ m.label }}</span>
      </div>
    </div>

    <!-- Transport-picker popup: a second way to pick a move besides the
         sidebar's Reachable Nodes list — click a reachable node here to see
         the same ticket chips right at it. -->
    <div
      v-if="popupVisible && popupPos"
      class="node-popup"
      :style="{ left: `${popupPos.x}px`, top: `${popupPos.y}px` }"
      @click.stop
    >
      <p class="node-popup-title">Node {{ popupNode?.id }}</p>
      <div class="node-popup-modes">
        <button
          v-for="mode in popupModes"
          :key="mode"
          type="button"
          class="mode-chip"
          :style="{ backgroundColor: modeColor(mode) }"
          :aria-label="`Move to node ${popupNode?.id} by ${modeLabel(mode)}`"
          @click="selectPopupMode(mode)"
        >
          <component :is="modeIcon(mode)" :size="13" class="mode-chip-icon" />
          {{ modeLabel(mode) }}
        </button>
      </div>
      <div class="node-popup-arrow"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { Scooter, Bus, TrainFront, Ship, EyeOff, LocateFixed, type LucideIcon } from 'lucide-vue-next'
import type { GraphNode, GraphEdge, DemoPlayer, ValidMoveDTO } from '../../types/game'
import { MODE_COLORS, modeLegend, modeColor, modeLabel } from '../../utils/transportModes'

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  displayPlayers: DemoPlayer[]
  selectedNode: GraphNode | null
  reachableIds?: Set<number>
  validMoves?: ValidMoveDTO[]
}>()

const emit = defineEmits<{
  'select-node': [node: GraphNode | null]
  'select-ticket': [mode: string]
}>()

// Same glyph-per-ticket mapping as InfoPanel's Reachable Nodes list — the
// map popup is a second entry point to the same action, so it should look
// like the same control, not a different one.
const MODE_ICONS: Record<string, LucideIcon> = {
  ESCOOTER: Scooter,
  BUS:      Bus,
  TRAIN:    TrainFront,
  FERRY:    Ship,
  BLACK:    EyeOff,
}
function modeIcon(mode: string): LucideIcon | undefined {
  return MODE_ICONS[mode]
}

const mapContainer = ref<HTMLDivElement>()
let map: maplibregl.Map | null = null

const myNode = computed(() => {
  const me = props.displayPlayers.find(p => p.isYou)
  return me?.node != null ? props.nodes.find(n => n.id === me.node) ?? null : null
})

function flyToMyNode() {
  if (!map || !myNode.value) return
  map.flyTo({ center: [myNode.value.lng, myNode.value.lat], zoom: Math.max(map.getZoom(), 15) })
}

// Camera-only move — deliberately does NOT touch exploreNode/selection.
// Used by node search and "focus on this player": both are just "take me
// there to look," not a click on the node itself, so nothing should get
// selected or dim/highlight as a side effect.
function flyToNode(node: GraphNode) {
  if (!map) return
  map.flyTo({ center: [node.lng, node.lat], zoom: Math.max(map.getZoom(), 15) })
}

// Lets the sidebar player list ("focus on this player's node") drive the
// camera without the parent needing to know anything about maplibre.
function focusNodeId(nodeId: number) {
  const node = props.nodes.find(n => n.id === nodeId)
  if (node) flyToNode(node)
}
defineExpose({ focusNodeId })

// ── Node search ────────────────────────────────────────────────────────────────

const searchQuery = ref('')
const showResults = ref(false)
const searchResults = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return []
  return props.nodes
    .filter(n => String(n.id).includes(q) || (n.label ?? '').toLowerCase().includes(q))
    .slice(0, 8)
})

function selectSearchResult(node: GraphNode) {
  flyToNode(node)
  searchQuery.value = ''
  showResults.value = false
}

// Auto-center on the player's own node the moment it's known, once — the map
// starts on a wide Wellington-wide view because node/player data hasn't
// loaded yet at construction time, so this catches the first moment it has.
// Guarded to fire only once so it doesn't yank the camera on every later move
// (the jump button above covers re-centering after that).
const hasAutoCentered = ref(false)
watch(myNode, node => {
  if (!map || !node || hasAutoCentered.value) return
  hasAutoCentered.value = true
  const target = { center: [node.lng, node.lat] as [number, number], zoom: Math.max(map.getZoom(), 15) }
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    map.jumpTo(target)
  } else {
    map.flyTo({ ...target, essential: true })
  }
})

// ── Always-on connected-node exploration highlight ─────────────────────────────
// Independent of props.selectedNode (which is turn-gated by the parent) — this
// lets clicking any node, on any turn, dim unrelated nodes and highlight its
// direct graph neighbors, purely as a "what connects here" browsing aid.

const exploreNode = ref<GraphNode | null>(null)
const neighborIds = computed<Set<number> | null>(() => {
  if (!exploreNode.value) return null
  const id = exploreNode.value.id
  const ids = new Set<number>()
  for (const e of props.edges) {
    if (e.from === id) ids.add(e.to)
    if (e.to === id) ids.add(e.from)
  }
  return ids
})

watch(() => props.selectedNode, n => {
  if (n === null) { exploreNode.value = null; popupNode.value = null; return }
  // A direct map click sets exploreNode itself, synchronously, before this
  // watch ever runs — so if exploreNode doesn't already match the incoming
  // selection, it came from elsewhere (e.g. the sidebar). Clear it so a
  // leftover neighbor-explore highlight from an earlier map click doesn't
  // linger alongside the new move highlight.
  if (exploreNode.value?.id !== n.id) exploreNode.value = null
})

// ── Move-selection highlight ─────────────────────────────────────────────────
// A separate, narrower spotlight for picking a destination (e.g. from the
// sidebar's Reachable Nodes list): only your own node and the chosen
// destination stay lit, not every neighbor of the destination — that's the
// explore highlight's job above, not this one.
const moveHighlightIds = computed<Set<number> | null>(() => {
  if (!props.selectedNode) return null
  const ids = new Set<number>([props.selectedNode.id])
  if (myNode.value) ids.add(myNode.value.id)
  return ids
})

// ── Transport-picker popup ──────────────────────────────────────────────────
// Clicking a reachable node on the map is a second way to pick a move,
// alongside the sidebar's Reachable Nodes list — a popup right at the node
// shows the same ticket chips so you don't have to look away from the map
// to see what you can travel there with.
const popupNode = ref<GraphNode | null>(null)
const popupPos = ref<{ x: number; y: number } | null>(null)

// Gated on props.reachableIds rather than just "is popupNode set" — self-
// heals if the node stops being reachable out from under it (turn ends,
// valid moves refresh) without needing every state-clearing path elsewhere
// in this file to remember to also clear popupNode explicitly.
const popupVisible = computed(() =>
  popupNode.value != null && !!props.reachableIds?.has(popupNode.value.id)
)
const popupModes = computed<string[]>(() => {
  if (!popupVisible.value || !popupNode.value) return []
  const move = props.validMoves?.find(m => m.nodeId === popupNode.value!.id)
  return move ? [...new Set(move.ticketOptions)] : []
})

function updatePopupPos() {
  if (!map || !popupNode.value) { popupPos.value = null; return }
  const p = map.project([popupNode.value.lng, popupNode.value.lat])
  popupPos.value = { x: p.x, y: p.y }
}

function selectPopupMode(mode: string) {
  if (!popupNode.value) return
  emit('select-node', popupNode.value)
  emit('select-ticket', mode)
  popupNode.value = null
}

// ── Map tile style catalogue ────────────────────────────────────────────────────

const MAP_STYLES = [
  { id: 'dark',    label: 'Dark',    url: 'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json' },
  { id: 'light',   label: 'Light',   url: 'https://basemaps.cartocdn.com/gl/positron-nolabels-gl-style/style.json' },
  { id: 'voyager', label: 'Voyager', url: 'https://basemaps.cartocdn.com/gl/voyager-nolabels-gl-style/style.json' },
]
const currentStyleId = ref('dark')
const switchingStyle = ref(false)

function switchStyle(id: string) {
  const target = MAP_STYLES.find(s => s.id === id)
  if (!map || !target || switchingStyle.value || id === currentStyleId.value) return
  switchingStyle.value = true
  const center = map.getCenter()
  const zoom = map.getZoom()
  const pitch = map.getPitch()
  const bearing = map.getBearing()
  currentStyleId.value = id
  map.setStyle(target.url)
  // NOTE: 'style.load' only fires for the map's initial style — maplibre-gl 4.7.1
  // does not re-fire it on setStyle() (verified empirically: only 'styledata' then
  // 'idle' fire). 'idle' reliably signals the new style is fully ready for our
  // addSource/addLayer/addImage calls.
  map.once('idle', () => {
    setupLayers()
    map!.jumpTo({ center, zoom, pitch, bearing })
    switchingStyle.value = false
  })
}

// ── Pie chart icon helpers ────────────────────────────────────────────────────

const MODE_ORDER = ['ESCOOTER', 'BUS', 'TRAIN', 'FERRY']
const ABBREV: Record<string, string> = { ESCOOTER: 'E', BUS: 'B', TRAIN: 'T', FERRY: 'F' }

function modesKey(modes: Set<string>): string {
  const key = MODE_ORDER.filter(m => modes.has(m)).map(m => ABBREV[m]).join('')
  return key || 'none'
}

function makePieIcon(modes: string[], isSelected = false): ImageData {
  const SIZE = 48
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = SIZE
  const ctx = canvas.getContext('2d')!
  const cx = SIZE / 2
  const r  = SIZE / 2 - 2

  if (modes.length === 0) {
    ctx.beginPath()
    ctx.arc(cx, cx, r, 0, Math.PI * 2)
    ctx.fillStyle = '#1f2937'
    ctx.fill()
  } else {
    const step = (Math.PI * 2) / modes.length
    let angle = -Math.PI / 2
    for (const mode of modes) {
      ctx.beginPath()
      ctx.moveTo(cx, cx)
      ctx.arc(cx, cx, r, angle, angle + step)
      ctx.closePath()
      ctx.fillStyle = MODE_COLORS[mode] ?? '#6b7280'
      ctx.fill()
      angle += step
    }
  }

  ctx.beginPath()
  ctx.arc(cx, cx, r, 0, Math.PI * 2)
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = isSelected ? 4 : 3
  ctx.stroke()

  return ctx.getImageData(0, 0, SIZE, SIZE)
}

// Ring icon for player-occupied nodes. Deliberately mode-agnostic: transport
// colors are what the other 260 nodes are made of, so reusing them here made
// player markers blend into the network instead of popping out of it at low
// zoom. A flat white ring + black hairline reads as "a person is here" against
// any basemap or surrounding clutter, independent of which lines pass through.
// The center stays transparent so the player-color circle layer shows through.
function makeOccupiedRingIcon(): ImageData {
  const SIZE = 48
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = SIZE
  const ctx = canvas.getContext('2d')!
  const cx = SIZE / 2
  const outerR    = SIZE / 2 - 2   // matches the pie icon's outer radius
  const ringWidth = 7

  ctx.beginPath()
  ctx.arc(cx, cx, outerR - ringWidth / 2, 0, Math.PI * 2)
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = ringWidth
  ctx.stroke()

  // Black hairline on both edges of the white ring — keeps it legible against
  // light basemaps (Positron/Voyager) as well as the default dark style.
  ctx.lineWidth = 1.5
  ctx.strokeStyle = '#000000'
  ctx.beginPath()
  ctx.arc(cx, cx, outerR, 0, Math.PI * 2)
  ctx.stroke()
  ctx.beginPath()
  ctx.arc(cx, cx, outerR - ringWidth, 0, Math.PI * 2)
  ctx.stroke()

  return ctx.getImageData(0, 0, SIZE, SIZE)
}

// Role glyph icons — rendered centered inside the occupied-ring icon's
// transparent hole, on top of the player-color circle fill. One fixed glyph
// per ROLE (not per player) — detectives all share the same glyph, since
// individual detectives are already distinguished by circle color.
function makeRoleIcon(role: 'MR_X' | 'DETECTIVE'): ImageData {
  const SIZE = 48
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = SIZE
  const ctx = canvas.getContext('2d')!

  ctx.strokeStyle = '#ffffff'

  if (role === 'DETECTIVE') {
    // Magnifying glass — investigation motif. Lens up-left, handle down-right.
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'

    const lensCx = 20.5, lensCy = 20.5, lensR = 5.2
    ctx.beginPath()
    ctx.arc(lensCx, lensCy, lensR, 0, Math.PI * 2)
    ctx.lineWidth = 3.2
    ctx.stroke()

    const dir = Math.SQRT1_2
    const startX = lensCx + dir * (lensR + 0.5)
    const startY = lensCy + dir * (lensR + 0.5)
    ctx.beginPath()
    ctx.moveTo(startX, startY)
    ctx.lineTo(31.5, 31.5)
    ctx.lineWidth = 4
    ctx.stroke()
  } else {
    // Bold crossmark — literal "X" for Mr X. Butt caps (not round) deliberately
    // keep the stroke corners tight to the diagonal so they don't overshoot the
    // radius budget the way round caps would.
    ctx.lineCap = 'butt'
    ctx.lineJoin = 'miter'
    ctx.lineWidth = 6

    ctx.beginPath()
    ctx.moveTo(16, 16)
    ctx.lineTo(32, 32)
    ctx.stroke()

    ctx.beginPath()
    ctx.moveTo(32, 16)
    ctx.lineTo(16, 32)
    ctx.stroke()
  }

  return ctx.getImageData(0, 0, SIZE, SIZE)
}

// Custom images registered via addImage() persist across map.setStyle() calls
// (unlike sources/layers, which setStyle() discards) — guard each registration
// so re-running this after a style switch doesn't throw "image already exists".
function addImageIfMissing(id: string, data: ImageData) {
  if (!map || map.hasImage(id)) return
  map.addImage(id, data)
}

function registerIcons() {
  if (!map) return
  for (let mask = 0; mask < 16; mask++) {
    const modes = MODE_ORDER.filter((_, i) => mask & (1 << i))
    const key   = modesKey(new Set(modes))
    addImageIfMissing(`node-${key}`,           makePieIcon(modes))
    addImageIfMissing(`node-${key}-selected`,  makePieIcon(modes, true))
  }
  // Occupied nodes all share one icon — a neutral ring, deliberately not
  // colored by transport mode (see makeOccupiedRingIcon).
  addImageIfMissing('node-occupied', makeOccupiedRingIcon())
  addImageIfMissing('role-detective', makeRoleIcon('DETECTIVE'))
  addImageIfMissing('role-mrx',       makeRoleIcon('MR_X'))
}

// ── GeoJSON builders ──────────────────────────────────────────────────────────

function nodeModeMap(): Map<number, Set<string>> {
  const m = new Map<number, Set<string>>()
  for (const e of props.edges) {
    for (const mode of e.modes) {
      if (!m.has(e.from)) m.set(e.from, new Set())
      if (!m.has(e.to))   m.set(e.to,   new Set())
      m.get(e.from)!.add(mode)
      m.get(e.to)!.add(mode)
    }
  }
  return m
}

function nodeGeoJSON() {
  const modeMap = nodeModeMap()
  const myNode  = props.displayPlayers.find(p => p.isYou)?.node ?? 0
  return {
    type: 'FeatureCollection' as const,
    features: props.nodes.map(n => {
      const modes = modeMap.get(n.id) ?? new Set<string>()
      const key   = modesKey(modes)
      const occupant    = props.displayPlayers.find(p => p.node != null && p.node === n.id)
      const isPlayer    = !!occupant
      const playerColor = occupant?.color ?? ''
      const playerName  = occupant?.name ?? ''
      const playerRole  = occupant?.role ?? ''
      const isSelected = n.id === props.selectedNode?.id
      const isReachable = !isPlayer && !!(props.reachableIds?.has(n.id) ??
        props.edges.some(
          e => (e.from === myNode && e.to === n.id) ||
               (e.to   === myNode && e.from === n.id)
        ))
      const iconKey = isPlayer   ? 'node-occupied'
                    : isSelected ? `node-${key}-selected`
                    : `node-${key}`
      const exploring = exploreNode.value != null &&
        (n.id === exploreNode.value.id || neighborIds.value?.has(n.id))
      const moveHighlighted = moveHighlightIds.value?.has(n.id) ?? false
      const dimming = exploreNode.value != null || moveHighlightIds.value != null
      const dimmed = dimming && !exploring && !moveHighlighted
      return {
        type: 'Feature' as const,
        properties: { id: n.id, label: n.label, isReachable, isPlayer, playerColor, playerName, playerRole, iconKey, dimmed },
        geometry: { type: 'Point' as const, coordinates: [n.lng, n.lat] },
      }
    }),
  }
}

function edgeGeoJSON() {
  const SPACING = 4.5
  return {
    type: 'FeatureCollection' as const,
    features: props.edges.flatMap(e => {
      const from = props.nodes.find(n => n.id === e.from)
      const to   = props.nodes.find(n => n.id === e.to)
      if (!from || !to) return []
      const modes = e.modes.length ? e.modes : ['BUS']
      const coords = e.coordinates ?? [[from.lng, from.lat], [to.lng, to.lat]]
      const dimmedEdge = exploreNode.value != null &&
        e.from !== exploreNode.value.id &&
        e.to !== exploreNode.value.id
      return modes.map((mode, i) => ({
        type: 'Feature' as const,
        properties: {
          mode,
          lineOffset: modes.length === 1 ? 0 : (i - (modes.length - 1) / 2) * SPACING,
          dimmedEdge,
        },
        geometry: { type: 'LineString' as const, coordinates: coords },
      }))
    }),
  }
}

function updateSources() {
  if (!map) return
  ;(map.getSource('nodes') as maplibregl.GeoJSONSource | undefined)?.setData(nodeGeoJSON() as any)
  ;(map.getSource('edges') as maplibregl.GeoJSONSource | undefined)?.setData(edgeGeoJSON() as any)
}

// ── Map setup ─────────────────────────────────────────────────────────────────

const DIMMED_OPACITY_CASE = ['case', ['boolean', ['get', 'dimmed'], false], 0.35, 1]

function setupLayers() {
  if (!map) return

  registerIcons()

  map.addSource('edges', { type: 'geojson', data: edgeGeoJSON() as any })
  map.addSource('nodes', { type: 'geojson', data: nodeGeoJSON() as any })

  // Wide rail casing beneath the coloured edges: bus spurs legitimately run
  // on the rail alignment, so without this band the purple train line
  // disappears under the red one.
  map.addLayer({
    id: 'edges-train-casing',
    type: 'line',
    source: 'edges',
    filter: ['==', ['get', 'mode'], 'TRAIN'],
    paint: {
      'line-color': MODE_COLORS.TRAIN,
      'line-width': 8,
      'line-offset': ['get', 'lineOffset'],
      'line-opacity': ['case', ['boolean', ['get', 'dimmedEdge'], false], 0.1, 0.6] as any,
    },
  })

  // Parallel coloured lines — one feature per mode per edge
  map.addLayer({
    id: 'edges',
    type: 'line',
    source: 'edges',
    paint: {
      'line-color': ['match', ['get', 'mode'],
        'ESCOOTER', MODE_COLORS.ESCOOTER,
        'BUS',      MODE_COLORS.BUS,
        'TRAIN',    MODE_COLORS.TRAIN,
        'FERRY',    MODE_COLORS.FERRY,
        '#6b7280',
      ],
      'line-width': 3,
      'line-offset': ['get', 'lineOffset'],
      'line-opacity': ['case', ['boolean', ['get', 'dimmedEdge'], false], 0.15, 0.85],
    },
  })

  // Player position: solid color fill (behind the ring icon)
  map.addLayer({
    id: 'nodes-player-fill',
    type: 'circle',
    source: 'nodes',
    filter: ['==', ['get', 'isPlayer'], true],
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 21, 15, 24, 18, 29],
      'circle-color': ['get', 'playerColor'],
      'circle-opacity': DIMMED_OPACITY_CASE as any,
    },
  })

  // Role glyph icons — rendered centered on top of the player-color fill,
  // underneath the occupied-ring icon (whose center is transparent).
  map.addLayer({
    id: 'nodes-player-role-icon',
    type: 'symbol',
    source: 'nodes',
    filter: ['==', ['get', 'isPlayer'], true],
    layout: {
      'icon-image': ['match', ['get', 'playerRole'],
        'MR_X',      'role-mrx',
        'DETECTIVE', 'role-detective',
        'role-detective',
      ],
      'icon-size': ['interpolate', ['linear'], ['zoom'], 12, 1.15, 15, 1.4, 18, 1.9],
      'icon-allow-overlap': true,
      'icon-ignore-placement': true,
    },
    paint: {
      'icon-opacity': DIMMED_OPACITY_CASE as any,
    },
  })

  // Pie chart node icons — zoom-interpolated size; player nodes rendered
  // noticeably larger so they still pop out even when zoomed out across the
  // full 261-node map, where plain nodes are dense and easy to lose a marker in.
  map.addLayer({
    id: 'nodes',
    type: 'symbol',
    source: 'nodes',
    layout: {
      'icon-image': ['get', 'iconKey'],
      'icon-size': ['interpolate', ['linear'], ['zoom'],
        12, ['case', ['boolean', ['get', 'isPlayer'], false], 1.3,  0.55],
        15, ['case', ['boolean', ['get', 'isPlayer'], false], 1.6, 0.85],
        18, ['case', ['boolean', ['get', 'isPlayer'], false], 2.1,  1.4],
      ],
      'icon-allow-overlap': true,
      'icon-ignore-placement': true,
    },
    paint: {
      'icon-opacity': DIMMED_OPACITY_CASE as any,
    },
  })

  // Node ID labels — shifted down slightly on player nodes to make room for the name
  map.addLayer({
    id: 'node-ids',
    type: 'symbol',
    source: 'nodes',
    layout: {
      'text-field': ['to-string', ['get', 'id']],
      // Player-node IDs get their own, less zoom-sensitive floor so they stay
      // legible at a glance even zoomed out across the full 261-node map.
      'text-size': ['interpolate', ['linear'], ['zoom'],
        12, ['case', ['boolean', ['get', 'isPlayer'], false], 11, 8],
        15, ['case', ['boolean', ['get', 'isPlayer'], false], 13, 11],
        18, ['case', ['boolean', ['get', 'isPlayer'], false], 16, 15],
      ],
      'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
      'text-anchor': 'center',
      'text-offset': ['case', ['boolean', ['get', 'isPlayer'], false],
        ['literal', [0, 2.1]],
        ['literal', [0, 0]],
      ],
      'text-allow-overlap': true,
      'text-ignore-placement': true,
    },
    paint: {
      'text-color': '#ffffff',
      'text-halo-color': '#000000',
      'text-halo-width': 1.2,
      'text-opacity': DIMMED_OPACITY_CASE as any,
    },
  })

  // Player name labels — shown above the node ID inside player nodes. Halo +
  // a higher zoomed-out size floor than a bare node label needs, since these
  // have to stay readable against the busier map, not just legible up close.
  map.addLayer({
    id: 'node-player-names',
    type: 'symbol',
    source: 'nodes',
    filter: ['==', ['get', 'isPlayer'], true],
    layout: {
      'text-field': ['get', 'playerName'],
      'text-size': ['interpolate', ['linear'], ['zoom'], 12, 10, 15, 12, 18, 15],
      'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
      'text-anchor': 'center',
      'text-offset': [0, -2.3],
      'text-max-width': 5,
      'text-allow-overlap': true,
      'text-ignore-placement': true,
    },
    paint: {
      'text-color': '#ffffff',
      'text-halo-color': '#000000',
      'text-halo-width': 1.2,
      'text-opacity': DIMMED_OPACITY_CASE as any,
    },
  })
}

onMounted(() => {
  map = new maplibregl.Map({
    container: mapContainer.value!,
    style: 'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json',
    center: [174.7762, -41.2865],
    zoom: 14,
    // The Wellington graph only spans ~22km × 24km (map.json's node bounds)
    // — zooming out past this just shows a shrinking dot surrounded by
    // country/world with nothing on it. 10 keeps the whole network in view
    // with comfortable margin without letting you scroll out to NZ-scale.
    minZoom: 10,
    attributionControl: false,
  })

  map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right')

  map.on('load', () => {
    if (!map) return

    setupLayers()

    map.on('click', 'nodes', e => {
      if (!e.features?.length) return
      const nodeId = e.features[0].properties.id as number
      const node = props.nodes.find(n => n.id === nodeId) ?? null
      exploreNode.value = node
      emit('select-node', node)
      // Reachable node: open the transport-picker popup right there too —
      // a non-reachable node just clears it, same as clicking empty space.
      if (node && props.reachableIds?.has(node.id)) {
        popupNode.value = node
        updatePopupPos()
      } else {
        popupNode.value = null
      }
    })

    map.on('click', e => {
      const hit = map!.queryRenderedFeatures(e.point, { layers: ['nodes'] })
      if (!hit.length) {
        exploreNode.value = null
        popupNode.value = null
        emit('select-node', null)
      }
    })

    map.on('mouseenter', 'nodes', () => { map!.getCanvas().style.cursor = 'pointer' })
    map.on('mouseleave', 'nodes', () => { map!.getCanvas().style.cursor = '' })

    // Keep the popup pinned to its node through pan/zoom/rotate — cheap to
    // call unconditionally, updatePopupPos itself no-ops when there's no
    // open popup.
    map.on('move', updatePopupPos)
  })
})

onUnmounted(() => {
  map?.remove()
  map = null
})

watch(
  [() => props.nodes, () => props.edges, () => props.displayPlayers, () => props.selectedNode, () => props.reachableIds, exploreNode],
  updateSources,
  { deep: true },
)
</script>

<style scoped>
@reference "tailwindcss";

.map-panel {
  @apply flex-1 relative overflow-hidden;
}

.map-canvas {
  @apply w-full h-full;
}

.jump-btn {
  @apply absolute top-3 right-3 z-10
         w-9 h-9 rounded-lg
         bg-gray-900/80 hover:bg-gray-800 text-white
         flex items-center justify-center
         border border-gray-700 shadow transition-colors;
}
.style-switcher {
  @apply absolute top-3 left-3 z-10 flex gap-1
         bg-gray-900/80 rounded-lg border border-gray-700 p-1 shadow;
}
.style-btn {
  @apply text-xs px-2 py-1 rounded-md text-gray-400
         hover:bg-gray-800 hover:text-white transition-colors disabled:opacity-50;
}
.style-btn--active {
  @apply bg-white/10 text-white font-semibold;
}

.search-box {
  @apply absolute top-3 left-1/2 -translate-x-1/2 z-10 w-40 sm:w-56;
}
.search-input {
  @apply w-full text-sm px-3 py-1.5 rounded-lg
         bg-gray-900/80 text-white placeholder-gray-500
         border border-gray-700 shadow outline-none
         focus:border-gray-500 transition-colors;
}
.search-results {
  @apply mt-1 rounded-lg bg-gray-900/95 border border-gray-700 shadow
         max-h-48 overflow-y-auto;
}
.search-result {
  @apply block w-full text-left text-sm font-mono px-3 py-1.5 text-gray-200
         hover:bg-gray-800 transition-colors;
}

.legend {
  @apply absolute bottom-8 left-3 flex gap-3 z-10;
}

.legend-item {
  @apply flex items-center gap-1;
}

.legend-line {
  @apply w-3 h-0.5 rounded-full;
}

.legend-label {
  @apply text-xs;
}

/* Positioned via popupPos (map.project() screen coords), anchored above the
   node with a small pointer — same floating-card language as the other map
   overlays (bg-gray-900/95, border-gray-700), but the transport chips below
   borrow InfoPanel's colored-pill treatment since those need per-mode color
   coding to read consistently with the rest of the app. */
.node-popup {
  @apply absolute z-20 -translate-x-1/2 -translate-y-[calc(100%+14px)]
         bg-gray-900/95 border border-gray-700 rounded-lg shadow-lg
         px-3 py-2.5 pointer-events-auto;
  min-width: 9rem;
}
.node-popup-title {
  @apply text-xs font-mono font-semibold text-gray-300 mb-2;
}
.node-popup-modes {
  @apply flex flex-wrap gap-1.5;
}
.node-popup-arrow {
  @apply absolute left-1/2 -bottom-[7px] -translate-x-1/2
         w-3 h-3 rotate-45 bg-gray-900/95 border-r border-b border-gray-700;
}
.mode-chip {
  @apply inline-flex items-center gap-1 text-xs text-white font-semibold
         px-2 py-1 rounded-full cursor-pointer select-none
         border border-white/25 shadow-sm shadow-black/30
         hover:-translate-y-0.5 hover:shadow-md hover:border-white/70 hover:brightness-110
         active:translate-y-0 active:brightness-95
         focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white
         transition-all duration-150;
}
.mode-chip-icon {
  @apply shrink-0 opacity-90;
}
</style>

<template>
  <div class="map-panel">
    <div ref="mapContainer" class="map-canvas" />
    <button
      v-if="myNode"
      class="jump-button"
      title="Jump to my position"
      aria-label="Jump to my position"
      @click="moveCameraTo(myNode)"
    ><LocateFixed :size="18" /></button>
    <div class="style-switcher">
      <button
        v-for="style in MAP_STYLES" :key="style.id"
        class="style-button"
        :class="{ 'style-button--active': currentStyle === style.id }"
        :disabled="switchingStyle"
        @click="switchStyle(style.id)"
      >{{ style.label }}</button>
    </div>
    <div class="search-box">
      <input
        v-model="searchText"
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
          v-for="result in searchResults"
          :key="result.id"
          class="search-result"
          @mousedown.prevent="selectSearchResult(result)"
        >Node {{ result.id }}</button>
      </div>
    </div>
    <div class="legend">
      <div v-for="type in TRANSPORT_TYPES" :key="type" class="legend-item">
        <div class="legend-line" :style="{ backgroundColor: ticketColor(type) }"></div>
        <span class="legend-label" :style="{ color: ticketColor(type) }">{{ ticketName(type) }}</span>
      </div>
    </div>

    <!-- Ticket picker at a clicked reachable node: the same chips as the
         sidebar's Reachable Nodes list, without looking away from the map -->
    <div
      v-if="popupVisible && popupPosition"
      class="node-popup"
      :style="{ left: `${popupPosition.x}px`, top: `${popupPosition.y}px` }"
      @click.stop
    >
      <p class="node-popup-title">Node {{ popupNode?.id }}</p>
      <div class="node-popup-modes">
        <button
          v-for="ticket in popupTickets"
          :key="ticket"
          type="button"
          class="ticket-chip"
          :style="{ backgroundColor: ticketColor(ticket) }"
          :aria-label="`Move to node ${popupNode?.id} by ${ticketName(ticket)}`"
          @click="pickPopupTicket(ticket)"
        >
          <component :is="ticketIcon(ticket)" :size="13" class="ticket-chip-icon" />
          {{ ticketName(ticket) }}
        </button>
      </div>
      <div class="node-popup-arrow"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
// The MapLibre map. Draws the transport network, the nodes (as pie icons of
// the modes that stop there) and the players, and turns clicks into
// select-node / select-ticket events for GameBoardPage.
//
// The map library doesn't redraw by itself when our data changes. Everything
// it shows comes from two data sets, built by nodesForMap() and
// connectionsForMap(), and a watch at the bottom rebuilds them on any change.
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { LocateFixed } from 'lucide-vue-next'
import { currentGame } from '../../shared/current-game'
import type { MapNode, MapConnection, PlayerMarker } from '../../shared/types'
import { TRANSPORT_TYPES, TICKET_COLORS, ticketColor, ticketName, ticketIcon } from '../../shared/tickets'

const props = defineProps<{
  nodes: MapNode[]
  edges: MapConnection[]
  players: PlayerMarker[]
  selectedNode: MapNode | null
}>()

const emit = defineEmits<{
  'select-node': [node: MapNode | null]
  'select-ticket': [ticket: string]
}>()

const mapContainer = ref<HTMLDivElement>()
let map: maplibregl.Map | null = null
let sizeWatcher: ResizeObserver | null = null

const reachableNodeIds = computed(() => new Set(currentGame.possibleMoves.map(move => move.nodeId)))

const myNode = computed(() => {
  const me = props.players.find(player => player.isYou)
  return props.nodes.find(node => node.id === me?.node) ?? null
})

// ── Camera ───────────────────────────────────────────────────────────────────

// Moves the camera only; it doesn't select anything. Zooms in to at least
// street level (15) but never zooms out. Used by the "jump to me" button, node
// search, and the sidebar's player locations.
function moveCameraTo(node: MapNode | null) {
  if (!map || !node) return
  map.flyTo({ center: [node.lng, node.lat], zoom: Math.max(map.getZoom(), 15) })
}

function showNode(nodeId: number) {
  moveCameraTo(props.nodes.find(node => node.id === nodeId) ?? null)
}
defineExpose({ showNode })

// The map opens on all of Wellington because player data isn't loaded yet.
// Centre on your own node the first time it's known, and only then, so later
// moves don't yank the camera around.
let hasAutoCentered = false
watch(myNode, node => {
  if (!map || !node || hasAutoCentered) return
  hasAutoCentered = true
  const target = { center: [node.lng, node.lat] as [number, number], zoom: Math.max(map.getZoom(), 15) }
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) map.jumpTo(target)
  else map.flyTo({ ...target, essential: true })
})

// ── Node search ──────────────────────────────────────────────────────────────

const searchText = ref('')
const showResults = ref(false)
const searchResults = computed(() => {
  const search = searchText.value.trim().toLowerCase()
  if (!search) return []
  return props.nodes
    .filter(node => String(node.id).includes(search) || (node.label ?? '').toLowerCase().includes(search))
    .slice(0, 8)
})

function selectSearchResult(node: MapNode) {
  moveCameraTo(node)
  searchText.value = ''
  showResults.value = false
}

// ── Highlighting ─────────────────────────────────────────────────────────────
// Two highlights dim every other node:
//  - explore: clicking any node, on any turn, lights it and its direct
//    neighbours, to show "what connects here"
//  - move: while a destination is selected, only your node and the destination stay lit

const exploredNode = ref<MapNode | null>(null)
const neighbourIds = computed(() => {
  const ids = new Set<number>()
  const id = exploredNode.value?.id
  for (const connection of props.edges) {
    if (connection.from === id) ids.add(connection.to)
    if (connection.to === id) ids.add(connection.from)
  }
  return ids
})

const moveHighlight = computed<Set<number> | null>(() => {
  if (!props.selectedNode) return null
  const ids = new Set([props.selectedNode.id])
  if (myNode.value) ids.add(myNode.value.id)
  return ids
})

watch(() => props.selectedNode, node => {
  if (node === null) { exploredNode.value = null; popupNode.value = null; return }
  // A map click sets exploredNode to the same node before this runs. A
  // different node means the selection came from the sidebar, so drop the
  // old explore highlight.
  if (exploredNode.value?.id !== node.id) exploredNode.value = null
})

// ── Ticket-picker popup ──────────────────────────────────────────────────────

const popupNode = ref<MapNode | null>(null)
const popupPosition = ref<{ x: number; y: number } | null>(null)

// Hidden as soon as the node stops being reachable (turn over, moves
// refreshed), so no other code has to remember to close it.
const popupVisible = computed(() => popupNode.value != null && reachableNodeIds.value.has(popupNode.value.id))
const popupTickets = computed(() => {
  const move = currentGame.possibleMoves.find(option => option.nodeId === popupNode.value?.id)
  return move?.ticketOptions ?? []
})

// Turns the node's map position into screen pixels, so the popup sits on it
function updatePopupPosition() {
  if (!map || !popupNode.value) { popupPosition.value = null; return }
  popupPosition.value = map.project([popupNode.value.lng, popupNode.value.lat])
}

function pickPopupTicket(ticket: string) {
  if (!popupNode.value) return
  emit('select-node', popupNode.value)
  emit('select-ticket', ticket)
  popupNode.value = null
}

// ── Basemap styles ───────────────────────────────────────────────────────────

const MAP_STYLES = [
  { id: 'dark',    label: 'Dark',    url: 'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json' },
  { id: 'light',   label: 'Light',   url: 'https://basemaps.cartocdn.com/gl/positron-nolabels-gl-style/style.json' },
  { id: 'voyager', label: 'Voyager', url: 'https://basemaps.cartocdn.com/gl/voyager-nolabels-gl-style/style.json' },
]
const currentStyle = ref('dark')
const switchingStyle = ref(false)

// setStyle() throws away our data sets and layers, so they're added again once
// the new style is ready, and the camera is put back where it was.
function switchStyle(id: string) {
  const target = MAP_STYLES.find(style => style.id === id)
  if (!map || !target || switchingStyle.value || id === currentStyle.value) return
  switchingStyle.value = true
  const camera = { center: map.getCenter(), zoom: map.getZoom(), pitch: map.getPitch(), bearing: map.getBearing() }
  currentStyle.value = id
  map.setStyle(target.url)
  // maplibre-gl 4.7 doesn't fire 'style.load' again after setStyle(); 'idle' is
  // the reliable "ready for addSource/addLayer" signal.
  map.once('idle', () => {
    addMapLayers()
    map!.jumpTo(camera)
    switchingStyle.value = false
  })
}

// ── Icons (drawn on a canvas, then registered with the map) ──────────────────

const ICON_SIZE = 48
const ICON_RADIUS = ICON_SIZE / 2 - 2

function blankIcon(): CanvasRenderingContext2D {
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = ICON_SIZE
  return canvas.getContext('2d')!
}

/** Icon name for a node, from the modes stopping there, e.g. 'node-BT' for bus and train. */
function pieIconName(modes: Set<string>): string {
  return TRANSPORT_TYPES.filter(type => modes.has(type)).map(type => type[0]).join('') || 'none'
}

// A pie chart with one slice per transport mode at the node, starting at 12 o'clock
function drawPieIcon(modes: string[], isSelected = false): ImageData {
  const pen = blankIcon()
  const middle = ICON_SIZE / 2
  if (modes.length === 0) {
    pen.beginPath()
    pen.arc(middle, middle, ICON_RADIUS, 0, Math.PI * 2)
    pen.fillStyle = '#1f2937'
    pen.fill()
  }
  const step = (Math.PI * 2) / modes.length
  modes.forEach((mode, i) => {
    const angle = -Math.PI / 2 + i * step
    pen.beginPath()
    pen.moveTo(middle, middle)
    pen.arc(middle, middle, ICON_RADIUS, angle, angle + step)
    pen.closePath()
    pen.fillStyle = ticketColor(mode)
    pen.fill()
  })
  pen.beginPath()
  pen.arc(middle, middle, ICON_RADIUS, 0, Math.PI * 2)
  pen.strokeStyle = '#ffffff'
  pen.lineWidth = isSelected ? 4 : 3
  pen.stroke()
  return pen.getImageData(0, 0, ICON_SIZE, ICON_SIZE)
}

// A white ring with black edges for any node a player stands on. It ignores
// transport colours on purpose: those are what every other node is made of,
// so a coloured marker got lost in the network. The centre is transparent so
// the player-colour circle layer shows through.
function drawPlayerRing(): ImageData {
  const pen = blankIcon()
  const middle = ICON_SIZE / 2
  const ringWidth = 7
  pen.beginPath()
  pen.arc(middle, middle, ICON_RADIUS - ringWidth / 2, 0, Math.PI * 2)
  pen.strokeStyle = '#ffffff'
  pen.lineWidth = ringWidth
  pen.stroke()
  // Black hairlines on both edges keep it visible on light basemaps too
  pen.lineWidth = 1.5
  pen.strokeStyle = '#000000'
  for (const radius of [ICON_RADIUS, ICON_RADIUS - ringWidth]) {
    pen.beginPath()
    pen.arc(middle, middle, radius, 0, Math.PI * 2)
    pen.stroke()
  }
  return pen.getImageData(0, 0, ICON_SIZE, ICON_SIZE)
}

// A white glyph drawn inside the ring: a magnifying glass for every
// detective (their circle colours tell them apart), an X for Mr X.
function drawRoleSymbol(role: 'MR_X' | 'DETECTIVE'): ImageData {
  const pen = blankIcon()
  pen.strokeStyle = '#ffffff'
  if (role === 'DETECTIVE') {
    // Lens up-left, handle down-right
    pen.lineCap = 'round'
    const lensCx = 20.5, lensCy = 20.5, lensR = 5.2
    pen.beginPath()
    pen.arc(lensCx, lensCy, lensR, 0, Math.PI * 2)
    pen.lineWidth = 3.2
    pen.stroke()
    const start = lensCx + Math.SQRT1_2 * (lensR + 0.5)
    pen.beginPath()
    pen.moveTo(start, start)
    pen.lineTo(31.5, 31.5)
    pen.lineWidth = 4
    pen.stroke()
  } else {
    // Butt caps keep the X's corners inside the ring; round caps overshoot
    pen.lineCap = 'butt'
    pen.lineWidth = 6
    pen.beginPath()
    pen.moveTo(16, 16)
    pen.lineTo(32, 32)
    pen.moveTo(32, 16)
    pen.lineTo(16, 32)
    pen.stroke()
  }
  return pen.getImageData(0, 0, ICON_SIZE, ICON_SIZE)
}

// Images survive setStyle() (unlike data sets and layers), so skip any already
// registered to avoid an "image already exists" error after a style switch.
function addIcons() {
  const add = (id: string, data: () => ImageData) => { if (!map!.hasImage(id)) map!.addImage(id, data()) }
  // The map can only show icons it already has by name, so a normal and a
  // selected pie icon are drawn up front for all 16 combinations of the four modes
  for (let mask = 0; mask < 16; mask++) {
    const modes = TRANSPORT_TYPES.filter((_, i) => mask & (1 << i))
    const key = pieIconName(new Set(modes))
    add(`node-${key}`, () => drawPieIcon(modes))
    add(`node-${key}-selected`, () => drawPieIcon(modes, true))
  }
  add('node-occupied', drawPlayerRing)
  add('role-detective', () => drawRoleSymbol('DETECTIVE'))
  add('role-mrx', () => drawRoleSymbol('MR_X'))
}

// ── The two data sets the map draws from ─────────────────────────────────────

function nodesForMap() {
  // The modes stopping at each node, from the edges touching it
  const transportAt = new Map<number, Set<string>>()
  for (const connection of props.edges) {
    for (const id of [connection.from, connection.to]) {
      if (!transportAt.has(id)) transportAt.set(id, new Set())
      connection.modes.forEach(type => transportAt.get(id)!.add(type))
    }
  }
  const dimming = exploredNode.value != null || moveHighlight.value != null
  return {
    type: 'FeatureCollection' as const,
    features: props.nodes.map(node => {
      const occupant = props.players.find(player => player.node === node.id)
      const key = pieIconName(transportAt.get(node.id) ?? new Set())
      const lit = node.id === exploredNode.value?.id || neighbourIds.value.has(node.id) || !!moveHighlight.value?.has(node.id)
      return {
        type: 'Feature' as const,
        properties: {
          id: node.id,
          hasPlayer: !!occupant,
          playerColor: occupant?.color ?? '',
          playerName: occupant?.name ?? '',
          playerRole: occupant?.role ?? '',
          icon: occupant ? 'node-occupied' : node.id === props.selectedNode?.id ? `node-${key}-selected` : `node-${key}`,
          dimmed: dimming && !lit,
        },
        geometry: { type: 'Point' as const, coordinates: [node.lng, node.lat] },
      }
    }),
  }
}

// One line per transport mode per connection, offset sideways so parallel modes sit side by side
function connectionsForMap() {
  const SPACING = 4.5
  const nodeLookup = new Map(props.nodes.map(node => [node.id, node]))
  return {
    type: 'FeatureCollection' as const,
    features: props.edges.flatMap(connection => {
      const from = nodeLookup.get(connection.from)
      const to = nodeLookup.get(connection.to)
      if (!from || !to) return []
      // A connection with no modes listed (a map data mistake) shows as a bus
      // line rather than vanishing
      const modes = connection.modes.length ? connection.modes : ['BUS']
      // Pre-computed road/rail path if the map has one, otherwise a straight line
      const coords = connection.coordinates ?? [[from.lng, from.lat], [to.lng, to.lat]]
      const dimmed = exploredNode.value != null && connection.from !== exploredNode.value.id && connection.to !== exploredNode.value.id
      return modes.map((mode, i) => ({
        type: 'Feature' as const,
        properties: { mode, offset: (i - (modes.length - 1) / 2) * SPACING, dimmed },
        geometry: { type: 'LineString' as const, coordinates: coords },
      }))
    }),
  }
}

function redrawMap() {
  ;(map?.getSource('nodes') as maplibregl.GeoJSONSource | undefined)?.setData(nodesForMap() as any)
  ;(map?.getSource('connections') as maplibregl.GeoJSONSource | undefined)?.setData(connectionsForMap() as any)
}

// ── Map layers (bottom to top) ───────────────────────────────────────────────

// The arrays below are MapLibre style rules. ['get', 'dimmed'] reads that
// property of each node or connection, ['case', test, a, b] picks a or b, and
// ['interpolate', ['linear'], ['zoom'], 12, a, 18, b] grows from a at zoom 12
// to b at zoom 18.
const FADE_WHEN_DIMMED = ['case', ['boolean', ['get', 'dimmed'], false], 0.35, 1] as any
const HAS_PLAYER = ['boolean', ['get', 'hasPlayer'], false]
const LABEL_LOOK = { 'text-color': '#ffffff', 'text-halo-color': '#000000', 'text-halo-width': 1.2, 'text-opacity': FADE_WHEN_DIMMED }

function addMapLayers() {
  if (!map) return
  addIcons()

  map.addSource('connections', { type: 'geojson', data: connectionsForMap() as any })
  map.addSource('nodes', { type: 'geojson', data: nodesForMap() as any })

  // A wide band under train edges. Some bus edges run along the railway, and
  // without this the purple train line vanishes under the red bus line.
  map.addLayer({
    id: 'train-band',
    type: 'line',
    source: 'connections',
    filter: ['==', ['get', 'mode'], 'TRAIN'],
    paint: {
      'line-color': TICKET_COLORS.TRAIN,
      'line-width': 8,
      'line-offset': ['get', 'offset'],
      'line-opacity': ['case', ['boolean', ['get', 'dimmed'], false], 0.1, 0.6] as any,
    },
  })

  map.addLayer({
    id: 'connections',
    type: 'line',
    source: 'connections',
    paint: {
      'line-color': ['match', ['get', 'mode'], ...TRANSPORT_TYPES.flatMap(type => [type, TICKET_COLORS[type]]), '#6b7280'] as any,
      'line-width': 3,
      'line-offset': ['get', 'offset'],
      'line-opacity': ['case', ['boolean', ['get', 'dimmed'], false], 0.15, 0.85],
    },
  })

  // Player's colour, filling the ring icon's hole
  map.addLayer({
    id: 'nodes-player-fill',
    type: 'circle',
    source: 'nodes',
    filter: ['==', ['get', 'hasPlayer'], true],
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 21, 15, 24, 18, 29],
      'circle-color': ['get', 'playerColor'],
      'circle-opacity': FADE_WHEN_DIMMED,
    },
  })

  // Role glyph on top of the player's colour
  map.addLayer({
    id: 'nodes-player-role-icon',
    type: 'symbol',
    source: 'nodes',
    filter: ['==', ['get', 'hasPlayer'], true],
    layout: {
      'icon-image': ['match', ['get', 'playerRole'], 'MR_X', 'role-mrx', 'role-detective'],
      'icon-size': ['interpolate', ['linear'], ['zoom'], 12, 1.15, 15, 1.4, 18, 1.9],
      'icon-allow-overlap': true,
      'icon-ignore-placement': true,
    },
    paint: { 'icon-opacity': FADE_WHEN_DIMMED },
  })

  // Node icons (pie, or ring for a player). Player nodes are drawn larger so
  // they stand out when zoomed out over all 216 nodes.
  map.addLayer({
    id: 'nodes',
    type: 'symbol',
    source: 'nodes',
    layout: {
      'icon-image': ['get', 'icon'],
      'icon-size': ['interpolate', ['linear'], ['zoom'],
        12, ['case', HAS_PLAYER, 1.3, 0.55],
        15, ['case', HAS_PLAYER, 1.6, 0.85],
        18, ['case', HAS_PLAYER, 2.1, 1.4],
      ] as any,
      'icon-allow-overlap': true,
      'icon-ignore-placement': true,
    },
    paint: { 'icon-opacity': FADE_WHEN_DIMMED },
  })

  // Node id. On player nodes it moves below the ring and gets bigger, so it
  // stays readable zoomed out.
  map.addLayer({
    id: 'node-ids',
    type: 'symbol',
    source: 'nodes',
    layout: {
      'text-field': ['to-string', ['get', 'id']],
      'text-size': ['interpolate', ['linear'], ['zoom'],
        12, ['case', HAS_PLAYER, 11, 8],
        15, ['case', HAS_PLAYER, 13, 11],
        18, ['case', HAS_PLAYER, 16, 15],
      ] as any,
      'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
      'text-anchor': 'center',
      'text-offset': ['case', HAS_PLAYER, ['literal', [0, 2.1]], ['literal', [0, 0]]] as any,
      'text-allow-overlap': true,
      'text-ignore-placement': true,
    },
    paint: LABEL_LOOK,
  })

  // Player name, above the ring
  map.addLayer({
    id: 'node-player-names',
    type: 'symbol',
    source: 'nodes',
    filter: ['==', ['get', 'hasPlayer'], true],
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
    paint: LABEL_LOOK,
  })
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

onMounted(() => {
  const newMap = new maplibregl.Map({
    container: mapContainer.value!,
    style: MAP_STYLES[0].url,
    center: [174.7762, -41.2865],
    zoom: 14,
    // The graph only covers about 22 × 24 km. Zoom 10 fits all of it without
    // letting you scroll out to the whole of New Zealand.
    minZoom: 10,
    attributionControl: false,
  })
  map = newMap
  newMap.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right')

  // Re-measure the map whenever its box changes size. MapLibre 4.7 watches the
  // box too, but it ignores the first change it sees, so a map created while
  // the page was still settling (e.g. in a background tab) stayed stuck at
  // that early size, leaving most of the map blank until the window resized.
  sizeWatcher = new ResizeObserver(() => newMap.resize())
  sizeWatcher.observe(mapContainer.value!)

  newMap.on('load', () => {
    addMapLayers()

    // Clicking a node explores it and selects it. A reachable node also
    // opens the ticket picker.
    newMap.on('click', 'nodes', click => {
      const nodeId = click.features?.[0]?.properties.id as number | undefined
      if (nodeId === undefined) return
      const node = props.nodes.find(candidate => candidate.id === nodeId) ?? null
      exploredNode.value = node
      emit('select-node', node)
      popupNode.value = node && reachableNodeIds.value.has(node.id) ? node : null
      updatePopupPosition()
    })

    // Fires for every click, nodes included, so it skips any click that hit a
    // node and clears everything otherwise
    newMap.on('click', click => {
      if (newMap.queryRenderedFeatures(click.point, { layers: ['nodes'] }).length) return
      exploredNode.value = null
      popupNode.value = null
      emit('select-node', null)
    })

    newMap.on('mouseenter', 'nodes', () => { newMap.getCanvas().style.cursor = 'pointer' })
    newMap.on('mouseleave', 'nodes', () => { newMap.getCanvas().style.cursor = '' })
    // Keep the popup pinned to its node while panning and zooming
    newMap.on('move', updatePopupPosition)
  })
})

onUnmounted(() => {
  sizeWatcher?.disconnect()
  map?.remove()
  map = null
})

// Redraw whenever anything the map shows changes
watch(
  [() => props.nodes, () => props.edges, () => props.players, () => props.selectedNode, reachableNodeIds, exploredNode],
  redrawMap,
  { deep: true },
)
</script>


<style scoped>
@reference "../../app/style.css";

.map-panel {
  @apply flex-1 relative overflow-hidden;
}

.map-canvas {
  @apply w-full h-full;
}

.jump-button {
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
.style-button {
  @apply text-xs px-2 py-1 rounded-md text-gray-400
         hover:bg-gray-800 hover:text-white transition-colors disabled:opacity-50;
}
.style-button--active {
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

/* Placed at the node's screen position (popupPosition), just above it, with a small arrow */
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
</style>

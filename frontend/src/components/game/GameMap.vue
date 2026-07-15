<template>
  <div class="map-panel">
    <div ref="mapContainer" class="map-canvas" />
    <button
      v-if="myNode"
      class="jump-btn"
      title="Jump to my position"
      @click="flyToMyNode"
    >⊕</button>
    <div class="style-switcher">
      <button
        v-for="s in MAP_STYLES" :key="s.id"
        class="style-btn"
        :class="{ 'style-btn--active': currentStyleId === s.id }"
        :disabled="switchingStyle"
        @click="switchStyle(s.id)"
      >{{ s.label }}</button>
    </div>
    <div class="legend">
      <div v-for="m in modeLegend" :key="m.mode" class="legend-item">
        <div class="legend-line" :style="{ backgroundColor: m.color }"></div>
        <span class="legend-label" :style="{ color: m.color }">{{ m.label }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import type { GraphNode, GraphEdge, DemoPlayer } from '../../types/game'
import { MODE_COLORS, modeLegend } from '../../utils/transportModes'

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  displayPlayers: DemoPlayer[]
  selectedNode: GraphNode | null
  reachableIds?: Set<number>
}>()

const emit = defineEmits<{ 'select-node': [node: GraphNode | null] }>()

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

// ── Always-on connected-node exploration highlight ─────────────────────────────
// Independent of props.selectedNode (which is turn-gated by the parent) — this
// lets clicking any node, on any turn, highlight its direct graph neighbors.

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

watch(() => props.selectedNode, n => { if (n === null) exploreNode.value = null })

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

// Ring icon for player-occupied nodes — draws colored arc segments per transport
// mode. The center is transparent so the player-color circle layer shows through.
function makeOccupiedRingIcon(modes: string[]): ImageData {
  const SIZE = 48
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = SIZE
  const ctx = canvas.getContext('2d')!
  const cx = SIZE / 2
  const outerR   = SIZE / 2 - 2   // matches the pie icon's outer radius
  const ringWidth = 6
  const ringMid   = outerR - ringWidth / 2

  if (modes.length === 0) {
    ctx.beginPath()
    ctx.arc(cx, cx, ringMid, 0, Math.PI * 2)
    ctx.strokeStyle = '#ffffff'
    ctx.lineWidth = ringWidth
    ctx.stroke()
  } else {
    const step = (Math.PI * 2) / modes.length
    let angle = -Math.PI / 2
    for (const mode of modes) {
      ctx.beginPath()
      ctx.arc(cx, cx, ringMid, angle, angle + step)
      ctx.strokeStyle = MODE_COLORS[mode] ?? '#6b7280'
      ctx.lineWidth = ringWidth
      ctx.stroke()
      angle += step
    }
  }

  // Separator: thin ring between player fill and mode arcs
  ctx.beginPath()
  ctx.arc(cx, cx, outerR - ringWidth, 0, Math.PI * 2)
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = 1.8
  ctx.stroke()

  // Outer border — matches the pie chart node border
  ctx.beginPath()
  ctx.arc(cx, cx, outerR, 0, Math.PI * 2)
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = 3
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
    // Occupied variant: colored ring, transparent fill
    addImageIfMissing(`node-occupied-${key}`,  makeOccupiedRingIcon(modes))
  }
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
      const iconKey = isPlayer   ? `node-occupied-${key}`
                    : isSelected ? `node-${key}-selected`
                    : `node-${key}`
      const dimmed = exploreNode.value != null &&
        n.id !== exploreNode.value.id &&
        !(neighborIds.value?.has(n.id))
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
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 13, 15, 17, 18, 25],
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
      'icon-size': ['interpolate', ['linear'], ['zoom'], 12, 0.72, 15, 1.0, 18, 1.6],
      'icon-allow-overlap': true,
      'icon-ignore-placement': true,
    },
    paint: {
      'icon-opacity': DIMMED_OPACITY_CASE as any,
    },
  })

  // Pie chart node icons — zoom-interpolated size; player node rendered larger
  map.addLayer({
    id: 'nodes',
    type: 'symbol',
    source: 'nodes',
    layout: {
      'icon-image': ['get', 'iconKey'],
      'icon-size': ['interpolate', ['linear'], ['zoom'],
        12, ['case', ['boolean', ['get', 'isPlayer'], false], 0.8,  0.55],
        15, ['case', ['boolean', ['get', 'isPlayer'], false], 1.15, 0.85],
        18, ['case', ['boolean', ['get', 'isPlayer'], false], 1.8,  1.4],
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
      'text-size': ['interpolate', ['linear'], ['zoom'], 12, 8, 15, 11, 18, 15],
      'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
      'text-anchor': 'center',
      'text-offset': ['case', ['boolean', ['get', 'isPlayer'], false],
        ['literal', [0, 1.7]],
        ['literal', [0, 0]],
      ],
      'text-allow-overlap': true,
      'text-ignore-placement': true,
    },
    paint: {
      'text-color': '#ffffff',
      'text-opacity': DIMMED_OPACITY_CASE as any,
    },
  })

  // Player name labels — shown above the node ID inside player nodes
  map.addLayer({
    id: 'node-player-names',
    type: 'symbol',
    source: 'nodes',
    filter: ['==', ['get', 'isPlayer'], true],
    layout: {
      'text-field': ['get', 'playerName'],
      'text-size': ['interpolate', ['linear'], ['zoom'], 12, 7, 15, 9, 18, 13],
      'text-font': ['Open Sans Bold', 'Arial Unicode MS Bold'],
      'text-anchor': 'center',
      'text-offset': [0, -1.9],
      'text-max-width': 5,
      'text-allow-overlap': true,
      'text-ignore-placement': true,
    },
    paint: {
      'text-color': '#ffffff',
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
    })

    map.on('click', e => {
      const hit = map!.queryRenderedFeatures(e.point, { layers: ['nodes'] })
      if (!hit.length) {
        exploreNode.value = null
        emit('select-node', null)
      }
    })

    map.on('mouseenter', 'nodes', () => { map!.getCanvas().style.cursor = 'pointer' })
    map.on('mouseleave', 'nodes', () => { map!.getCanvas().style.cursor = '' })
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
         bg-gray-900/80 hover:bg-gray-800 text-white text-lg
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
</style>

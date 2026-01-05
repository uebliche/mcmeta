<template>
  <div>
    <div class="bg-orb orb-a"></div>
    <div class="bg-orb orb-b"></div>
    <div class="bg-grid"></div>

    <header class="site-header">
      <div>
        <p class="eyebrow">Minecraft metadata explorer</p>
        <h1>mcmeta</h1>
        <p class="subtitle">
          Browse loader and artifact metadata per Minecraft version. Data is
          published to git branches named
          <span class="mono">mc/&lt;version&gt;</span>.
        </p>
      </div>
      <div class="header-actions">
        <button class="ghost" @click="refresh">Refresh</button>
        <button class="ghost" @click="goLatest">Latest</button>
        <a
          class="ghost"
          :href="repoUrl"
          target="_blank"
          rel="noreferrer"
        >
          Repo
        </a>
      </div>
    </header>

    <main class="layout">
      <div class="panel search-panel">
        <div class="search-row">
          <div>
            <label class="label" for="search">Search versions</label>
            <input
              id="search"
              v-model="search"
              type="search"
              placeholder="1.20, 24w, rc"
            />
          </div>
          <label class="toggle">
            <input v-model="includeSnapshots" type="checkbox" />
            <span>Include snapshots</span>
          </label>
          <div class="stat-row compact">
            <div>
              <div class="stat-label">Versions</div>
              <div class="stat-value">{{ versionCount }}</div>
            </div>
            <div>
              <div class="stat-label">Last sync</div>
              <div class="stat-value">{{ lastSync }}</div>
            </div>
          </div>
        </div>
      </div>

      <aside class="sidebar">
        <div class="panel list-panel">
          <div class="version-list">
            <div v-if="filteredEntries.length === 0" class="version-item">
              No matches
            </div>
            <div
              v-for="(entry, index) in filteredEntries"
              :key="entry.branch"
              class="version-item"
              :class="{ active: entry.branch === activeBranch }"
              :style="{ animationDelay: `${index * 0.02}s` }"
              @click="selectEntry(entry)"
            >
              <div>{{ entry.label }}</div>
              <div class="version-meta">
                <span>{{ entry.badge }}</span>
                <span>{{ entry.branch }}</span>
              </div>
              <div class="version-icons">
                <span
                  v-for="icon in loaderIconList"
                  :key="`${entry.branch}-${icon.key}`"
                  class="loader-icon"
                  :class="[
                    icon.key,
                    loaderStatus[entry.branch]?.[icon.key] ? 'on' : 'off',
                  ]"
                  :title="icon.label"
                >
                  <img :src="icon.src" :alt="icon.label" loading="lazy" />
                </span>
              </div>
            </div>
          </div>
        </div>
      </aside>

      <section class="content">
        <div class="panel hero">
          <div>
            <h2>{{ activeLabel || 'Select a version' }}</h2>
            <p class="mono">{{ activeBranch || '—' }}</p>
          </div>
          <div class="status">
            <span :class="['badge', overallStatus]">{{ overallStatus }}</span>
            <div v-if="errorMessage" class="status-message">{{ errorMessage }}</div>
          </div>
        </div>

        <div class="tabs">
          <button
            class="tab"
            :class="{ active: activeTab === 'overview' }"
            @click="activeTab = 'overview'"
          >
            Overview
          </button>
          <button
            class="tab"
            :class="{ active: activeTab === 'raw' }"
            @click="activeTab = 'raw'"
          >
            Raw JSON
          </button>
        </div>

        <div v-show="activeTab === 'overview'" class="tab-panel">
          <div class="grid">
            <div class="panel">
              <h3>Loaders</h3>
              <div class="loader-summary">
                <div
                  v-for="entry in loaderCards"
                  :key="entry.key"
                  class="loader-card"
                >
                  <h4>{{ entry.label }}</h4>
                  <div class="chips">
                    <span
                      v-for="item in entry.loader"
                      :key="`${entry.key}-loader-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span
                      v-for="item in entry.installer"
                      :key="`${entry.key}-installer-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span v-if="entry.loader.length === 0 && entry.installer.length === 0" class="chip">
                      n/a
                    </span>
                  </div>
                </div>
              </div>
            </div>
            <div class="panel">
              <h3>Artifacts</h3>
              <div class="artifact-summary">
                <div v-if="minestom" class="artifact-card">
                  <h4>Minestom</h4>
                  <div class="chips">
                    <span
                      v-for="item in minestom.versions"
                      :key="`minestom-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span v-if="!minestom.versions || minestom.versions.length === 0" class="chip">
                      n/a
                    </span>
                  </div>
                  <div class="mono">{{ (minestom.coordinates || []).join(', ') }}</div>
                </div>
                <div v-if="fabricApi" class="artifact-card">
                  <h4>Fabric API</h4>
                  <div class="chips">
                    <span
                      v-for="item in fabricApiPreview"
                      :key="`fabric-api-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span v-if="fabricApiMore > 0" class="chip">
                      +{{ fabricApiMore }} more
                    </span>
                  </div>
                  <div class="mono">project {{ fabricApi.project_id }}</div>
                </div>
                <div v-if="paper" class="artifact-card">
                  <h4>Paper</h4>
                  <div class="chips">
                    <span
                      v-for="item in paperPreview"
                      :key="`paper-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span v-if="paperMore > 0" class="chip">+{{ paperMore }} more</span>
                  </div>
                </div>
                <div v-if="folia" class="artifact-card">
                  <h4>Folia</h4>
                  <div class="chips">
                    <span
                      v-for="item in foliaPreview"
                      :key="`folia-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span v-if="foliaMore > 0" class="chip">+{{ foliaMore }} more</span>
                  </div>
                </div>
                <div v-if="velocity" class="artifact-card">
                  <h4>Velocity</h4>
                  <div class="chips">
                    <span
                      v-for="item in velocityPreview"
                      :key="`velocity-${item}`"
                      class="chip"
                    >
                      {{ item }}
                    </span>
                    <span v-if="velocityMore > 0" class="chip">
                      +{{ velocityMore }} more
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="panel health-panel">
            <h3>Source health</h3>
            <div class="health-grid">
              <div
                v-for="source in sourceItems"
                :key="source.name"
                class="health-item"
              >
                <span>{{ source.name }}</span>
                <span :class="['badge', source.status]">{{ source.status }}</span>
              </div>
            </div>
            <div v-if="metaNotes.length" class="notes">
              <strong>Notes</strong>
              <ul>
                <li v-for="note in metaNotes" :key="note">{{ note }}</li>
              </ul>
            </div>
          </div>

          <div class="panel howto">
            <h3>How to use (Gradle)</h3>
            <p>
              Use the local Gradle plugin to load versions from mcmeta and
              expose them as Gradle properties.
            </p>
            <pre class="code">{{ howTo }}</pre>
          </div>
        </div>

        <div v-show="activeTab === 'raw'" class="tab-panel">
          <div class="panel">
            <h3>loader-index.json</h3>
            <pre class="code">{{ rawLoader }}</pre>
          </div>
          <div class="panel">
            <h3>artifacts.json</h3>
            <pre class="code">{{ rawArtifacts }}</pre>
          </div>
          <div class="panel">
            <h3>meta.json</h3>
            <pre class="code">{{ rawMeta }}</pre>
          </div>
        </div>
      </section>
    </main>

    <footer class="site-footer">
      <span class="mono">uebliche/mcmeta</span>
      <span>Data is updated by mcmeta-harvest</span>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';

const repoOwner = 'uebliche';
const repoName = 'mcmeta';
const apiBase = `https://api.github.com/repos/${repoOwner}/${repoName}`;
const rawBase = `https://raw.githubusercontent.com/${repoOwner}/${repoName}`;
const repoUrl = `https://github.com/${repoOwner}/${repoName}`;
const cacheKey = 'mcmeta-branches-v2';
const statusCacheKey = 'mcmeta-loader-status-v1';
const manifestCacheKey = 'mcmeta-manifest-order-v1';
const cacheTtlMs = 1000 * 60 * 30;
const statusWorkers = 6;
const manifestUrl =
  'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';

const search = ref('');
const includeSnapshots = ref(true);
const branches = ref([]);
const activeBranch = ref('');
const activeLabel = ref('');
const activeTab = ref('overview');
const overallStatus = ref('idle');
const lastSync = ref('-');
const loaderStatus = ref({});
const manifestOrder = ref({});
const errorMessage = ref('');

const loaderIndex = ref(null);
const artifacts = ref(null);
const meta = ref(null);

const filteredEntries = computed(() => {
  const searchValue = search.value.toLowerCase();
  const include = includeSnapshots.value;
  const order = manifestOrder.value || {};
  const names = branches.value.map((branch) => branch.name);
  const entries = [];

  if (names.includes('latest')) {
    entries.push({
      label: 'latest',
      branch: 'latest',
      badge: 'latest',
    });
  }
  if (names.includes('latest-snapshot') && include) {
    entries.push({
      label: 'latest-snapshot',
      branch: 'latest-snapshot',
      badge: 'snapshot',
    });
  }

  let list = names
    .filter((name) => name.startsWith('mc/'))
    .map((name) => name.replace('mc/', ''))
    .map((version) => ({
      label: version,
      branch: `mc/${version}`,
      badge: isSnapshot(version) ? 'snapshot' : 'release',
    }))
    .filter((entry) => (include ? true : entry.badge === 'release'));

  list.sort((a, b) => {
    const aKey = order[sanitizeVersion(a.label)];
    const bKey = order[sanitizeVersion(b.label)];
    if (aKey !== undefined && bKey !== undefined) {
      return aKey - bKey;
    }
    if (aKey !== undefined) return -1;
    if (bKey !== undefined) return 1;
    return b.label.localeCompare(a.label, undefined, { numeric: true });
  });

  const all = entries.concat(list);
  if (!searchValue) return all;
  return all.filter((entry) => {
    const hay = `${entry.label} ${entry.branch}`.toLowerCase();
    return hay.includes(searchValue);
  });
});

const versionCount = computed(() => filteredEntries.value.length);

const loaderCards = computed(() => {
  const loaders = loaderIndex.value?.loaders || {};
  const entries = [
    { key: 'fabric', label: 'Fabric' },
    { key: 'quilt', label: 'Quilt' },
    { key: 'forge', label: 'Forge' },
    { key: 'neoforge', label: 'NeoForge' },
  ];
  return entries.map((entry) => {
    const data = loaders[entry.key] || {};
    return {
      ...entry,
      loader: data.loader || [],
      installer: data.installer || [],
    };
  });
});

const minestom = computed(() => artifacts.value?.artifacts?.minestom || null);
const fabricApi = computed(() => artifacts.value?.artifacts?.['fabric-api'] || null);
const paper = computed(() => artifacts.value?.artifacts?.paper || null);
const folia = computed(() => artifacts.value?.artifacts?.folia || null);
const velocity = computed(() => artifacts.value?.artifacts?.velocity || null);

const fabricApiPreview = computed(() => {
  const versions = fabricApi.value?.versions || [];
  return versions.slice(0, 8).map((item) => item.version_number);
});

const fabricApiMore = computed(() => {
  const total = fabricApi.value?.versions?.length || 0;
  return Math.max(total - 8, 0);
});

const paperPreview = computed(() => {
  const versions = paper.value?.versions || [];
  return versions.slice(0, 6);
});

const paperMore = computed(() => {
  const total = paper.value?.versions?.length || 0;
  return Math.max(total - 6, 0);
});

const foliaPreview = computed(() => {
  const versions = folia.value?.versions || [];
  return versions.slice(0, 6);
});

const foliaMore = computed(() => {
  const total = folia.value?.versions?.length || 0;
  return Math.max(total - 6, 0);
});

const velocityPreview = computed(() => {
  const versions = velocity.value?.versions || [];
  return versions.slice(0, 6);
});

const velocityMore = computed(() => {
  const total = velocity.value?.versions?.length || 0;
  return Math.max(total - 6, 0);
});

const sourceItems = computed(() => {
  const sources = meta.value?.sources || {};
  return Object.keys(sources).map((name) => ({
    name,
    status: sources[name],
  }));
});

const metaNotes = computed(() => meta.value?.notes || []);

const rawLoader = computed(() =>
  loaderIndex.value ? JSON.stringify(loaderIndex.value, null, 2) : ''
);
const rawArtifacts = computed(() =>
  artifacts.value ? JSON.stringify(artifacts.value, null, 2) : ''
);
const rawMeta = computed(() =>
  meta.value ? JSON.stringify(meta.value, null, 2) : ''
);

const howTo = `settings.gradle.kts
pluginManagement {
  includeBuild("gradle-plugin")
}

build.gradle.kts
plugins {
  id("net.uebliche.mcmeta")
}

mcmeta {
  minecraftVersion = "1.21.4"
}

val fabricLoader = extra["mcmetaFabricLoaderVersion"] as String?
val paperBuild = extra["mcmetaPaperVersion"] as String?
val velocityVersion = extra["mcmetaVelocityVersion"] as String?
val foliaBuild = extra["mcmetaFoliaVersion"] as String?`;

const loaderIconList = [
  {
    key: 'fabric',
    label: 'Fabric',
    src: 'icons/fabric.png',
  },
  {
    key: 'quilt',
    label: 'Quilt',
    src: 'icons/quilt.png',
  },
  {
    key: 'forge',
    label: 'Forge',
    src: 'icons/forge.png',
  },
  {
    key: 'neoforge',
    label: 'NeoForge',
    src: 'icons/neoforge.png',
  },
];

function isSnapshot(version) {
  return !/^\d+\.\d+(\.\d+)?$/.test(version);
}

function formatTimestamp(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleString();
}

function loadCache() {
  const cached = localStorage.getItem(cacheKey);
  if (!cached) return null;
  try {
    const parsed = JSON.parse(cached);
    if (Date.now() - parsed.timestamp > cacheTtlMs) return null;
    return parsed;
  } catch {
    return null;
  }
}

function loadManifestCache() {
  const cached = localStorage.getItem(manifestCacheKey);
  if (!cached) return null;
  try {
    const parsed = JSON.parse(cached);
    if (Date.now() - parsed.timestamp > cacheTtlMs) return null;
    return parsed;
  } catch {
    return null;
  }
}

function storeCache(items) {
  localStorage.setItem(
    cacheKey,
    JSON.stringify({ timestamp: Date.now(), items })
  );
}

function storeManifestCache(order) {
  localStorage.setItem(
    manifestCacheKey,
    JSON.stringify({ timestamp: Date.now(), order })
  );
}

function loadStatusCache() {
  const cached = localStorage.getItem(statusCacheKey);
  if (!cached) return {};
  try {
    return JSON.parse(cached) || {};
  } catch {
    return {};
  }
}

function storeStatusCache(statuses) {
  localStorage.setItem(statusCacheKey, JSON.stringify(statuses));
}

async function fetchBranches() {
  const cached = loadCache();
  if (cached) return cached;

  let page = 1;
  let all = [];

  while (true) {
    const resp = await fetch(`${apiBase}/branches?per_page=100&page=${page}`);
    if (!resp.ok) throw new Error('Failed to load branches');
    const data = await resp.json();
    all = all.concat(data);
    if (data.length < 100) break;
    page += 1;
  }

  storeCache(all);
  return { timestamp: Date.now(), items: all };
}

async function fetchManifestOrder() {
  const cached = loadManifestCache();
  if (cached) {
    manifestOrder.value = cached.order || {};
    return;
  }
  try {
    const data = await fetchJson(manifestUrl);
    const order = {};
    (data.versions || []).forEach((entry, index) => {
      const key = sanitizeVersion(entry.id);
      if (key) {
        order[key] = index;
      }
    });
    manifestOrder.value = order;
    storeManifestCache(order);
  } catch (err) {
    console.warn('manifest fetch failed', err);
    manifestOrder.value = {};
  }
}

function setStatus(text, state = 'neutral') {
  overallStatus.value = state === 'neutral' ? text : state;
}

async function selectEntry(entry) {
  activeBranch.value = entry.branch;
  activeLabel.value = entry.label;
  setStatus('loading', 'neutral');

  try {
    errorMessage.value = '';
    const [loader, artifactsData, metaData] = await Promise.all([
      fetchJson(`${rawBase}/${entry.branch}/loader-index.json`),
      fetchJson(`${rawBase}/${entry.branch}/artifacts.json`),
      fetchJson(`${rawBase}/${entry.branch}/meta.json`),
    ]);

    loaderIndex.value = loader;
    artifacts.value = artifactsData;
    meta.value = metaData;

    const statuses = Object.values(metaData.sources || {});
    const overall = statuses.reduce((acc, value) => {
      if (value === 'error') return 'error';
      if (value === 'partial' && acc !== 'error') return 'partial';
      return acc;
    }, 'ok');

    overallStatus.value = overall;
  } catch (err) {
    console.error(err);
    overallStatus.value = 'error';
    errorMessage.value = err?.message || 'Failed to load version data';
    loaderIndex.value = null;
    artifacts.value = null;
    meta.value = null;
  }
}

function goLatest() {
  const latest = filteredEntries.value.find((entry) => entry.branch === 'latest');
  if (latest) {
    selectEntry(latest);
    return;
  }
  if (filteredEntries.value.length) {
    selectEntry(filteredEntries.value[0]);
  }
}

async function fetchJson(url) {
  const resp = await fetch(url, { cache: 'no-store' });
  if (!resp.ok) throw new Error(`Failed to fetch ${url}`);
  return resp.json();
}

async function fetchLoaderStatus(entry) {
  const key = entry.branch;
  if (loaderStatus.value[key]) return;
  try {
    const data = await fetchJson(`${rawBase}/${entry.branch}/loader-index.json`);
    const loaders = data.loaders || {};
    loaderStatus.value[key] = {
      fabric: hasLoader(loaders.fabric),
      quilt: hasLoader(loaders.quilt),
      forge: hasLoader(loaders.forge),
      neoforge: hasLoader(loaders.neoforge),
    };
  } catch {
    loaderStatus.value[key] = {
      fabric: false,
      quilt: false,
      forge: false,
      neoforge: false,
    };
  }
}

function hasLoader(entry) {
  if (!entry) return false;
  const loader = Array.isArray(entry.loader) ? entry.loader.length : 0;
  const installer = Array.isArray(entry.installer) ? entry.installer.length : 0;
  return loader > 0 || installer > 0;
}

function sanitizeVersion(value) {
  let out = '';
  let prevDash = false;
  for (const ch of value) {
    let normalized = null;
    if (/[a-z0-9]/i.test(ch)) {
      normalized = ch.toLowerCase();
    } else if (ch === '.' || ch === '-' || ch === '_') {
      normalized = ch === '.' ? '.' : '-';
    } else if (/\s/.test(ch)) {
      normalized = '-';
    }

    if (!normalized) continue;
    if (normalized === '-') {
      if (prevDash) continue;
      prevDash = true;
      out += '-';
    } else {
      prevDash = false;
      out += normalized;
    }
  }
  return out.replace(/^\.+|\.+$/g, '').replace(/^-+|-+$/g, '');
}

async function ensureLoaderStatuses(entries) {
  const queue = entries.filter((entry) => !loaderStatus.value[entry.branch]);
  if (!queue.length) return;

  const workers = Array.from({ length: statusWorkers }, async () => {
    while (queue.length) {
      const entry = queue.shift();
      if (!entry) return;
      await fetchLoaderStatus(entry);
    }
  });

  await Promise.all(workers);
  storeStatusCache(loaderStatus.value);
}

async function init() {
  overallStatus.value = 'loading';
  try {
    errorMessage.value = '';
    loaderStatus.value = loadStatusCache();
    await fetchManifestOrder();
    const result = await fetchBranches();
    branches.value = result.items;
    lastSync.value = formatTimestamp(result.timestamp);

    await ensureLoaderStatuses(filteredEntries.value);

    if (filteredEntries.value.length) {
      await selectEntry(filteredEntries.value[0]);
    } else {
      overallStatus.value = 'empty';
    }
  } catch (err) {
    console.error(err);
    overallStatus.value = 'error';
    errorMessage.value = err?.message || 'Failed to load data';
  }
}

async function refresh() {
  localStorage.removeItem(cacheKey);
  localStorage.removeItem(statusCacheKey);
  localStorage.removeItem(manifestCacheKey);
  errorMessage.value = '';
  await init();
}

watch(filteredEntries, (list) => {
  if (!list.length) return;
  ensureLoaderStatuses(list);
  const hasActive = list.some((entry) => entry.branch === activeBranch.value);
  if (!activeBranch.value || !hasActive) {
    selectEntry(list[0]);
  }
});

onMounted(() => {
  init();
});
</script>

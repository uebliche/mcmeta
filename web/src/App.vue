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
      <aside class="sidebar">
        <div class="panel">
          <label class="label" for="search">Search versions</label>
          <input
            id="search"
            v-model="search"
            type="search"
            placeholder="1.20, 24w, rc"
          />
          <label class="toggle">
            <input v-model="includeSnapshots" type="checkbox" />
            <span>Include snapshots</span>
          </label>
          <div class="stat-row">
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
        <div class="panel list-panel">
          <div class="version-list">
            <div v-if="filteredVersions.length === 0" class="version-item">
              No matches
            </div>
            <div
              v-for="(version, index) in filteredVersions"
              :key="version"
              class="version-item"
              :class="{ active: version === activeVersion }"
              :style="{ animationDelay: `${index * 0.02}s` }"
              @click="selectVersion(version)"
            >
              <div>{{ version }}</div>
              <div class="version-meta">
                <span>{{ isSnapshot(version) ? 'snapshot' : 'release' }}</span>
                <span>mc/{{ version }}</span>
              </div>
            </div>
          </div>
        </div>
      </aside>

      <section class="content">
        <div class="panel hero">
          <div>
            <h2>{{ activeVersion || 'Select a version' }}</h2>
            <p class="mono">{{ activeVersion ? `mc/${activeVersion}` : 'mc/—' }}</p>
          </div>
          <div class="status">
            <span :class="['badge', overallStatus]">{{ overallStatus }}</span>
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
              </div>
            </div>
          </div>

          <div class="panel">
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
const cacheTtlMs = 1000 * 60 * 30;

const search = ref('');
const includeSnapshots = ref(true);
const branches = ref([]);
const activeVersion = ref('');
const activeTab = ref('overview');
const overallStatus = ref('idle');
const lastSync = ref('-');

const loaderIndex = ref(null);
const artifacts = ref(null);
const meta = ref(null);

const filteredVersions = computed(() => {
  let list = branches.value
    .filter((branch) => branch.name.startsWith('mc/'))
    .map((branch) => branch.name.replace('mc/', ''))
    .filter((version) => (includeSnapshots.value ? true : !isSnapshot(version)))
    .filter((version) => version.toLowerCase().includes(search.value.toLowerCase()));
  list.sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  return list;
});

const versionCount = computed(() => filteredVersions.value.length);

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

const fabricApiPreview = computed(() => {
  const versions = fabricApi.value?.versions || [];
  return versions.slice(0, 8).map((item) => item.version_number);
});

const fabricApiMore = computed(() => {
  const total = fabricApi.value?.versions?.length || 0;
  return Math.max(total - 8, 0);
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

function storeCache(items) {
  localStorage.setItem(
    cacheKey,
    JSON.stringify({ timestamp: Date.now(), items })
  );
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

function setStatus(text, state = 'neutral') {
  overallStatus.value = state === 'neutral' ? text : state;
}

async function selectVersion(version) {
  activeVersion.value = version;
  setStatus('loading', 'neutral');

  try {
    const [loader, artifactsData, metaData] = await Promise.all([
      fetchJson(`${rawBase}/mc/${version}/loader-index.json`),
      fetchJson(`${rawBase}/mc/${version}/artifacts.json`),
      fetchJson(`${rawBase}/mc/${version}/meta.json`),
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
    loaderIndex.value = null;
    artifacts.value = null;
    meta.value = null;
  }
}

async function fetchJson(url) {
  const resp = await fetch(url, { cache: 'no-store' });
  if (!resp.ok) throw new Error(`Failed to fetch ${url}`);
  return resp.json();
}

async function init() {
  overallStatus.value = 'loading';
  try {
    const result = await fetchBranches();
    branches.value = result.items;
    lastSync.value = formatTimestamp(result.timestamp);

    if (filteredVersions.value.length) {
      await selectVersion(filteredVersions.value[0]);
    } else {
      overallStatus.value = 'empty';
    }
  } catch (err) {
    console.error(err);
    overallStatus.value = 'error';
  }
}

async function refresh() {
  localStorage.removeItem(cacheKey);
  await init();
}

watch(filteredVersions, (list) => {
  if (!list.length) return;
  if (!activeVersion.value || !list.includes(activeVersion.value)) {
    selectVersion(list[0]);
  }
});

onMounted(() => {
  init();
});
</script>

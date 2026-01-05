const repoOwner = "uebliche";
const repoName = "mcmeta";
const apiBase = `https://api.github.com/repos/${repoOwner}/${repoName}`;
const rawBase = `https://raw.githubusercontent.com/${repoOwner}/${repoName}`;
const cacheKey = "mcmeta-branches-v1";
const cacheTtlMs = 1000 * 60 * 30;

const versionList = document.getElementById("version-list");
const searchInput = document.getElementById("search");
const showSnapshots = document.getElementById("show-snapshots");
const versionCount = document.getElementById("version-count");
const lastSync = document.getElementById("last-sync");
const refreshButton = document.getElementById("refresh");

const activeVersion = document.getElementById("active-version");
const activeBranch = document.getElementById("active-branch");
const overallStatus = document.getElementById("overall-status");
const loaderSummary = document.getElementById("loader-summary");
const artifactSummary = document.getElementById("artifact-summary");
const sourceHealth = document.getElementById("source-health");
const metaNotes = document.getElementById("meta-notes");
const rawLoader = document.getElementById("raw-loader");
const rawArtifacts = document.getElementById("raw-artifacts");
const rawMeta = document.getElementById("raw-meta");

let branches = [];
let active = null;

function isSnapshot(version) {
  return !/^\d+\.\d+(\.\d+)?$/.test(version);
}

function setStatus(text, state) {
  overallStatus.textContent = text;
  overallStatus.className = `badge ${state || "neutral"}`;
}

function formatTimestamp(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  return date.toLocaleString();
}

function loadCache() {
  const cached = localStorage.getItem(cacheKey);
  if (!cached) {
    return null;
  }
  try {
    const parsed = JSON.parse(cached);
    if (Date.now() - parsed.timestamp > cacheTtlMs) {
      return null;
    }
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
  if (cached) {
    return cached.items;
  }

  let page = 1;
  let all = [];

  while (true) {
    const resp = await fetch(`${apiBase}/branches?per_page=100&page=${page}`);
    if (!resp.ok) {
      throw new Error("Failed to load branches");
    }
    const data = await resp.json();
    all = all.concat(data);
    if (data.length < 100) {
      break;
    }
    page += 1;
  }

  storeCache(all);
  return all;
}

function buildVersionList() {
  const query = searchInput.value.trim().toLowerCase();
  const includeSnapshots = showSnapshots.checked;
  const filtered = branches
    .filter((branch) => branch.name.startsWith("mc/"))
    .map((branch) => branch.name.replace("mc/", ""))
    .filter((version) => (includeSnapshots ? true : !isSnapshot(version)))
    .filter((version) => version.toLowerCase().includes(query));

  versionCount.textContent = `${filtered.length}`;
  versionList.innerHTML = "";

  if (filtered.length === 0) {
    versionList.innerHTML = "<div class=\"version-item\">No matches</div>";
    return;
  }

  filtered.forEach((version, index) => {
    const item = document.createElement("div");
    item.className = "version-item";
    if (active === version) {
      item.classList.add("active");
    }
    item.style.animationDelay = `${index * 0.02}s`;
    item.innerHTML = `
      <div>${version}</div>
      <div class="version-meta">
        <span>${isSnapshot(version) ? "snapshot" : "release"}</span>
        <span>mc/${version}</span>
      </div>
    `;
    item.addEventListener("click", () => selectVersion(version));
    versionList.appendChild(item);
  });
}

async function selectVersion(version) {
  active = version;
  setStatus("loading", "neutral");
  activeVersion.textContent = version;
  activeBranch.textContent = `mc/${version}`;
  buildVersionList();

  try {
    const [loader, artifacts, meta] = await Promise.all([
      fetchJson(`${rawBase}/mc/${version}/loader-index.json`),
      fetchJson(`${rawBase}/mc/${version}/artifacts.json`),
      fetchJson(`${rawBase}/mc/${version}/meta.json`),
    ]);

    renderLoader(loader);
    renderArtifacts(artifacts);
    renderMeta(meta);

    rawLoader.textContent = JSON.stringify(loader, null, 2);
    rawArtifacts.textContent = JSON.stringify(artifacts, null, 2);
    rawMeta.textContent = JSON.stringify(meta, null, 2);

    const overall = Object.values(meta.sources || {}).reduce((acc, value) => {
      if (value === "error") return "error";
      if (value === "partial" && acc !== "error") return "partial";
      return acc;
    }, "ok");

    setStatus(overall, overall);
  } catch (err) {
    console.error(err);
    setStatus("error", "error");
    loaderSummary.innerHTML = "<p>Failed to load data.</p>";
    artifactSummary.innerHTML = "";
    sourceHealth.innerHTML = "";
    metaNotes.innerHTML = "";
    rawLoader.textContent = "";
    rawArtifacts.textContent = "";
    rawMeta.textContent = "";
  }
}

async function fetchJson(url) {
  const resp = await fetch(url, { cache: "no-store" });
  if (!resp.ok) {
    throw new Error(`Failed to fetch ${url}`);
  }
  return resp.json();
}

function renderLoader(loader) {
  const loaders = loader.loaders || {};
  loaderSummary.innerHTML = "";

  const entries = [
    { key: "fabric", label: "Fabric" },
    { key: "quilt", label: "Quilt" },
    { key: "forge", label: "Forge" },
    { key: "neoforge", label: "NeoForge" },
  ];

  entries.forEach(({ key, label }) => {
    const data = loaders[key] || { loader: [], installer: [] };
    const card = document.createElement("div");
    card.className = "loader-card";
    const loaderVersions = data.loader || [];
    const installerVersions = data.installer || [];

    card.innerHTML = `
      <h4>${label}</h4>
      <div class="chips">
        ${buildChips(loaderVersions)}
        ${installerVersions.length ? buildChips(installerVersions) : ""}
      </div>
    `;
    loaderSummary.appendChild(card);
  });
}

function renderArtifacts(artifacts) {
  artifactSummary.innerHTML = "";

  const minestom = artifacts.artifacts?.minestom;
  const fabricApi = artifacts.artifacts?.["fabric-api"];

  if (minestom) {
    const card = document.createElement("div");
    card.className = "artifact-card";
    card.innerHTML = `
      <h4>Minestom</h4>
      <div class="chips">${buildChips(minestom.versions || [])}</div>
      <div class="mono">${(minestom.coordinates || []).join(", ")}</div>
    `;
    artifactSummary.appendChild(card);
  }

  if (fabricApi) {
    const card = document.createElement("div");
    card.className = "artifact-card";
    const versionItems = (fabricApi.versions || [])
      .slice(0, 8)
      .map((v) => `<span class="chip">${v.version_number}</span>`)
      .join("");
    const moreCount = Math.max((fabricApi.versions || []).length - 8, 0);
    card.innerHTML = `
      <h4>Fabric API</h4>
      <div class="chips">${versionItems}${
      moreCount > 0 ? `<span class="chip">+${moreCount} more</span>` : ""
    }</div>
      <div class="mono">project ${fabricApi.project_id}</div>
    `;
    artifactSummary.appendChild(card);
  }
}

function renderMeta(meta) {
  sourceHealth.innerHTML = "";
  const sources = meta.sources || {};
  Object.keys(sources).forEach((key) => {
    const status = sources[key];
    const item = document.createElement("div");
    item.className = "health-item";
    item.innerHTML = `
      <span>${key}</span>
      <span class="badge ${status}">${status}</span>
    `;
    sourceHealth.appendChild(item);
  });

  const notes = meta.notes || [];
  if (notes.length) {
    metaNotes.innerHTML = `<strong>Notes</strong><ul>${notes
      .map((note) => `<li>${note}</li>`)
      .join("")}</ul>`;
  } else {
    metaNotes.innerHTML = "";
  }
}

function buildChips(items) {
  if (!items || items.length === 0) {
    return "<span class=\"chip\">n/a</span>";
  }
  return items.map((item) => `<span class=\"chip\">${item}</span>`).join("");
}

function handleTabs() {
  const tabs = document.querySelectorAll(".tab");
  const panels = document.querySelectorAll(".tab-panel");

  tabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      tabs.forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      const target = tab.dataset.tab;
      panels.forEach((panel) => {
        panel.classList.toggle("hidden", panel.dataset.panel !== target);
      });
    });
  });
}

async function init() {
  setStatus("loading", "neutral");
  handleTabs();

  try {
    branches = await fetchBranches();
    const cached = loadCache();
    lastSync.textContent = formatTimestamp(cached?.timestamp || Date.now());
    buildVersionList();
    const first = branches.find((branch) => branch.name.startsWith("mc/"));
    if (first) {
      await selectVersion(first.name.replace("mc/", ""));
    } else {
      setStatus("empty", "neutral");
    }
  } catch (err) {
    console.error(err);
    setStatus("error", "error");
    versionList.innerHTML = "<div class=\"version-item\">Failed to load</div>";
  }
}

searchInput.addEventListener("input", buildVersionList);
showSnapshots.addEventListener("change", buildVersionList);
refreshButton.addEventListener("click", () => {
  localStorage.removeItem(cacheKey);
  init();
});

init();

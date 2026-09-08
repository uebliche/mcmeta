import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const mode = process.argv[2];
const modes = new Set(['build', 'examples', 'publish-local', 'publish']);
if (!modes.has(mode)) throw new Error(`Unknown managed build action: ${mode}`);

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    stdio: 'inherit',
    windowsHide: true,
    ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

function gradle(args, env = process.env) {
  const wrapper = path.join(root, 'examples', 'platforms', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  // The Windows wrapper requires cmd; all arguments below are fixed by this script.
  run(wrapper, ['--no-daemon', '--console=plain', ...args], { env, shell: process.platform === 'win32' });
}

if (mode === 'examples') {
  // Preserve the complete version matrix previously executed by GitHub Actions.
  for (const version of ['1.21.11', '1.21.4', '1.20.1']) {
    console.log(`Building all platform examples for Minecraft ${version}`);
    gradle(['-p', 'examples/platforms', 'build', `-Pmcmeta.minecraftVersion=${version}`]);
  }
} else if (mode === 'publish') {
  for (const key of ['GITHUB_ACTOR', 'GITHUB_TOKEN']) {
    if (!process.env[key]?.trim() || process.env[key].startsWith('{')) {
      throw new Error(`Configure ${key === 'GITHUB_ACTOR' ? 'MCMETA_PACKAGES_USER' : 'MCMETA_PACKAGES_TOKEN'} in Uebliche.dev before publishing.`);
    }
  }
  const revision = spawnSync('git', ['rev-parse', '--short=7', 'HEAD'], { cwd: root, encoding: 'utf8', windowsHide: true });
  if (revision.status !== 0 || !/^[0-9a-f]{7,}$/.test(revision.stdout.trim())) throw new Error('Cannot determine plugin source revision');
  const version = `${new Date().toISOString().slice(0, 10).replaceAll('-', '.')}-${revision.stdout.trim().slice(0, 7)}`;
  console.log(`Publishing mcmeta Gradle plugin ${version}`);
  gradle(['-p', 'gradle-plugin', 'build', 'publish'], { ...process.env, MCMETA_PLUGIN_VERSION: version });
} else {
  gradle(['-p', 'gradle-plugin', 'build', ...(mode === 'publish-local' ? ['publishToMavenLocal'] : [])]);
}

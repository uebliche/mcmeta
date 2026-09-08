import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const mode = process.argv[2];
const modes = new Set(['build', 'examples', 'publish-local']);
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

function gradle(args) {
  const wrapper = path.join(root, 'examples', 'platforms', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  // The Windows wrapper requires cmd; all arguments below are fixed by this script.
  run(wrapper, ['--no-daemon', '--console=plain', ...args], { shell: process.platform === 'win32' });
}

if (mode === 'examples') {
  // Preserve the complete version matrix previously executed by GitHub Actions.
  for (const version of ['1.21.11', '1.21.4', '1.20.1']) {
    console.log(`Building all platform examples for Minecraft ${version}`);
    gradle(['-p', 'examples/platforms', 'build', `-Pmcmeta.minecraftVersion=${version}`]);
  }
} else {
  gradle(['-p', 'gradle-plugin', 'build', ...(mode === 'publish-local' ? ['publishToMavenLocal'] : [])]);
}

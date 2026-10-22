import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { signingEnvironment } from './android-signing.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tasks = { debug: 'assembleDebug', test: 'testDebugUnitTest', release: 'assembleRelease' };
const mode = process.argv[2];
if (!Object.hasOwn(tasks, mode)) throw new Error('Expected debug, test, or release.');
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const version = spawnSync(java, ['-version'], { encoding: 'utf8' });
const major = Number(`${version.stderr ?? ''}${version.stdout ?? ''}`.match(/version "(\d+)/)?.[1]);
if (version.error || version.status !== 0 || major < 21 || !major) throw new Error('Install JDK 21 or newer and set JAVA_HOME.');
const env = mode === 'release' ? await signingEnvironment(root) : process.env;
const windows = process.platform === 'win32';
const result = spawnSync(windows ? 'gradlew.bat' : './gradlew', [tasks[mode], '--no-daemon'], {
  cwd: join(root, 'android'), env, stdio: 'inherit', shell: windows,
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;

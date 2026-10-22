import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { chmod, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

async function exists(path) {
  try { await stat(path); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

// Called only by an explicit android:release invocation. Never print credentials.
export async function signingEnvironment(root) {
  const { NOTEPIT_KEYSTORE, NOTEPIT_STORE_PASSWORD } = process.env;
  if (NOTEPIT_KEYSTORE || NOTEPIT_STORE_PASSWORD) {
    if (!NOTEPIT_KEYSTORE || !NOTEPIT_STORE_PASSWORD) throw new Error('Set both NOTEPIT_KEYSTORE and NOTEPIT_STORE_PASSWORD.');
    const keystore = resolve(root, NOTEPIT_KEYSTORE);
    if (!await exists(keystore)) throw new Error('The configured signing keystore does not exist.');
    return { ...process.env, NOTEPIT_KEYSTORE: keystore };
  }
  if (process.env.CI) throw new Error('CI release builds require NOTEPIT_KEYSTORE and NOTEPIT_STORE_PASSWORD.');
  const directory = join(root, '.signing');
  const keystore = join(directory, 'keystore.jks');
  const configPath = join(directory, 'config.json');
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await chmod(directory, 0o700);
  const hasConfig = await exists(configPath);
  const hasKeystore = await exists(keystore);
  if (hasKeystore && !hasConfig) throw new Error('Signing config is missing. Restore .signing/config.json from your private backup; do not replace the existing key.');
  let config;
  if (hasConfig) {
    await chmod(configPath, 0o600);
    try { config = JSON.parse(await readFile(configPath, 'utf8')); }
    catch { throw new Error('Cannot read private signing configuration. Restore .signing/config.json from your private backup.'); }
    if (config?.alias !== 'notepit' || typeof config.password !== 'string' || !config.password) throw new Error('Invalid private signing configuration.');
    if (!hasKeystore) throw new Error('Signing keystore is missing. Restore .signing/keystore.jks from your private backup.');
  } else {
    config = { alias: 'notepit', password: randomBytes(32).toString('base64url') };
    await writeFile(configPath, `${JSON.stringify(config)}\n`, { mode: 0o600, flag: 'wx' });
    const keytool = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'keytool.exe' : 'keytool') : 'keytool';
    // keytool reads the password from its environment, not process arguments or logs.
    const previousMask = process.umask(0o077);
    let result;
    try {
      result = spawnSync(keytool, [
        '-genkeypair', '-noprompt', '-storetype', 'JKS', '-keystore', keystore,
        '-alias', 'notepit', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=NotePit, O=NotePit', '-storepass:env', 'NOTEPIT_SIGNING_PASSWORD',
        '-keypass:env', 'NOTEPIT_SIGNING_PASSWORD',
      ], { env: { ...process.env, NOTEPIT_SIGNING_PASSWORD: config.password }, stdio: 'ignore' });
    } finally {
      process.umask(previousMask);
    }
    if (result.error || result.status !== 0) throw new Error('keytool could not create the signing key. Private config was retained; restore a complete .signing backup or remove the newly created incomplete directory before retrying.');
    console.log('Created private release signing key in .signing/. Back up this directory securely; future updates require the same key.');
  }
  await chmod(keystore, 0o600);
  return { ...process.env, NOTEPIT_KEYSTORE: keystore, NOTEPIT_STORE_PASSWORD: config.password };
}

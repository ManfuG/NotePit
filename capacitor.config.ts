import type { CapacitorConfig } from '@capacitor/cli';
const config: CapacitorConfig = {
  appId: 'io.notepit.app',
  appName: 'NotePit',
  webDir: 'dist',
  android: { allowMixedContent: false, backgroundColor: '#101714' },
};
export default config;

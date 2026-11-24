// Standalone renderer build (no Electron Forge), used for the Android/Capacitor app.
// Mirrors vite.main-renderer.config.js; output goes to www/ (capacitor.config.json webDir).
import { defineConfig } from 'vite';
import inject from '@rollup/plugin-inject';

export default defineConfig({
  root: '.',
  base: './',
  assetsInclude: ['**/*.gltf', '**/*.glb'],
  build: {
    // Android 11 ships WebView 83; lower syntax so devices without WebView updates still run.
    target: 'chrome83',
    outDir: 'www',
    emptyOutDir: true,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    chunkSizeWarningLimit: 10240,
  },
  plugins: [
    inject({
      $: 'jquery',
      jQuery: 'jquery',
    }),
  ],
  resolve: {
    preserveSymlinks: true,
  },
  clearScreen: false,
});

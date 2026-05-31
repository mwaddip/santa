import { defineConfig } from 'tsup'

export default defineConfig({
  entry: ['src/index.ts', 'src/runner.ts'],
  format: ['esm'],
  target: 'es2022',
  clean: true,
  banner: { js: '#!/usr/bin/env node' },
})

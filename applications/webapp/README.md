# VidIngest webapp

The operator console for `vidingest-server`: start ingest runs, watch phases advance, and diagnose
the ones that fail. Angular 22, zoneless, standalone components and signals, no component library.

```bash
npm install
npm start          # http://localhost:4200, proxying /vidingest to the server on :8051
npm test           # vitest, no watch
npm run build      # production bundle into dist/webapp
npm run api:gen    # re-fetch the live OpenAPI spec, then regenerate src/app/api/generated
```

`npm run api:gen` needs the server running. Commit `openapi/vidingest.json` and the regenerated
client together, and never hand-edit `src/app/api/generated/`.

Screens, design tokens, the measured API findings behind them and the deployment path are in
[docs/vidingest/VidIngest - Web UI.md](../../docs/vidingest/VidIngest%20-%20Web%20UI.md).

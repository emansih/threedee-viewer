// Serves the built web app. No dependencies; needs Node 18+.
//
//   node webApp/server/serve.mjs [port] [appDir]
//
// A shared link is  http://127.0.0.1:<port>/v?=<base64url>, where the payload is the gzipped GLB and image
// (made by the app's "Copy link" button). Every path that isn't a file falls through to the app, which reads
// the link itself; nothing is stored on the server, the model and image travel inside the URL.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { dirname, extname, join, normalize, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const port = Number(process.argv[2] ?? process.env.PORT ?? 8080)
const appDir = process.argv[3] ?? join(here, '..', '..', 'build', 'tasks', '_webApp_buildWasmJsAppWasmJsDebug')

const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.wasm': 'application/wasm', '.json': 'application/json', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.map': 'application/json',
}

async function send(res, file, status = 200) {
  const body = await readFile(file)
  res.writeHead(status, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-cache' })
  res.end(body)
}

function inside(root, path) {
  const file = normalize(join(root, path))
  return file.startsWith(root + sep) ? file : null
}

const server = createServer({ maxHeaderSize: 8 * 1024 * 1024 }, async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname)
    const file = inside(appDir, path)
    if (file && extname(file)) {
      try { return await send(res, file) } catch { /* not a file: 404 below for assets */ }
      res.writeHead(404).end('Not found')
      return
    }
    await send(res, join(appDir, 'index.html')) // every other path: the app
  } catch (e) {
    res.writeHead(500).end(String(e))
  }
})
server.listen(port, '0.0.0.0', () => console.log(`ThreeDee Viewer on http://127.0.0.1:${port}/`))

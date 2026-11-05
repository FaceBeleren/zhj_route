import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

test('应用初始化时总览监听不会提前读取未声明的状态', async () => {
  globalThis.window = { sessionStorage: { getItem: () => null } }
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  try {
    const { default: App } = await server.ssrLoadModule('/src/App.vue')
    const html = await renderToString(createSSRApp(App))
    assert.ok(html.length > 0)
  } finally {
    await server.close()
    delete globalThis.window
  }
})

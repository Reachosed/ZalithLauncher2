/**
 * ZL2 Remote Relay —— Cloudflare Worker 版中继
 *
 * 作用只有一件事：把一条 WebSocket 连接上收到的字节，原样转发给同一个
 * 「房间」里的另一条连接。它看不到任何有效内容——两端之间是端到端加密的
 * （AES-256-GCM，密钥由 6 位配对码经 PBKDF2 派生，从不经过这里）。
 *
 * 部署：见同目录 README.md，或直接 `npx wrangler deploy`。
 * 免费额度：Workers 10 万请求/天，Durable Objects 对 WebSocket 消息按
 * 20:1 折算请求数，个人远控一天几百条消息，用不到零头。
 */

const ROOM_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("zl2-remote-relay ok", {
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }

    if (url.pathname !== "/ws") {
      return new Response("not found", { status: 404 });
    }

    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("expected websocket upgrade", { status: 426 });
    }

    const room = url.searchParams.get("room") || "";
    if (!ROOM_PATTERN.test(room)) {
      return new Response("bad room", { status: 400 });
    }

    // 可选的自定义口令：在 Worker 设置里加一个变量 RELAY_KEY 即可开启。
    // 不设置就完全开放（房间名本身已经由配对码派生，不可猜）。
    if (env.RELAY_KEY && url.searchParams.get("key") !== env.RELAY_KEY) {
      return new Response("forbidden", { status: 403 });
    }

    const roomId = env.ROOMS.idFromName(room);
    return env.ROOMS.get(roomId).fetch(request);
  },
};

/**
 * 一个房间 = 一个 Durable Object 实例。
 * 用 Hibernation API：房间里没人时实例可以休眠，不计时长费。
 */
export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // 必须在返回 101 之前 accept，否则消息会丢
    this.state.acceptWebSocket(server);

    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    // 只在同房间内广播，且不回传给发送者（省一半流量）
    for (const peer of this.state.getWebSockets()) {
      if (peer === ws) continue;
      try {
        peer.send(message);
      } catch (e) {
        // 对端已断开，等 webSocketClose 清理即可
      }
    }
  }

  webSocketClose(ws, code, reason, wasClean) {
    try {
      ws.close(code === 1006 ? 1000 : code, reason);
    } catch (e) {
      // 已经关了，忽略
    }
  }

  webSocketError(ws, error) {
    try {
      ws.close(1011, "relay error");
    } catch (e) {
      // 忽略
    }
  }
}

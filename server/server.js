import http from 'node:http';
import crypto from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';

const PORT = Number(process.env.PORT || 8080);
const homes = new Map();       // id -> { ws, token }
const sessions = new Map();    // sessionId -> { homeId, viewer }

function send(ws, obj) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}
function fail(ws, message) { send(ws, { type: 'error', message }); }

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ ok: true, homes: homes.size, sessions: sessions.size }));
  }
  res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('HomeCam signaling server');
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 256 * 1024 });

wss.on('connection', (ws, req) => {
  const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const role = u.searchParams.get('role') || '';
  const id = (u.searchParams.get('id') || '').trim();
  const token = u.searchParams.get('token') || '';
  const target = (u.searchParams.get('target') || '').trim();

  ws.role = role;
  ws.homeId = null;
  ws.sessionId = null;

  if (role === 'home') {
    if (!/^\d{8}$/.test(id) || !/^[a-f0-9]{64}$/.test(token)) {
      fail(ws, 'Invalid home credentials'); return ws.close(1008, 'bad credentials');
    }
    const old = homes.get(id);
    if (old && old.token !== token) {
      fail(ws, 'This ID is already in use'); return ws.close(1008, 'id collision');
    }
    if (old?.ws && old.ws !== ws) old.ws.close(4000, 'replaced');
    homes.set(id, { ws, token });
    ws.homeId = id;
    send(ws, { type: 'registered', id });
  } else if (role === 'viewer') {
    const home = homes.get(target);
    if (!home || home.token !== token) {
      fail(ws, 'Дом не найден или неверный пароль'); return ws.close(1008, 'unauthorized');
    }
    const sessionId = crypto.randomUUID();
    sessions.set(sessionId, { homeId: target, viewer: ws });
    ws.sessionId = sessionId;
    ws.homeId = target;
    send(ws, { type: 'session', sessionId });
    send(home.ws, { type: 'viewer-request', sessionId });
  } else {
    fail(ws, 'Unknown role'); return ws.close(1008, 'bad role');
  }

  ws.on('message', raw => {
    let m;
    try { m = JSON.parse(raw.toString()); } catch { return; }
    const sid = m.sessionId;
    if (!sid || typeof sid !== 'string') return;
    const s = sessions.get(sid);
    if (!s) return;
    const home = homes.get(s.homeId);
    if (!home) return;

    if (ws.role === 'home' && ws.homeId === s.homeId) {
      if (['offer','ice'].includes(m.type)) send(s.viewer, m);
    } else if (ws.role === 'viewer' && s.viewer === ws) {
      if (['answer','ice','control'].includes(m.type)) send(home.ws, m);
      if (m.type === 'disconnect') {
        send(home.ws, { type: 'viewer-disconnect', sessionId: sid });
        sessions.delete(sid);
      }
    }
  });

  ws.on('close', () => {
    if (ws.role === 'home' && ws.homeId) {
      const h = homes.get(ws.homeId);
      if (h?.ws === ws) homes.delete(ws.homeId);
      for (const [sid, s] of sessions) {
        if (s.homeId === ws.homeId) { fail(s.viewer, 'Дом отключился'); s.viewer.close(1012, 'home offline'); sessions.delete(sid); }
      }
    } else if (ws.role === 'viewer' && ws.sessionId) {
      const s = sessions.get(ws.sessionId);
      if (s?.viewer === ws) {
        const h = homes.get(s.homeId);
        send(h?.ws, { type: 'viewer-disconnect', sessionId: ws.sessionId });
        sessions.delete(ws.sessionId);
      }
    }
  });
});

server.listen(PORT, '0.0.0.0', () => console.log(`HomeCam signaling on :${PORT}`));

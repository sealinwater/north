import http from 'node:http';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const rawEnvPort = Number(process.env.PORT);
const port = Number(
  process.env.DEFAULT_APP_PORT ||
    (Number.isFinite(rawEnvPort) && rawEnvPort > 0 && rawEnvPort !== 8080 ? rawEnvPort : 3000)
);
const PLUGIN_VERSION = 50;

interface CacheEntry<T> {
  expiresAt: number;
  data: T;
}

const skipsCache = new Map<string, CacheEntry<unknown>>();
const subsCache = new Map<string, CacheEntry<unknown>>();
const crashUploadRateMap = new Map<string, number[]>();
const CRASH_LOGS_DIR = path.resolve(__dirname, 'crash_logs');
const MAX_CRASH_BODY_BYTES = 128 * 1024;
const MAX_STORED_CRASH_FILES = 50;

function redactSensitiveText(input: string): string {
  return input
    .replace(/(api_token|token|passkey|password|pass|key)=([^&\s"']+)/gi, '$1=[REDACTED]')
    .replace(/(Bearer\s+)([A-Za-z0-9._\-+/=]+)/gi, '$1[REDACTED]')
    .replace(/(\/announce\/)([0-9a-f]{16,})/gi, '$1[REDACTED]')
    .replace(/(\/torrents\/download\/\d+\.)([0-9a-f]{16,})/gi, '$1[REDACTED]');
}

function getClientIp(req: http.IncomingMessage): string {
  const fwd = req.headers['x-forwarded-for'];
  const first = Array.isArray(fwd) ? fwd[0] : fwd;
  if (first) return first.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function isCrashUploadRateLimited(ip: string): boolean {
  const now = Date.now();
  const windowMs = 10 * 60_000;
  const recent = (crashUploadRateMap.get(ip) || []).filter((ts) => now - ts < windowMs);
  if (recent.length >= 20) {
    crashUploadRateMap.set(ip, recent);
    return true;
  }
  recent.push(now);
  crashUploadRateMap.set(ip, recent);
  if (crashUploadRateMap.size > 500) {
    const oldestKey = crashUploadRateMap.keys().next().value;
    if (oldestKey !== undefined) crashUploadRateMap.delete(oldestKey);
  }
  return false;
}

function pruneCrashLogsDir(): void {
  try {
    if (!fs.existsSync(CRASH_LOGS_DIR)) return;
    const files = fs
      .readdirSync(CRASH_LOGS_DIR)
      .filter((f) => f.endsWith('.json'))
      .map((f) => {
        const full = path.resolve(CRASH_LOGS_DIR, f);
        const mtime = fs.existsSync(full) ? fs.statSync(full).mtimeMs : 0;
        return { full, mtime };
      })
      .sort((a, b) => b.mtime - a.mtime);
    for (let i = MAX_STORED_CRASH_FILES; i < files.length; i++) {
      const target = files[i].full;
      if (target.startsWith(CRASH_LOGS_DIR + path.sep)) {
        fs.unlinkSync(target);
      }
    }
  } catch {
    // Ignore pruning errors
  }
}

function getCached<T>(map: Map<string, CacheEntry<T>>, key: string): T | null {
  const hit = map.get(key);
  if (!hit) return null;
  if (Date.now() > hit.expiresAt) {
    map.delete(key);
    return null;
  }
  return hit.data;
}

function setCached<T>(map: Map<string, CacheEntry<T>>, key: string, data: T, ttlMs: number): void {
  if (map.size > 200) {
    const firstKey = map.keys().next().value;
    if (firstKey !== undefined) map.delete(firstKey);
  }
  map.set(key, { expiresAt: Date.now() + ttlMs, data });
}

function getBaseUrl(req: http.IncomingMessage): string {
  if (process.env.APP_URL && process.env.APP_URL !== 'MY_APP_URL') {
    return process.env.APP_URL.replace(/\/$/, '');
  }
  const forwardedHost = req.headers['x-forwarded-host'];
  const host = (Array.isArray(forwardedHost) ? forwardedHost[0] : forwardedHost) || req.headers.host || `localhost:${port}`;
  const forwardedProto = req.headers['x-forwarded-proto'];
  const proto = (Array.isArray(forwardedProto) ? forwardedProto[0] : forwardedProto) || 'https';
  return `${proto}://${host}`;
}

function sendJson(res: http.ServerResponse, statusCode: number, payload: unknown): void {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, HEAD, OPTIONS',
    'Access-Control-Allow-Headers': 'Origin, X-Requested-With, Content-Type, Accept, Range, Authorization'
  });
  res.end(body);
}

function serveFileWithRange(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  filePath: string,
  contentType: string,
  extraHeaders: Record<string, string> = {}
): void {
  if (!fs.existsSync(filePath)) {
    sendJson(res, 404, { error: 'Asset not found' });
    return;
  }

  const stat = fs.statSync(filePath);
  const totalSize = stat.size;
  const rangeHeader = req.headers.range;

  const baseHeaders: Record<string, string> = {
    'Content-Type': contentType,
    'Accept-Ranges': 'bytes',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
    'Access-Control-Allow-Headers': 'Range, If-Range, Content-Type, Origin, Accept',
    'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges',
    ...extraHeaders
  };

  if (rangeHeader && rangeHeader.startsWith('bytes=')) {
    const spec = rangeHeader.replace('bytes=', '').trim();
    const [startRaw, endRaw] = spec.split('-');
    let start = startRaw ? Number.parseInt(startRaw, 10) : 0;
    let end = endRaw ? Number.parseInt(endRaw, 10) : totalSize - 1;

    if (!startRaw && endRaw) {
      const suffixLen = Number.parseInt(endRaw, 10);
      start = Math.max(0, totalSize - suffixLen);
      end = totalSize - 1;
    }

    if (Number.isNaN(start) || Number.isNaN(end) || start < 0 || start >= totalSize || end < start) {
      res.writeHead(416, {
        ...baseHeaders,
        'Content-Range': `bytes */${totalSize}`
      });
      res.end();
      return;
    }

    end = Math.min(end, totalSize - 1);
    const chunkSize = end - start + 1;

    res.writeHead(206, {
      ...baseHeaders,
      'Content-Range': `bytes ${start}-${end}/${totalSize}`,
      'Content-Length': String(chunkSize)
    });

    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    fs.createReadStream(filePath, { start, end }).pipe(res);
    return;
  }

  res.writeHead(200, {
    ...baseHeaders,
    'Content-Length': String(totalSize)
  });

  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  fs.createReadStream(filePath).pipe(res);
}

async function fetchJsonSafe(url: string, timeoutMs = 4500): Promise<unknown | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(url, {
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        'User-Agent': 'North-Proxy-API/27'
      }
    });
    if (!resp.ok) return null;
    const text = (await resp.text()).trim();
    if (!text.startsWith('{') && !text.startsWith('[')) return null;
    return JSON.parse(text);
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, HEAD, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept, Range, Authorization');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Length, Content-Range, Accept-Ranges');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const rawUrl = req.url || '/';
  const parsedUrl = new URL(rawUrl, `http://localhost:${port}`);
  const pathname = parsedUrl.pathname;

  // 1. Proxy API Health & Discovery
  if ((req.method === 'GET' || req.method === 'HEAD') && (pathname === '/api/health' || pathname === '/api')) {
    const baseUrl = getBaseUrl(req);
    const introExists = fs.existsSync(path.join(__dirname, 'public', 'north_intro.mp4'));
    sendJson(res, 200, {
      ok: true,
      service: 'north-proxy-api',
      version: PLUGIN_VERSION,
      introAvailable: introExists,
      endpoints: {
        health: `${baseUrl}/api/health`,
        introVideo: `${baseUrl}/api/intro.mp4`,
        skips: `${baseUrl}/api/skips`,
        subtitles: `${baseUrl}/api/subtitles`,
        torrent: `${baseUrl}/api/torrent`,
        proxy: `${baseUrl}/api/proxy`,
        crashLog: `${baseUrl}/api/crash-log`,
        crashLogs: `${baseUrl}/api/crash-logs`,
        plugins: `${baseUrl}/plugins.json`,
        repo: `${baseUrl}/repo.json`
      }
    });
    return;
  }

  // 1B. Automatic Crash & Debug Log Ingestion (POST /api/crash-log) and Recent Listing (GET /api/crash-logs)
  if (req.method === 'POST' && pathname === '/api/crash-log') {
    const clientIp = getClientIp(req);
    if (isCrashUploadRateLimited(clientIp)) {
      sendJson(res, 429, { ok: false, error: 'Too many crash reports from this client' });
      return;
    }

    let totalBytes = 0;
    const chunks: Buffer[] = [];
    let aborted = false;

    req.on('data', (chunk: Buffer) => {
      if (aborted) return;
      totalBytes += chunk.length;
      if (totalBytes > MAX_CRASH_BODY_BYTES) {
        aborted = true;
        sendJson(res, 413, { ok: false, error: 'Crash report exceeds 128 KB limit' });
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      if (aborted) return;
      try {
        const rawText = Buffer.concat(chunks).toString('utf-8').trim();
        if (!rawText.startsWith('{')) {
          sendJson(res, 400, { ok: false, error: 'Expected JSON object payload' });
          return;
        }
        const parsed = JSON.parse(rawText) as Record<string, unknown>;
        const sanitizedLogs = Array.isArray(parsed.recentLogs)
          ? parsed.recentLogs.slice(-200).map((line) => redactSensitiveText(String(line ?? '')).slice(0, 1000))
          : [];

        const rawPlayerState = (parsed.playerState && typeof parsed.playerState === 'object')
          ? (parsed.playerState as Record<string, unknown>)
          : {};

        const reportId = `crash_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
        const sanitizedReport = {
          id: reportId,
          receivedAtUtc: new Date().toISOString(),
          pluginVersion: Number(parsed.pluginVersion) || PLUGIN_VERSION,
          reportType: redactSensitiveText(String(parsed.reportType || 'unknown')).slice(0, 48),
          timestampUtc: redactSensitiveText(String(parsed.timestampUtc || '')).slice(0, 48),
          device: redactSensitiveText(String(parsed.device || '')).slice(0, 120),
          androidRelease: redactSensitiveText(String(parsed.androidRelease || '')).slice(0, 32),
          androidSdk: Number(parsed.androidSdk) || 0,
          threadName: redactSensitiveText(String(parsed.threadName || '')).slice(0, 80),
          summary: redactSensitiveText(String(parsed.summary || '')).slice(0, 400),
          exceptionClass: redactSensitiveText(String(parsed.exceptionClass || '')).slice(0, 160),
          exceptionMessage: redactSensitiveText(String(parsed.exceptionMessage || '')).slice(0, 400),
          stackTrace: redactSensitiveText(String(parsed.stackTrace || '')).slice(0, 6000),
          playerState: {
            stateSummary: redactSensitiveText(String(rawPlayerState.stateSummary || '')).slice(0, 240),
            lastValidPositionMs: Number(rawPlayerState.lastValidPositionMs) || 0,
            lastKnownDurationMs: Number(rawPlayerState.lastKnownDurationMs) || 0,
            lastRequestedSeekMs: Number(rawPlayerState.lastRequestedSeekMs) || -1,
            activeHashPrefix: redactSensitiveText(String(rawPlayerState.activeHashPrefix || '')).slice(0, 16)
          },
          recentLogs: sanitizedLogs
        };

        fs.mkdirSync(CRASH_LOGS_DIR, { recursive: true });
        const safeFileName = `${reportId.replace(/[^a-zA-Z0-9_-]/g, '')}.json`;
        const resolvedPath = path.resolve(CRASH_LOGS_DIR, safeFileName);
        if (!resolvedPath.startsWith(CRASH_LOGS_DIR + path.sep)) {
          sendJson(res, 400, { ok: false, error: 'Invalid report filename' });
          return;
        }
        fs.writeFileSync(resolvedPath, JSON.stringify(sanitizedReport, null, 2), 'utf-8');
        pruneCrashLogsDir();

        console.error(
          `[North CrashLog] ${sanitizedReport.reportType} v${sanitizedReport.pluginVersion} | ${sanitizedReport.summary}`
        );
        sendJson(res, 200, { ok: true, id: reportId });
      } catch {
        sendJson(res, 400, { ok: false, error: 'Invalid JSON payload' });
      }
    });
    return;
  }

  if (req.method === 'GET' && pathname === '/api/crash-logs') {
    try {
      const limit = Math.min(25, Math.max(1, Number(parsedUrl.searchParams.get('limit') || '10')));
      if (!fs.existsSync(CRASH_LOGS_DIR)) {
        sendJson(res, 200, { ok: true, count: 0, reports: [] });
        return;
      }
      const files = fs
        .readdirSync(CRASH_LOGS_DIR)
        .filter((f) => f.endsWith('.json'))
        .map((f) => {
          const full = path.resolve(CRASH_LOGS_DIR, f);
          const mtime = fs.existsSync(full) ? fs.statSync(full).mtimeMs : 0;
          return { full, mtime };
        })
        .sort((a, b) => b.mtime - a.mtime)
        .slice(0, limit);

      const reports = files
        .filter((entry) => entry.full.startsWith(CRASH_LOGS_DIR + path.sep))
        .map((entry) => {
          try {
            return JSON.parse(fs.readFileSync(entry.full, 'utf-8'));
          } catch {
            return null;
          }
        })
        .filter(Boolean);

      sendJson(res, 200, { ok: true, count: reports.length, reports });
    } catch {
      sendJson(res, 500, { ok: false, error: 'Failed reading crash logs' });
    }
    return;
  }

  // 2. Branded "NORTH" Pre-Roll Intro Video API (Range-capable MP4)
  if ((req.method === 'GET' || req.method === 'HEAD') && (pathname === '/api/intro.mp4' || pathname === '/north_intro.mp4')) {
    const introPath = path.join(__dirname, 'public', 'north_intro.mp4');
    serveFileWithRange(req, res, introPath, 'video/mp4', {
      'Cache-Control': 'public, max-age=86400',
      'Content-Disposition': 'inline; filename="north_intro.mp4"'
    });
    return;
  }

  // 3. Aggregated Skip Intro / Recap / Outro API (TheIntroDB + IntroDB)
  if (req.method === 'GET' && pathname === '/api/skips') {
    const tmdbId = (parsedUrl.searchParams.get('tmdb_id') || '').trim();
    const rawImdbId = (parsedUrl.searchParams.get('imdb_id') || '').trim();
    const imdbId = rawImdbId
      ? (rawImdbId.startsWith('tt') ? rawImdbId : `tt${rawImdbId.padStart(7, '0')}`)
      : '';
    const season = (parsedUrl.searchParams.get('season') || '').trim();
    const episode = (parsedUrl.searchParams.get('episode') || '').trim();
    const isMovie = parsedUrl.searchParams.get('type') === 'movie' || (!season && !episode);

    if (!tmdbId && !imdbId) {
      sendJson(res, 400, { error: 'Missing tmdb_id or imdb_id' });
      return;
    }

    const cacheKey = `${tmdbId}|${imdbId}|${season}|${episode}|${isMovie}`;
    const cached = getCached(skipsCache, cacheKey);
    if (cached) {
      sendJson(res, 200, cached);
      return;
    }

    const theIntroUrls: string[] = [];
    if (tmdbId) {
      theIntroUrls.push(
        isMovie
          ? `https://api.theintrodb.org/v4/media?tmdb_id=${encodeURIComponent(tmdbId)}`
          : `https://api.theintrodb.org/v4/media?tmdb_id=${encodeURIComponent(tmdbId)}&season=${encodeURIComponent(season)}&episode=${encodeURIComponent(episode)}`
      );
    }
    if (imdbId) {
      theIntroUrls.push(
        isMovie
          ? `https://api.theintrodb.org/v4/media?imdb_id=${encodeURIComponent(imdbId)}`
          : `https://api.theintrodb.org/v4/media?imdb_id=${encodeURIComponent(imdbId)}&season=${encodeURIComponent(season)}&episode=${encodeURIComponent(episode)}`
      );
    }

    const introDbUrl = !isMovie && imdbId && season && episode
      ? `https://api.introdb.app/segments?imdb_id=${encodeURIComponent(imdbId)}&season=${encodeURIComponent(season)}&episode=${encodeURIComponent(episode)}`
      : null;

    const [theIntroFirst, introDbData] = await Promise.all([
      (async () => {
        for (const u of theIntroUrls) {
          const data = await fetchJsonSafe(u, 3500);
          if (data) return data;
        }
        return null;
      })(),
      introDbUrl ? fetchJsonSafe(introDbUrl, 3500) : Promise.resolve(null)
    ]);

    const payload = {
      ok: true,
      theIntroDb: theIntroFirst,
      introDb: introDbData
    };
    setCached(skipsCache, cacheKey, payload, 10 * 60_000);
    sendJson(res, 200, payload);
    return;
  }

  // 4. Subtitle Lookup & CORS Subtitle Proxy API
  if ((req.method === 'GET' || req.method === 'HEAD') && pathname === '/api/subtitles') {
    const rawSubUrl = (parsedUrl.searchParams.get('url') || '').trim();
    if (rawSubUrl.startsWith('http://') || rawSubUrl.startsWith('https://')) {
      try {
        const upstream = await fetch(rawSubUrl, {
          headers: { 'User-Agent': 'North-Proxy-API/27' }
        });
        if (!upstream.ok) {
          sendJson(res, 502, { error: `Upstream subtitle HTTP ${upstream.status}` });
          return;
        }
        const buf = Buffer.from(await upstream.arrayBuffer());
        const contentType = rawSubUrl.toLowerCase().endsWith('.vtt')
          ? 'text/vtt; charset=utf-8'
          : 'application/x-subrip; charset=utf-8';
        res.writeHead(200, {
          'Content-Type': contentType,
          'Content-Length': String(buf.length),
          'Access-Control-Allow-Origin': '*'
        });
        res.end(req.method === 'HEAD' ? undefined : buf);
      } catch (err) {
        sendJson(res, 502, { error: String(err) });
      }
      return;
    }

    const rawImdbId = (parsedUrl.searchParams.get('imdb_id') || '').trim();
    const imdbId = rawImdbId
      ? (rawImdbId.startsWith('tt') ? rawImdbId : `tt${rawImdbId.padStart(7, '0')}`)
      : '';
    const season = (parsedUrl.searchParams.get('season') || '').trim();
    const episode = (parsedUrl.searchParams.get('episode') || '').trim();
    if (!imdbId) {
      sendJson(res, 400, { error: 'Missing url or imdb_id' });
      return;
    }

    const cacheKey = `${imdbId}|${season}|${episode}`;
    const cached = getCached(subsCache, cacheKey);
    if (cached) {
      sendJson(res, 200, cached);
      return;
    }

    const stremioUrl = season && episode
      ? `https://opensubtitles-v3.strem.io/subtitles/series/${encodeURIComponent(imdbId)}:${encodeURIComponent(season)}:${encodeURIComponent(episode)}.json`
      : `https://opensubtitles-v3.strem.io/subtitles/movie/${encodeURIComponent(imdbId)}.json`;

    const data = await fetchJsonSafe(stremioUrl, 5000);
    if (!data) {
      sendJson(res, 502, { error: 'Failed to reach subtitle provider' });
      return;
    }
    setCached(subsCache, cacheKey, data, 15 * 60_000);
    sendJson(res, 200, data);
    return;
  }

  // 5. Authenticated .torrent Fetch Proxy
  if (req.method === 'GET' && pathname === '/api/torrent') {
    const targetUrl = (parsedUrl.searchParams.get('url') || '').trim();
    const token = (parsedUrl.searchParams.get('token') || '').trim();
    if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
      sendJson(res, 400, { error: 'Invalid torrent url' });
      return;
    }

    try {
      const headers: Record<string, string> = {
        Accept: 'application/x-bittorrent, application/octet-stream, */*',
        'User-Agent': 'North-Proxy-API/27'
      };
      if (token) headers.Authorization = `Bearer ${token}`;
      const upstream = await fetch(targetUrl, { headers });
      if (!upstream.ok) {
        sendJson(res, upstream.status, { error: `Upstream HTTP ${upstream.status}` });
        return;
      }
      const bytes = Buffer.from(await upstream.arrayBuffer());
      res.writeHead(200, {
        'Content-Type': 'application/x-bittorrent',
        'Content-Length': String(bytes.length),
        'Access-Control-Allow-Origin': '*'
      });
      res.end(bytes);
    } catch (err) {
      sendJson(res, 502, { error: String(err) });
    }
    return;
  }

  // 6. Streaming HTTP/HTTPS Range Proxy
  if ((req.method === 'GET' || req.method === 'HEAD') && pathname === '/api/proxy') {
    const targetUrl = (parsedUrl.searchParams.get('url') || '').trim();
    if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
      sendJson(res, 400, { error: 'Invalid proxy target url' });
      return;
    }

    try {
      const fwdHeaders: Record<string, string> = {
        'User-Agent': 'North-Proxy-API/27'
      };
      if (req.headers.range) {
        fwdHeaders.Range = String(req.headers.range);
      }
      const upstream = await fetch(targetUrl, {
        method: req.method,
        headers: fwdHeaders
      });

      const outHeaders: Record<string, string> = {
        'Content-Type': upstream.headers.get('content-type') || 'application/octet-stream',
        'Accept-Ranges': upstream.headers.get('accept-ranges') || 'bytes',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges'
      };
      const cl = upstream.headers.get('content-length');
      if (cl) outHeaders['Content-Length'] = cl;
      const cr = upstream.headers.get('content-range');
      if (cr) outHeaders['Content-Range'] = cr;

      res.writeHead(upstream.status, outHeaders);
      if (req.method === 'HEAD' || !upstream.body) {
        res.end();
        return;
      }
      const reader = upstream.body.getReader();
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (value) res.write(Buffer.from(value));
        }
      } finally {
        reader.releaseLock();
        res.end();
      }
    } catch (err) {
      if (!res.headersSent) {
        sendJson(res, 502, { error: String(err) });
      } else {
        res.end();
      }
    }
    return;
  }

  // 7. Cloudstream Repository & Plugin Manifest Routes
  if (req.method === 'GET' && pathname === '/repo.json') {
    const baseUrl = getBaseUrl(req);
    sendJson(res, 200, {
      name: 'North (Account API Key Required)',
      description:
        "North • Nordic & Danish Cloudstream Extension (by sealinwater). ⚠️ ACCOUNT API KEY REQUIRED: You need a NordicBytes account and UNIT3D API key to use North. Apply at https://nordicbytes.org/application (your application text is public so members can vouch for you, so don't include your real name or personal info; your email and 2 tracker proof screenshot/profile links are only visible to staff), then copy your API key from Profile -> Settings -> API Key into North Settings.",
      iconUrl: 'https://raw.githubusercontent.com/sealinwater/north/builds/icon.png',
      manifestVersion: 1,
      pluginLists: [`${baseUrl}/plugins.json`]
    });
    return;
  }

  if (req.method === 'GET' && pathname === '/plugins.json') {
    const baseUrl = getBaseUrl(req);
    const cs3Path = fs.existsSync(path.join(__dirname, 'North.cs3'))
      ? path.join(__dirname, 'North.cs3')
      : path.join(__dirname, 'NordicBytesPlugin.cs3');
    let fileSize = 223000;
    if (fs.existsSync(cs3Path)) {
      fileSize = fs.statSync(cs3Path).size;
    }

    sendJson(res, 200, [
      {
        url: `${baseUrl}/North.cs3`,
        status: 1,
        version: PLUGIN_VERSION,
        name: 'North',
        internalName: 'North',
        authors: ['North • sealinwater'],
        description:
          '⚠️ ACCOUNT API KEY REQUIRED: You need a NordicBytes (nordicbytes.org) account and API key to use North. How to get set up: 1) Apply at https://nordicbytes.org/application. Everything you write in your application text and experience level is public so members can vouch for you, so never put your real name, address, or personal info in there. Your email and at least 2 tracker proof screenshots + profile links are only shown to staff (you do need proofs or the application gets rejected). 2) Once you can log in at nordicbytes.org, go to Profile -> Settings -> API Key and copy your UNIT3D API token. 3) In Cloudstream, go to Settings -> Extensions -> North (gear icon), paste your key, and hit Save.',
        iconUrl: 'https://raw.githubusercontent.com/sealinwater/north/builds/icon.png',
        fileSize: fileSize,
        apiVersion: 1
      }
    ]);
    return;
  }

  if (
    req.method === 'GET' &&
    (pathname === '/North.cs3' || pathname === '/NordicBytesPlugin.cs3' || pathname === '/NordicBytes.cs3')
  ) {
    const filePath = fs.existsSync(path.join(__dirname, 'North.cs3'))
      ? path.join(__dirname, 'North.cs3')
      : path.join(__dirname, 'NordicBytesPlugin.cs3');
    if (fs.existsSync(filePath)) {
      const stat = fs.statSync(filePath);
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(stat.size),
        'Content-Disposition': 'attachment; filename="North.cs3"'
      });
      fs.createReadStream(filePath).pipe(res);
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Plugin file not found. Please build using ./gradlew make.');
    }
    return;
  }

  if (req.method === 'POST' && pathname === '/api/claim-pymd') {
    let rawBody = '';
    req.on('data', (chunk) => {
      rawBody += chunk;
    });
    req.on('end', async () => {
      try {
        const parsed = rawBody ? JSON.parse(rawBody) : {};
        const apiKey = String(parsed?.apiKey || '').trim();
        const customSlug = String(parsed?.customSlug || 'north').trim();
        if (!apiKey) {
          sendJson(res, 400, { error: 'Please enter your py.md API Key from py.md/settings' });
          return;
        }
        const response = await fetch('https://py.md/api/links', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-API-KEY': apiKey
          },
          body: JSON.stringify({
            target: 'https://raw.githubusercontent.com/sealinwater/north/builds/repo.json',
            domain: 'py.md',
            customurl: customSlug,
            description: 'North Cloudstream Repository'
          })
        });
        const data = await response.json();
        sendJson(res, response.status, data);
      } catch (err) {
        sendJson(res, 500, { error: String(err) });
      }
    });
    return;
  }

  const baseUrl = getBaseUrl(req);
  const githubRepoUrl = 'https://raw.githubusercontent.com/sealinwater/north/builds/repo.json';
  const pyMdNorthUrl = 'https://py.md/north';
  const pyMdAutoUrl = 'https://py.md/pwahR';
  const cs3Url = 'https://raw.githubusercontent.com/sealinwater/north/builds/North.cs3';
  const cloudstreamProtocolUrl = 'cloudstreamrepo://raw.githubusercontent.com/sealinwater/north/builds/repo.json';
  const qrCodeUrl = `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(cloudstreamProtocolUrl)}`;

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>North • Cloudstream Repository & Proxy API (sealinwater)</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
      body { font-family: 'Inter', sans-serif; background-color: #040B16; }
    </style>
</head>
<body class="bg-[#040B16] text-blue-50 min-h-screen py-10 px-4">
    <div class="max-w-3xl mx-auto space-y-6">
        <div class="bg-[#08152B] border border-blue-800/60 rounded-2xl p-6 sm:p-8 shadow-2xl shadow-blue-950/60">
            <div class="flex items-center space-x-3 mb-2">
                <span class="inline-flex items-center px-2.5 py-0.5 rounded text-xs font-semibold bg-sky-500/15 text-sky-300 border border-sky-400/30">
                    NORTH • SEALINWATER
                </span>
                <span class="text-xs text-blue-300/80 font-mono">Version ${PLUGIN_VERSION} (Proxy API + Intro)</span>
            </div>
            <h1 class="text-3xl font-extrabold text-white tracking-tight">North • Cloudstream Extension & Proxy API</h1>
            <p class="text-blue-200/80 mt-2 text-sm sm:text-base">
                Nordic Movies & Series extension + companion Proxy API server with the Nordic Aurora intro, seekable MKV/MP4 streaming, Chromecast stereo audio support, skip intro buttons, and Ghost Ratio protection.
            </p>
            <div class="mt-6 flex flex-wrap gap-3">
                <a href="${cloudstreamProtocolUrl}" 
                   class="inline-flex items-center justify-center px-5 py-3 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-semibold text-sm transition-colors shadow-lg shadow-blue-600/30 border border-blue-400/30">
                    Install North in Cloudstream
                </a>
                <a href="${cs3Url}" 
                   class="inline-flex items-center justify-center px-5 py-3 rounded-xl bg-[#0D2142] hover:bg-[#132D59] text-blue-100 font-medium text-sm transition-colors border border-blue-800/70">
                    Download North (.cs3)
                </a>
                <a href="https://nordicbytes.org/application" target="_blank" rel="noopener noreferrer"
                   class="inline-flex items-center justify-center px-5 py-3 rounded-xl bg-sky-600/20 hover:bg-sky-600/30 text-sky-200 font-semibold text-sm transition-colors border border-sky-500/40">
                    Apply for NordicBytes Account
                </a>
            </div>
        </div>

        <div class="bg-[#08152B] border border-amber-500/40 rounded-2xl p-6 space-y-4 shadow-xl">
            <div class="flex items-center justify-between">
                <h2 class="text-lg font-bold text-white">⚠️ Account API Key Required: How to Sign Up &amp; Get Your Key</h2>
                <span class="text-xs font-semibold px-2.5 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-400/30">Required</span>
            </div>
            <p class="text-xs sm:text-sm text-blue-100/85">
                <b>North</b> needs an active <b>NordicBytes</b> (<code class="text-sky-300">nordicbytes.org</code>) account and your personal UNIT3D API token to work. Here is how to apply and grab your key:
            </p>
            <div class="space-y-3 text-xs text-blue-100/80">
                <div class="bg-[#040B16] border border-blue-900/70 rounded-xl p-4 space-y-1.5">
                    <div class="font-semibold text-sky-300 text-sm">1. Apply to Join at <a href="https://nordicbytes.org/application" target="_blank" class="underline hover:text-white">https://nordicbytes.org/application</a></div>
                    <ul class="list-disc list-inside space-y-1 text-blue-200/80">
                        <li><b>Your Application is Public:</b> Whatever you write in your application text and your experience level (like <i>&ldquo;New, I haven't used private trackers before&rdquo;</i> or experienced) is visible to all members on the site so they can vouch for you. Just be honest and write a clear answer.</li>
                        <li><b class="text-amber-300">No Personal Information:</b> Never put your real name, home address, or any other private personal info in your application.</li>
                        <li><b>Where Are You From? (Anonymous):</b> Optional, shown next to your application.</li>
                        <li><b>Email Address (Staff Only):</b> Only visible to staff.</li>
                        <li><b>Proof (Staff Only, Required):</b> Add at least 2 screenshots (<code>Screenshot URL</code> + <code>Profile URL</code>) showing your profile on another tracker with your username, ratio, and upload/download stats. <i>You need proof or the application gets rejected.</i></li>
                    </ul>
                </div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-xl p-4 space-y-1">
                    <div class="font-semibold text-sky-300 text-sm">2. Generate / Copy Your API Key</div>
                    <p class="text-blue-200/80">Already have an account or once approved, <a href="https://nordicbytes.org/login" target="_blank" class="text-sky-300 underline">log in at nordicbytes.org</a>, open your <b>Profile &rarr; Settings &rarr; API Key / Security</b>, and copy your personal <b>UNIT3D API Token</b>.</p>
                </div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-xl p-4 space-y-1">
                    <div class="font-semibold text-sky-300 text-sm">3. Activate in Cloudstream</div>
                    <p class="text-blue-200/80">In Cloudstream, go to <b>Settings &rarr; Extensions &rarr; North (⚙️ Gear Icon)</b>, paste your token into <b>North (NordicBytes) API Key</b>, and tap <b>Save &amp; Restart CloudStream</b>.</p>
                </div>
            </div>
        </div>

        <div class="bg-[#08152B] border border-blue-800/60 rounded-2xl p-6 space-y-3">
            <h2 class="text-lg font-semibold text-white">North Companion Proxy API</h2>
            <p class="text-xs text-blue-200/75">Paste this API base URL into <b>North Settings → North Proxy API URL</b> so the plugin listens on this server for pre-roll intro video, skip segments, subtitles, and torrent/stream proxying:</p>
            <div class="relative">
                <input id="apiBaseInput" readonly value="${baseUrl}"
                       class="w-full bg-[#040B16] border border-blue-700/70 rounded-lg px-3 py-2 text-xs font-mono text-sky-300 pr-20 select-all focus:outline-none">
                <button onclick="copyToClipboard('apiBaseInput', 'apiBaseBtn')" id="apiBaseBtn"
                        class="absolute right-1 top-1 bottom-1 px-3 bg-blue-700 hover:bg-blue-600 text-xs font-semibold text-white rounded transition-colors">
                    Copy
                </button>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2 pt-2 text-xs font-mono text-blue-200/80">
                <div class="bg-[#040B16] border border-blue-900/70 rounded-lg px-3 py-2">GET /api/health</div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-lg px-3 py-2">GET /api/intro.mp4</div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-lg px-3 py-2">GET /api/skips</div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-lg px-3 py-2">GET /api/subtitles</div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-lg px-3 py-2">GET /api/torrent</div>
                <div class="bg-[#040B16] border border-blue-900/70 rounded-lg px-3 py-2">GET /api/proxy</div>
            </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div class="bg-[#08152B] border border-blue-800/60 rounded-2xl p-6 flex flex-col justify-between">
                <div class="space-y-4">
                    <div>
                        <div class="flex items-center justify-between mb-2">
                            <h2 class="text-lg font-semibold text-white">1. Short URL / Shortcode (py.md)</h2>
                            <span class="text-xs bg-sky-500/15 text-sky-300 px-2 py-0.5 rounded font-mono border border-sky-500/30">py.md</span>
                        </div>
                        <p class="text-xs text-blue-200/70 mb-2">
                            In Cloudstream, <code class="text-sky-300">py.md</code> shortcodes start with <code class="text-amber-300 font-bold">!</code> (e.g. <code class="text-sky-300">!north</code> or <code class="text-sky-300">!pwahR</code>), or paste the full URL:
                        </p>
                        <div class="relative mb-2">
                            <input id="northInput" readonly value="${pyMdNorthUrl}" 
                                   class="w-full bg-[#040B16] border border-blue-700/70 rounded-lg px-3 py-2 text-xs font-mono text-sky-300 pr-20 select-all focus:outline-none">
                            <button onclick="copyToClipboard('northInput', 'northBtn')" id="northBtn"
                                    class="absolute right-1 top-1 bottom-1 px-3 bg-blue-700 hover:bg-blue-600 text-xs font-semibold text-white rounded transition-colors">
                                Copy
                            </button>
                        </div>
                        <div class="relative">
                            <input id="shortInput" readonly value="${pyMdAutoUrl}" 
                                   class="w-full bg-[#040B16] border border-blue-800/70 rounded-lg px-3 py-2 text-xs font-mono text-blue-300 pr-20 select-all focus:outline-none">
                            <button onclick="copyToClipboard('shortInput', 'shortBtn')" id="shortBtn"
                                    class="absolute right-1 top-1 bottom-1 px-3 bg-[#0D2142] hover:bg-[#132D59] text-xs font-medium text-blue-100 rounded transition-colors">
                                Copy
                            </button>
                        </div>
                    </div>

                    <div class="pt-3 border-t border-blue-900/60">
                        <p class="text-xs text-blue-200/70 mb-2">
                            Direct GitHub Repository URL (<code class="text-sky-300">sealinwater/north</code>):
                        </p>
                        <div class="relative">
                            <input id="repoInput" readonly value="${githubRepoUrl}" 
                                   class="w-full bg-[#040B16] border border-blue-800/70 rounded-lg px-3 py-2 text-xs font-mono text-blue-300 pr-20 select-all focus:outline-none">
                            <button onclick="copyToClipboard('repoInput', 'repoBtn')" id="repoBtn"
                                    class="absolute right-1 top-1 bottom-1 px-3 bg-[#0D2142] hover:bg-[#132D59] text-xs font-medium text-blue-100 rounded transition-colors">
                                Copy
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            <div class="bg-[#08152B] border border-blue-800/60 rounded-2xl p-6 flex flex-col items-center justify-center text-center">
                <h2 class="text-lg font-semibold text-white mb-1">2. Scan QR Code</h2>
                <p class="text-xs text-blue-200/70 mb-4">Scan using your phone camera to install <b>North</b> directly:</p>
                <div class="bg-white p-3 rounded-xl shadow-md inline-block">
                    <img src="${qrCodeUrl}" alt="Cloudstream QR Code" class="w-44 h-44 rounded-lg">
                </div>
                <span class="text-[11px] text-blue-400/70 mt-3 font-mono">sealinwater/north • v${PLUGIN_VERSION}</span>
            </div>
        </div>

        <footer class="text-center text-xs text-blue-400/50 pt-4">
            North • Deep Blue Cloudstream Repository & Proxy API • sealinwater/north
        </footer>
    </div>

    <script>
        function copyToClipboard(inputId, buttonId) {
            const copyText = document.getElementById(inputId);
            copyText.select();
            copyText.setSelectionRange(0, 99999);
            navigator.clipboard.writeText(copyText.value);
            const btn = document.getElementById(buttonId);
            const originalText = btn.innerText;
            btn.innerText = 'Copied!';
            setTimeout(() => {
                btn.innerText = originalText;
            }, 2000);
        }
    </script>
</body>
</html>`;

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
});

server.listen(port, '0.0.0.0', () => {
  console.log(`North Cloudstream Repo & Proxy API server running on http://0.0.0.0:${port}`);
});

const express = require('express');
const axios = require('axios');
const path = require('path');
const dns = require('dns');
const net = require('net');
const http = require('http');
const https = require('https');

// The original web prototype of Eager to Listen, kept for local experiments. The Android app
// (android/) and the desktop app (desktop/) don't use any of this.
//
// It listens on this computer only. Set HOST=0.0.0.0 if you really mean to expose it, and read
// the notes on /api/proxy first.
const app = express();
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '127.0.0.1';

app.disable('x-powered-by');
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  next();
});

// Serve static files from the public folder (same origin as the proxy, so no CORS is needed)
app.use(express.static(path.join(__dirname, 'public')));

/**
 * Checks whether an IP address belongs to private, loopback, link-local,
 * carrier-grade NAT, or multicast ranges (SSRF protection).
 */
function isPrivateIp(ip) {
  if (net.isIPv4(ip)) {
    const parts = ip.split('.').map(Number);
    if (parts.length !== 4 || parts.some(isNaN)) return true;
    const [b0, b1] = parts;
    if (b0 === 0) return true; // 0.0.0.0/8
    if (b0 === 10) return true; // 10.0.0.0/8
    if (b0 === 127) return true; // 127.0.0.0/8
    if (b0 === 169 && b1 === 254) return true; // 169.254.0.0/16 link-local
    if (b0 === 172 && (b1 >= 16 && b1 <= 31)) return true; // 172.16.0.0/12
    if (b0 === 192 && b1 === 168) return true; // 192.168.0.0/16
    if (b0 === 100 && (b1 >= 64 && b1 <= 127)) return true; // 100.64.0.0/10 CGNAT
    if (b0 >= 224) return true; // 224.0.0.0/4 multicast & 240.0.0.0/4 reserved
    return false;
  }
  if (net.isIPv6(ip)) {
    const normalized = ip.toLowerCase();
    // IPv4 written as IPv6 (::ffff:127.0.0.1): judge the IPv4 address inside
    const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(normalized);
    if (mapped) return isPrivateIp(mapped[1]);
    if (normalized.startsWith('::ffff:')) return true; // hex-form mapped addresses
    if (normalized === '::1' || normalized === '::') return true;
    if (normalized.startsWith('fc') || normalized.startsWith('fd')) return true; // fc00::/7 ULA
    if (normalized.startsWith('fe8') || normalized.startsWith('fe9') || normalized.startsWith('fea') || normalized.startsWith('feb')) return true; // fe80::/10 link-local
    if (normalized.startsWith('ff')) return true; // ff00::/8 multicast
    return false;
  }
  return true;
}

function blocked(message) {
  return Object.assign(new Error(message), { code: 'EBLOCKED' });
}

/**
 * DNS lookup used for every connection the proxy makes, including each redirect hop. Checking the
 * address here, at connect time, closes two holes in a check-then-fetch design: a redirect to an
 * internal address, and a host name that answers with a public address first and a private one
 * second (DNS rebinding).
 */
function safeLookup(hostname, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = {};
  }
  dns.lookup(hostname, { ...options, all: true }, (err, addresses) => {
    if (err) return callback(err);
    const list = Array.isArray(addresses) ? addresses : [{ address: addresses, family: net.isIPv6(addresses) ? 6 : 4 }];
    if (!list.length || list.some((a) => isPrivateIp(a.address))) {
      return callback(blocked('Access to private or local network addresses is forbidden'));
    }
    if (options.all) return callback(null, list);
    callback(null, list[0].address, list[0].family);
  });
}

/** A literal IP address in the URL never goes through DNS, so check it directly. */
function assertAllowedTarget(protocol, hostname) {
  if (protocol !== 'http:' && protocol !== 'https:') throw blocked('Only http and https protocols are supported');
  const host = String(hostname || '').replace(/^\[|\]$/g, '');
  if (net.isIP(host) && isPrivateIp(host)) throw blocked('Access to private or local network addresses is forbidden');
}

const httpAgent = new http.Agent({ lookup: safeLookup });
const httpsAgent = new https.Agent({ lookup: safeLookup });

// Feed/transcript proxy for the browser prototype (browsers can't fetch other sites' feeds directly)
app.get('/api/proxy', async (req, res) => {
  const { url } = req.query;

  if (!url || typeof url !== 'string') {
    return res.status(400).json({ error: 'URL parameter is required' });
  }

  let parsedUrl;
  try {
    parsedUrl = new URL(url);
  } catch (_) {
    return res.status(400).json({ error: 'Invalid URL format' });
  }

  try {
    assertAllowedTarget(parsedUrl.protocol, parsedUrl.hostname);

    const response = await axios.get(parsedUrl.toString(), {
      responseType: 'arraybuffer',
      headers: {
        'User-Agent': 'EagerToListen/0.9.0-alpha (WebProxy; +https://eagertobehealthy.com/eager-to-listen)',
        'Accept': '*/*'
      },
      timeout: 10000,
      maxContentLength: 15 * 1024 * 1024,
      maxBodyLength: 15 * 1024 * 1024,
      maxRedirects: 5,
      proxy: false,
      httpAgent,
      httpsAgent,
      beforeRedirect: (options) => assertAllowedTarget(options.protocol, options.hostname)
    });

    const contentType = response.headers['content-type'] || 'application/octet-stream';
    res.setHeader('Content-Type', contentType);
    res.setHeader('Cache-Control', 'public, max-age=600');
    // What comes back is someone else's content served from this app's address: never let a
    // browser run it as a page of ours.
    res.setHeader('Content-Security-Policy', "sandbox; default-src 'none'");
    res.send(response.data);
  } catch (error) {
    if (error.code === 'EBLOCKED' || (error.cause && error.cause.code === 'EBLOCKED')) {
      return res.status(403).json({ error: (error.cause || error).message });
    }
    console.error(`Proxy error fetching ${parsedUrl.origin}:`, error.message);
    const status = error.response ? error.response.status : 502;
    res.status(status).json({
      error: 'Failed to fetch the requested URL',
      details: error.message
    });
  }
});

// Fallback to index.html for single page app routing if needed
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

if (require.main === module) app.listen(PORT, HOST, () => {
  console.log(`==================================================`);
  console.log(` Eager to Listen web prototype running on port ${PORT}`);
  console.log(` Local URL: http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
  console.log(`==================================================`);
});

module.exports = { app, isPrivateIp, safeLookup, assertAllowedTarget };

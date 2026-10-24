const express = require('express');
const cors = require('cors');
const axios = require('axios');
const path = require('path');
const dns = require('dns');
const net = require('net');

const app = express();
const PORT = process.env.PORT || 3000;

// Enable CORS for all requests
app.use(cors());

// Serve static files from the public folder
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
    if (normalized === '::1' || normalized === '::') return true;
    if (normalized.startsWith('fc') || normalized.startsWith('fd')) return true; // fc00::/7 ULA
    if (normalized.startsWith('fe8') || normalized.startsWith('fe9') || normalized.startsWith('fea') || normalized.startsWith('feb')) return true; // fe80::/10 link-local
    if (normalized.startsWith('ff')) return true; // ff00::/8 multicast
    return false;
  }
  return true;
}

// CORS proxy endpoint with SSRF protections
app.get('/api/proxy', async (req, res) => {
  const { url } = req.query;

  if (!url) {
    return res.status(400).json({ error: 'URL parameter is required' });
  }

  let parsedUrl;
  try {
    parsedUrl = new URL(url);
  } catch (_) {
    return res.status(400).json({ error: 'Invalid URL format' });
  }

  if (parsedUrl.protocol !== 'http:' && parsedUrl.protocol !== 'https:') {
    return res.status(400).json({ error: 'Only http and https protocols are supported' });
  }

  try {
    const addresses = await dns.promises.lookup(parsedUrl.hostname, { all: true });
    for (const addr of addresses) {
      if (isPrivateIp(addr.address)) {
        return res.status(403).json({ error: 'Access to private or local network addresses is forbidden' });
      }
    }

    const response = await axios.get(url, {
      responseType: 'arraybuffer',
      headers: {
        'User-Agent': 'EagerToListen/0.9.0-alpha (WebProxy; +https://eagertobehealthy.com/eager-to-listen)',
        'Accept': '*/*'
      },
      timeout: 10000,
      maxContentLength: 15 * 1024 * 1024,
      maxBodyLength: 15 * 1024 * 1024,
      maxRedirects: 5
    });

    const contentType = response.headers['content-type'] || 'application/octet-stream';
    res.setHeader('Content-Type', contentType);
    res.setHeader('Cache-Control', 'public, max-age=600');
    res.send(response.data);
  } catch (error) {
    console.error(`Proxy error fetching ${url}:`, error.message);
    const status = error.response ? error.response.status : 500;
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

app.listen(PORT, () => {
  console.log(`==================================================`);
  console.log(` DoggCatcher Clone server running on port ${PORT}`);
  console.log(` Local URL: http://localhost:${PORT}`);
  console.log(`==================================================`);
});

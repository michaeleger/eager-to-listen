const express = require('express');
const cors = require('cors');
const axios = require('axios');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

// Enable CORS for all requests
app.use(cors());

// Serve static files from the public folder
app.use(express.static(path.join(__dirname, 'public')));

// CORS proxy endpoint
app.get('/api/proxy', async (req, res) => {
  const { url } = req.query;

  if (!url) {
    return res.status(400).json({ error: 'URL parameter is required' });
  }

  try {
    // Set headers to mimic a normal browser request to avoid user-agent blocks
    const response = await axios.get(url, {
      responseType: 'arraybuffer',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': '*/*'
      },
      timeout: 10000 // 10s timeout
    });

    // Forward the content type header if present, otherwise default to octet-stream
    const contentType = response.headers['content-type'] || 'application/octet-stream';
    res.setHeader('Content-Type', contentType);
    
    // Set caching headers so we don't spam podcast servers on repeated loads
    res.setHeader('Cache-Control', 'public, max-age=600'); // 10 minutes cache

    res.send(response.data);
  } catch (error) {
    console.error(`Proxy error fetching ${url}:`, error.message);
    res.status(500).json({
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

// Tests for the web prototype's feed proxy (server.js): node --test test_server.js
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { app, isPrivateIp, safeLookup, assertAllowedTarget } = require('./server.js');

test('private and local addresses are recognised', () => {
  for (const ip of ['127.0.0.1', '10.1.2.3', '192.168.1.1', '172.20.0.1', '169.254.169.254', '100.64.0.1', '0.0.0.0', '::1', 'fd00::1', 'fe80::1', '::ffff:127.0.0.1', '::ffff:7f00:1']) {
    assert.equal(isPrivateIp(ip), true, ip);
  }
  for (const ip of ['8.8.8.8', '1.1.1.1', '2606:4700:4700::1111', '::ffff:8.8.8.8']) {
    assert.equal(isPrivateIp(ip), false, ip);
  }
});

test('literal private addresses and odd schemes are refused before any request', () => {
  assert.throws(() => assertAllowedTarget('http:', '127.0.0.1'), /forbidden/);
  assert.throws(() => assertAllowedTarget('http:', '[::1]'), /forbidden/);
  assert.throws(() => assertAllowedTarget('file:', 'example.com'), /Only http and https/);
  assert.doesNotThrow(() => assertAllowedTarget('https:', 'example.com'));
});

test('a host name that resolves to this computer is refused at connect time', (t, done) => {
  safeLookup('localhost', {}, (err) => {
    assert.equal(err && err.code, 'EBLOCKED');
    done();
  });
});

test('the proxy answers 403 for local targets and 400 for junk', async () => {
  const server = http.createServer(app).listen(0, '127.0.0.1');
  await new Promise((r) => server.once('listening', r));
  const base = `http://127.0.0.1:${server.address().port}/api/proxy?url=`;
  try {
    for (const target of ['http://127.0.0.1:1/', 'http://localhost:1/', 'http://[::1]:1/', 'http://169.254.169.254/latest/meta-data/']) {
      const res = await fetch(base + encodeURIComponent(target));
      assert.equal(res.status, 403, target);
    }
    assert.equal((await fetch(base + encodeURIComponent('ftp://example.com/x'))).status, 403);
    assert.equal((await fetch(base + 'not-a-url')).status, 400);
    assert.equal((await fetch(base.replace('?url=', ''))).status, 400);
  } finally {
    server.close();
  }
});

const fs = require('fs');
const xml = fs.readFileSync('riverside_sample.xml', 'utf8');
const regex = /<podcast:transcript\s+url="([^"]+)"\s+type="([^"]+)"/g;
let match;
while ((match = regex.exec(xml)) !== null) {
  console.log('TRANSCRIPT:', match[2], match[1]);
}

import test from 'node:test';
import assert from 'node:assert/strict';
import { isSafePublicText, reviewedPublicLinks } from './public-link-policy.mjs';

test('only exact reviewed URLs are permitted', () => {
  for (const url of reviewedPublicLinks) {
    assert.equal(isSafePublicText(`[reference](${url})`), true);
    for (const suffix of ['?secret=1', '#extra', '/other', '.evil.test'])
      assert.equal(isSafePublicText(`[reference](${url}${suffix})`), false);
  }
  for (const value of ['https://example.com', 'http://localhost', 'https://', '[bad](https://)', '/home/user', '/opt/app', '/etc/config', '/var/data', '192.168.1.1', 'BEGIN RSA PRIVATE KEY', 'sk-abcdefghijklmnopqrstuvwxyz'])
    assert.equal(isSafePublicText(value), false, value);
});

import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeTop250Title } from '../src/proxy/douban-scrape.ts';

test('normalizes non-breaking spaces before extracting the primary Top250 title', () => {
    const cases = [
        'Movie&nbsp;/&nbsp;Original Title',
        'Movie&#160;/&#160;Original Title',
        'Movie&#xA0;/&#xA0;Original Title',
        'Movie\u00a0/\u00a0Original Title',
    ];

    for (const rawTitle of cases) {
        assert.equal(normalizeTop250Title(rawTitle), 'Movie / Original Title');
    }
});

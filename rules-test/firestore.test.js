// Firestore Security Rules を Firebase Emulator 上で検証する。
// server 層を撤廃した今、Rules がアクセス制御と LWW を強制する唯一の防衛線
// なので、CI で deploy 前に必ず通す (docs/stock/sync.md)。
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import {
  setDoc,
  getDoc,
  deleteDoc,
  doc,
} from 'firebase/firestore';
import { readFileSync } from 'node:fs';
import { describe, test, before, after, beforeEach } from 'node:test';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-rules-test',
    firestore: {
      rules: readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

const session = { startedAtMs: 100, json: '{}' };
const settings = (ms) => ({ updatedAtMs: ms, json: '{}' });
const dailyMetric = (date) => ({ date, json: '{}' });

const seed = (path, data) =>
  env.withSecurityRulesDisabled((ctx) => setDoc(doc(ctx.firestore(), path), data));

describe('users/{uid} (プロファイル)', () => {
  test('オーナーは自分のプロファイルを書ける', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice'), {
        providers: ['google.com'],
        createdAtMs: 1,
        updatedAtMs: 1,
      }),
    );
  });

  test('他人のプロファイルは読めない', async () => {
    await seed('users/alice', { providers: ['google.com'], createdAtMs: 1, updatedAtMs: 1 });
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(getDoc(doc(bob, 'users/alice')));
  });

  test('未認証はプロファイルを書けない', async () => {
    const anon = env.unauthenticatedContext().firestore();
    await assertFails(
      setDoc(doc(anon, 'users/alice'), {
        providers: ['google.com'],
        createdAtMs: 1,
        updatedAtMs: 1,
      }),
    );
  });
});

describe('users/{uid}/sessions/{id} (履歴)', () => {
  test('オーナーは自分のセッションを書ける', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertSucceeds(setDoc(doc(alice, 'users/alice/sessions/s1'), session));
  });

  test('他人のセッションは書けない', async () => {
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(setDoc(doc(bob, 'users/alice/sessions/s1'), session));
  });

  test('他人のセッションは読めない', async () => {
    await seed('users/alice/sessions/s1', session);
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(getDoc(doc(bob, 'users/alice/sessions/s1')));
  });

  test('セッションは削除できない (append-only)', async () => {
    await seed('users/alice/sessions/s1', session);
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(deleteDoc(doc(alice, 'users/alice/sessions/s1')));
  });

  test('未知のフィールドを足した write は拒否される', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/sessions/s1'), { ...session, extra: 'x' }),
    );
  });
});

describe('users/{uid}/dailyMetrics/{date} (Health Connect 取り込み)', () => {
  test('オーナーは自分の dailyMetrics を書ける', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/dailyMetrics/2026-06-19'), dailyMetric('2026-06-19')),
    );
  });

  test('同じ日付の上書きは許される (冪等 upsert)', async () => {
    await seed('users/alice/dailyMetrics/2026-06-19', dailyMetric('2026-06-19'));
    const alice = env.authenticatedContext('alice').firestore();
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/dailyMetrics/2026-06-19'), dailyMetric('2026-06-19')),
    );
  });

  test('doc id と date フィールドが一致しないと拒否される', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/dailyMetrics/2026-06-19'), dailyMetric('2026-06-20')),
    );
  });

  test('YYYY-MM-DD 形式でない doc id は拒否される', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/dailyMetrics/today'), { date: 'today', json: '{}' }),
    );
  });

  test('他人の dailyMetrics は書けない', async () => {
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(
      setDoc(doc(bob, 'users/alice/dailyMetrics/2026-06-19'), dailyMetric('2026-06-19')),
    );
  });

  test('他人の dailyMetrics は読めない', async () => {
    await seed('users/alice/dailyMetrics/2026-06-19', dailyMetric('2026-06-19'));
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(getDoc(doc(bob, 'users/alice/dailyMetrics/2026-06-19')));
  });

  test('削除はできない (append-only)', async () => {
    await seed('users/alice/dailyMetrics/2026-06-19', dailyMetric('2026-06-19'));
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(deleteDoc(doc(alice, 'users/alice/dailyMetrics/2026-06-19')));
  });

  test('未知フィールドは拒否される', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/dailyMetrics/2026-06-19'), {
        ...dailyMetric('2026-06-19'),
        extra: 'x',
      }),
    );
  });
});

describe('users/{uid}/settings/current (設定 LWW)', () => {
  test('オーナーは初回 settings を作れる', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/settings/current'), settings(100)),
    );
  });

  test('LWW: より新しい updatedAtMs は通る', async () => {
    await seed('users/alice/settings/current', settings(100));
    const alice = env.authenticatedContext('alice').firestore();
    await assertSucceeds(
      setDoc(doc(alice, 'users/alice/settings/current'), settings(200)),
    );
  });

  test('LWW: より古い updatedAtMs は拒否される', async () => {
    await seed('users/alice/settings/current', settings(100));
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/settings/current'), settings(50)),
    );
  });

  test('LWW: 同じ updatedAtMs は拒否される (単調増加のみ)', async () => {
    await seed('users/alice/settings/current', settings(100));
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/settings/current'), settings(100)),
    );
  });

  test('settings/current 以外の id は拒否される', async () => {
    const alice = env.authenticatedContext('alice').firestore();
    await assertFails(
      setDoc(doc(alice, 'users/alice/settings/other'), settings(100)),
    );
  });

  test('他人の settings は読めない', async () => {
    await seed('users/alice/settings/current', settings(100));
    const bob = env.authenticatedContext('bob').firestore();
    await assertFails(getDoc(doc(bob, 'users/alice/settings/current')));
  });
});

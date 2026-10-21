import { readFile } from 'node:fs/promises';
import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  Bytes, Timestamp, collection, doc, getDoc, getDocs, limit, orderBy,
  query, serverTimestamp, setDoc, startAfter, updateDoc, writeBatch,
} from 'firebase/firestore';

let env;
// Local-only updater fixtures; no test writes are made to the live project.
test('publishing requires a fresh private password proof and never exposes credentials', async () => {
  const pinHash = 'a'.repeat(64);
  await env.withSecurityRulesDisabled(async ctx => {
    await setDoc(doc(ctx.firestore(), 'mod_update_config', 'admin'), { pinHash });
  });
  const owner = env.authenticatedContext('release-owner').firestore();
  const friend = env.authenticatedContext('release-friend').firestore();
  const anonymous = env.unauthenticatedContext().firestore();
  const release = { version: '2.2', fileId: 'abcdefghijk123456', notes: 'Update', releaseNumber: 2002000, publishedAt: serverTimestamp() };
  const publish = (db, hash, data = release) => {
    const batch = writeBatch(db);
    batch.set(doc(db, 'mod_update_proofs', 'release-owner'), { ...data, pinHash: hash, at: serverTimestamp() });
    batch.set(doc(db, 'mod_updates', 'stable'), data);
    batch.set(doc(db, 'mod_update_releases', data.version), data);
    return batch.commit();
  };
  await assertFails(setDoc(doc(friend, 'mod_update_config', 'admin'), { pinHash }));
  await assertFails(getDoc(doc(friend, 'mod_update_config', 'admin')));
  await assertFails(setDoc(doc(friend, 'mod_updates', 'stable'), release));
  await assertFails(setDoc(doc(anonymous, 'mod_updates', 'stable'), release));
  await assertFails(publish(owner, 'wrong-password'));
  await assertFails(publish(friend, pinHash));
  await assertSucceeds(publish(owner, pinHash));
  const other = env.authenticatedContext('other-device').firestore();
  const otherBatch = writeBatch(other);
  otherBatch.set(doc(other, 'mod_update_proofs', 'other-device'), { ...release, pinHash, at: serverTimestamp() });
  otherBatch.set(doc(other, 'mod_updates', 'stable'), release);
  otherBatch.set(doc(other, 'mod_update_releases', release.version), release);
  await assertSucceeds(otherBatch.commit()); // Same password works from another device, no admin account.
  assert.equal((await assertSucceeds(getDoc(doc(anonymous, 'mod_updates', 'stable')))).get('version'), '2.2');
  await assertFails(publish(owner, pinHash, { ...release, releaseNumber: 2001000 }));
  await assertFails(publish(owner, pinHash, { ...release, fileId: '../invalid' }));
  await assertFails(setDoc(doc(owner, 'mod_updates', 'stable'), release)); // Cannot replay an old proof.
  await assertFails(getDoc(doc(owner, 'mod_update_proofs', 'release-owner')));
  await assertFails(getDoc(doc(friend, 'mod_update_proofs', 'release-owner')));
  await assertSucceeds(publish(owner, pinHash, { ...release, version: '2.3', releaseNumber: 2003000, notes: 'New in 2.3' }));
  assert.equal((await getDoc(doc(anonymous, 'mod_update_releases', '2.2'))).get('notes'), 'Update');
  assert.equal((await getDoc(doc(anonymous, 'mod_update_releases', '2.3'))).get('notes'), 'New in 2.3');
  await assertFails(setDoc(doc(friend, 'mod_update_releases', '2.2'), { ...release, notes: 'forged' }));
});
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-mihon-panels',
    firestore: { host: '127.0.0.1', port: 8094, rules: await readFile(new URL('./firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env?.cleanup(); });

function message(uid, panel = false) {
  return {
    senderName: uid, senderUid: uid, text: 'AOWKAOWK', timestamp: serverTimestamp(), isMangaShare: panel,
    ...(panel ? { hasPanel: true, panelWidth: 400, panelHeight: 300 } : {}),
  };
}
function panelBatch(db, id, { size = 1000, uid = 'alice' } = {}) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'global_chat', id), message(uid, true));
  batch.set(doc(db, 'global_chat_panels', id), { senderUid: uid, data: Bytes.fromUint8Array(new Uint8Array(size)) });
  return batch;
}

test('authenticated panel and message are created atomically, other circle member can read', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertSucceeds(panelBatch(alice, 'panel-ok').commit());
  const bob = env.authenticatedContext('bob').firestore();
  assert.equal((await assertSucceeds(getDoc(doc(bob, 'global_chat_panels', 'panel-ok')))).get('data').toUint8Array().length, 1000);
});

test('unauthenticated user cannot read or upload an image', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db, 'global_chat_panels', 'panel-ok')));
  await assertFails(panelBatch(db, 'unauth').commit());
});

test('oversized upload is rejected without leaving an orphaned message', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(panelBatch(db, 'too-big', { size: 150001 }).commit());
  assert.equal((await getDoc(doc(db, 'global_chat', 'too-big'))).exists(), false);
  await assertSucceeds(panelBatch(db, 'size-boundary', { size: 150000 }).commit());
});

test('standalone blob, missing blob and forged sender are rejected', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(setDoc(doc(db, 'global_chat_panels', 'orphan'), { senderUid: 'alice', data: Bytes.fromUint8Array(new Uint8Array(10)) }));
  await assertFails(setDoc(doc(db, 'global_chat', 'missing-blob'), message('alice', true)));
  await assertFails(panelBatch(db, 'forged-sender', { uid: 'bob' }).commit());
});

test('existing text, manga shares and own read receipt remain compatible', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(db, 'global_chat', 'text'), message('alice')));
  await assertSucceeds(setDoc(doc(db, 'global_chat', 'manga'), { ...message('alice'), isMangaShare: true, mangaTitle: 'Eleceed' }));
  await assertSucceeds(updateDoc(doc(db, 'global_chat', 'text'), { 'readBy.alice': 123 }));
  await assertFails(updateDoc(doc(db, 'global_chat', 'text'), { 'readBy.bob': 123 }));
  await assertFails(updateDoc(doc(db, 'global_chat', 'text'), { text: 'changed' }));
});

test('retention removes paired message and image atomically', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await panelBatch(db, 'delete-pair').commit();
  const messageOnly = writeBatch(db);
  messageOnly.delete(doc(db, 'global_chat', 'delete-pair'));
  await assertFails(messageOnly.commit());
  const imageOnly = writeBatch(db);
  imageOnly.delete(doc(db, 'global_chat_panels', 'delete-pair'));
  await assertFails(imageOnly.commit());
  const paired = writeBatch(db);
  paired.delete(doc(db, 'global_chat', 'delete-pair'));
  paired.delete(doc(db, 'global_chat_panels', 'delete-pair'));
  await assertSucceeds(paired.commit());
  await env.withSecurityRulesDisabled(async ctx => {
    assert.equal((await getDoc(doc(ctx.firestore(), 'global_chat_panels', 'delete-pair'))).exists(), false);
  });
});

test('cleanup of 201 messages preserves 200 latest including their images', async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    for (let start = 1; start <= 201; start += 100) {
      const batch = writeBatch(db);
      for (let i = start; i <= Math.min(start + 99, 201); i++) {
        const id = String(i).padStart(4, '0');
        batch.set(doc(db, 'global_chat', id), { ...message('alice', true), timestamp: Timestamp.fromMillis(i * 1000) });
        batch.set(doc(db, 'global_chat_panels', id), { senderUid: 'alice', data: Bytes.fromUint8Array(new Uint8Array(10)) });
      }
      await batch.commit();
    }
  });
  const db = env.authenticatedContext('alice').firestore();
  const ordered = query(collection(db, 'global_chat'), orderBy('timestamp', 'desc'));
  const newest = await getDocs(query(ordered, limit(200)));
  const old = await getDocs(query(ordered, startAfter(newest.docs.at(-1)), limit(200)));
  assert.deepEqual(old.docs.map(d => d.id), ['0001']);
  const batch = writeBatch(db);
  for (const message of old.docs) {
    batch.delete(message.ref);
    batch.delete(doc(db, 'global_chat_panels', message.id));
  }
  await assertSucceeds(batch.commit());
  assert.equal((await getDocs(ordered)).size, 200);
  await env.withSecurityRulesDisabled(async ctx => {
    assert.equal((await getDocs(collection(ctx.firestore(), 'global_chat_panels'))).size, 200);
  });
});

test('ten paired deletions fit the Rules access-call limit', async () => {
  const db = env.authenticatedContext('alice').firestore();
  for (let i = 0; i < 10; i++) await panelBatch(db, `batch-${i}`).commit();
  const batch = writeBatch(db);
  for (let i = 0; i < 10; i++) {
    batch.delete(doc(db, 'global_chat', `batch-${i}`));
    batch.delete(doc(db, 'global_chat_panels', `batch-${i}`));
  }
  await assertSucceeds(batch.commit());
});

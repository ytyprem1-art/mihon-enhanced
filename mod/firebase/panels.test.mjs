import { readFile } from 'node:fs/promises';
import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  Bytes, Timestamp, collection, doc, getDoc, getDocs, limit, orderBy,
  query, serverTimestamp, setDoc, startAfter, updateDoc, writeBatch, deleteField,
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

const hdVersion1 = '11111111-1111-4111-8111-111111111111';
const hdVersion2 = '22222222-2222-4222-8222-222222222222';
function profilePhotoBatch(db, uid, version, size = 1000) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'presence', uid), { username: uid, avatarBase64: 'small-avatar', avatarPhotoVersion: version }, { merge: true });
  batch.set(doc(db, 'chat_profile_photos', uid), {
    senderUid: uid, version, width: 768, height: 768, data: Bytes.fromUint8Array(new Uint8Array(size)),
  });
  return batch;
}

test('HD profile photos are owner written, bounded and visible only to authenticated chat clients', async () => {
  const alice = env.authenticatedContext('photo-alice').firestore();
  const bob = env.authenticatedContext('photo-bob').firestore();
  const anonymous = env.unauthenticatedContext().firestore();
  await assertSucceeds(profilePhotoBatch(alice, 'photo-alice', hdVersion1, 204800).commit());
  assert.equal((await assertSucceeds(getDoc(doc(bob, 'chat_profile_photos', 'photo-alice')))).get('data').toUint8Array().length, 204800);
  await assertFails(getDoc(doc(anonymous, 'chat_profile_photos', 'photo-alice')));
  await assertFails(profilePhotoBatch(bob, 'photo-alice', hdVersion2).commit());
  await assertFails(profilePhotoBatch(alice, 'photo-alice', hdVersion2, 204801).commit());
  assert.equal((await getDoc(doc(alice, 'chat_profile_photos', 'photo-alice'))).get('version'), hdVersion1);
  await assertFails(setDoc(doc(alice, 'chat_profile_photos', 'photo-alice'), {
    senderUid: 'photo-alice', version: hdVersion2, width: 768, height: 768, data: Bytes.fromUint8Array(new Uint8Array(100)),
  }));
});

test('replacing a profile photo overwrites the same document rather than retaining old photos', async () => {
  const db = env.authenticatedContext('photo-replace').firestore();
  await assertSucceeds(profilePhotoBatch(db, 'photo-replace', hdVersion1, 10).commit());
  await assertSucceeds(profilePhotoBatch(db, 'photo-replace', hdVersion2, 20).commit());
  const photo = await getDoc(doc(db, 'chat_profile_photos', 'photo-replace'));
  assert.equal(photo.get('version'), hdVersion2);
  assert.equal(photo.get('data').toUint8Array().length, 20);
  assert.equal((await getDoc(doc(db, 'presence', 'photo-replace'))).get('avatarPhotoVersion'), hdVersion2);
});

test('removing a profile photo clears HD and thumbnail metadata in the same batch', async () => {
  const db = env.authenticatedContext('photo-remove').firestore();
  await assertSucceeds(profilePhotoBatch(db, 'photo-remove', hdVersion1).commit());
  const alone = writeBatch(db); alone.delete(doc(db, 'chat_profile_photos', 'photo-remove'));
  await assertFails(alone.commit());
  const batch = writeBatch(db);
  batch.update(doc(db, 'presence', 'photo-remove'), { avatarBase64: deleteField(), avatarPhotoVersion: deleteField() });
  batch.delete(doc(db, 'chat_profile_photos', 'photo-remove'));
  await assertSucceeds(batch.commit());
  assert.equal((await getDoc(doc(db, 'chat_profile_photos', 'photo-remove'))).exists(), false);
  assert.equal((await getDoc(doc(db, 'presence', 'photo-remove'))).get('avatarPhotoVersion'), undefined);
});

function gifBatch(db, id, { size = 100, url, uid = 'alice' } = {}) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'global_chat', id), {
    ...message(uid), hasGif: true, gifWidth: 120, gifHeight: 100,
    ...(url ? { gifUrl: url } : {}),
  });
  if (!url) batch.set(doc(db, 'global_chat_gifs', id), { senderUid: uid, data: Bytes.fromUint8Array(new Uint8Array(size)) });
  return batch;
}

test('GIF upload is atomic, bounded, owner checked and readable by chat members', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const anonymous = env.unauthenticatedContext().firestore();
  await assertSucceeds(gifBatch(alice, 'gif-boundary', { size: 1000000 }).commit());
  assert.equal((await assertSucceeds(getDoc(doc(bob, 'global_chat_gifs', 'gif-boundary')))).get('data').toUint8Array().length, 1000000);
  await assertFails(getDoc(doc(anonymous, 'global_chat_gifs', 'gif-boundary')));
  await assertFails(gifBatch(alice, 'gif-too-big', { size: 1000001 }).commit());
  assert.equal((await getDoc(doc(alice, 'global_chat', 'gif-too-big'))).exists(), false);
  await assertFails(gifBatch(alice, 'gif-forged', { uid: 'bob' }).commit());
  await assertFails(setDoc(doc(alice, 'global_chat_gifs', 'gif-orphan'), { senderUid: 'alice', data: Bytes.fromUint8Array(new Uint8Array(10)) }));
  await assertFails(setDoc(doc(alice, 'global_chat', 'gif-missing'), { ...message('alice'), hasGif: true, gifWidth: 20, gifHeight: 20 }));
});

test('public GIF links need no blob and reject unsafe or non-media URLs', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(gifBatch(db, 'gif-link', { url: 'https://media.tenor.com/example/hello.gif' }).commit());
  await assertFails(gifBatch(db, 'gif-link-bad', { url: 'https://media.tenor.com.evil.test/a.gif' }).commit());
  await assertFails(gifBatch(db, 'gif-local-link', { url: 'content://keyboard/a.gif' }).commit());
  const remove = writeBatch(db);
  remove.delete(doc(db, 'global_chat', 'gif-link'));
  await assertSucceeds(remove.commit());
});

test('GIF cleanup removes message and file together and fits ten-message batch limit', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(gifBatch(db, 'gif-delete').commit());
  const messageOnly = writeBatch(db); messageOnly.delete(doc(db, 'global_chat', 'gif-delete'));
  await assertFails(messageOnly.commit());
  const gifOnly = writeBatch(db); gifOnly.delete(doc(db, 'global_chat_gifs', 'gif-delete'));
  await assertFails(gifOnly.commit());
  const paired = writeBatch(db); paired.delete(doc(db, 'global_chat', 'gif-delete')); paired.delete(doc(db, 'global_chat_gifs', 'gif-delete'));
  await assertSucceeds(paired.commit());
  for (let i = 0; i < 10; i++) await assertSucceeds(gifBatch(db, `gif-clean-${i}`).commit());
  const batch = writeBatch(db);
  for (let i = 0; i < 10; i++) {
    batch.delete(doc(db, 'global_chat', `gif-clean-${i}`));
    batch.delete(doc(db, 'global_chat_gifs', `gif-clean-${i}`));
  }
  await assertSucceeds(batch.commit());
  await env.withSecurityRulesDisabled(async ctx => {
    for (let i = 0; i < 10; i++) assert.equal((await getDoc(doc(ctx.firestore(), 'global_chat_gifs', `gif-clean-${i}`))).exists(), false);
  });
});

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

test('timestamp retention keeps 200 mixed messages and deletes expired GIF and panel blobs', async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    for (let start = 1; start <= 205; start += 100) {
      const batch = writeBatch(db);
      for (let i = start; i <= Math.min(start + 99, 205); i++) {
        const id = `mixed-${String(i).padStart(4, '0')}`;
        const data = { ...message('alice', i % 3 === 2), timestamp: Timestamp.fromMillis(i * 1000) };
        if (i % 3 === 1) {
          Object.assign(data, { hasGif: true, gifWidth: 20, gifHeight: 20 });
          batch.set(doc(db, 'global_chat_gifs', id), { senderUid: 'alice', data: Bytes.fromUint8Array(new Uint8Array(10)) });
        } else if (i % 3 === 2) {
          batch.set(doc(db, 'global_chat_panels', id), { senderUid: 'alice', data: Bytes.fromUint8Array(new Uint8Array(10)) });
        }
        batch.set(doc(db, 'global_chat', id), data);
      }
      await batch.commit();
    }
  });
  const db = env.authenticatedContext('alice').firestore();
  const ordered = query(collection(db, 'global_chat'), orderBy('timestamp', 'desc'));
  const newest = await getDocs(query(ordered, limit(200)));
  const old = await getDocs(query(ordered, startAfter(newest.docs.at(-1)), limit(10)));
  assert.deepEqual(old.docs.map(d => d.id), ['mixed-0005', 'mixed-0004', 'mixed-0003', 'mixed-0002', 'mixed-0001']);
  const batch = writeBatch(db);
  for (const item of old.docs) {
    batch.delete(item.ref);
    if (item.get('hasPanel')) batch.delete(doc(db, 'global_chat_panels', item.id));
    if (item.get('hasGif') && !item.get('gifUrl')) batch.delete(doc(db, 'global_chat_gifs', item.id));
  }
  await assertSucceeds(batch.commit());
  assert.equal((await getDocs(ordered)).size, 200);
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    assert.equal((await getDoc(doc(db, 'global_chat_gifs', 'mixed-0001'))).exists(), false);
    assert.equal((await getDoc(doc(db, 'global_chat_panels', 'mixed-0002'))).exists(), false);
    for (const media of ['global_chat_gifs', 'global_chat_panels']) {
      for (const item of (await getDocs(collection(db, media))).docs) assert.equal((await getDoc(doc(db, 'global_chat', item.id))).exists(), true);
    }
  });
});

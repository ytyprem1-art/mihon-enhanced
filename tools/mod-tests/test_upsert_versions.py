"""Reproduce the browse UPSERT/trigger conflict, then verify the real trigger-only repair."""
from pathlib import Path
import re
import runpy
import sqlite3

root = Path(__file__).resolve().parents[2]
helpers = runpy.run_path(str(Path(__file__).with_name('test_upgrade.py')))
db = helpers['make_old']()
helpers['insert'](db, 'mangas', _id=10, source=100, url='/comic', title='Comic',
                  favorite=0, description='old', version=12, memo='{}')
helpers['insert'](db, 'chapters', _id=20, manga_id=10, url='/chapter', name='Chapter',
                  read=1, last_page_read=17, version=7, memo='{}')
helpers['insert'](db, 'history', _id=30, chapter_id=20, last_read=1000, time_read=1200)
for version in range(29, 37):
    db.executescript(helpers['sql']((root / f'data/src/main/sqldelight/tachiyomi/migrations/{version}.sqm').read_text(encoding='utf-8')))

source = (root / 'data/src/main/sqldelight/tachiyomi/data/manga.sq').read_text(encoding='utf-8')
upsert = source.split('insertNetworkManga {', 1)[1].split(';', 1)[0] + ';'
params = {key: None for key in re.findall(r':(\w+)', upsert)}
params.update(sourceId=100, remoteUrl='/comic', remoteTitle='Comic', remoteDescription='fresh',
              remoteStatus=0, remoteUpdateStrategy=0, remoteMemo='{}', userReaderFlags=0,
              userChapterFlags=0, stateChapterFetchInterval=0, stateInitialized=1,
              updateTitle=1, updateCover=0, updateDetails=1)
try:
    db.execute(upsert, params)
except sqlite3.IntegrityError as error:
    assert 'enhanced_manga_version.manga_id' in str(error), error
else:
    raise AssertionError('Expected the original browse UPSERT regression')

snapshot = db.execute('SELECT id, manga_id, user_read, user_last_page_read FROM chapter').fetchall()
db.executescript((root / 'data/src/main/sqldelight/tachiyomi/migrations/37.sqm').read_text(encoding='utf-8'))
assert db.execute('SELECT * FROM enhanced_manga_version').fetchall() == [(10, 12)]
for description, version in [('fresh', 13), ('fresh again', 14), ('fresh again', 14)]:
    params['remoteDescription'] = description
    db.execute(upsert, params)
    assert db.execute('SELECT version FROM enhanced_manga_version WHERE manga_id=10').fetchone()[0] == version
assert db.execute('SELECT id, manga_id, user_read, user_last_page_read FROM chapter').fetchall() == snapshot
assert db.execute('SELECT count(*) FROM history').fetchone()[0] == 1
assert db.execute('SELECT count(*) FROM manga').fetchone()[0] == 1

# Existing and missing counters both survive an outer chapter UPSERT.
db.execute('INSERT INTO chapter SELECT * FROM chapter WHERE id=20 ON CONFLICT(id) DO UPDATE SET user_read=0')
assert db.execute('SELECT version FROM enhanced_chapter_version WHERE chapter_id=20').fetchone()[0] == 8
assert db.execute('SELECT version FROM enhanced_manga_version WHERE manga_id=10').fetchone()[0] == 15
db.execute('DELETE FROM enhanced_chapter_version')
db.execute('DELETE FROM enhanced_manga_version')
db.execute('INSERT INTO chapter SELECT * FROM chapter WHERE id=20 ON CONFLICT(id) DO UPDATE SET user_read=1')
assert db.execute('SELECT version FROM enhanced_chapter_version WHERE chapter_id=20').fetchone()[0] == 1
assert db.execute('SELECT version FROM enhanced_manga_version WHERE manga_id=10').fetchone()[0] == 1
db.execute('UPDATE enhanced_sync_state SET restoring=1 WHERE id=1')
params['remoteDescription'] = 'restored'
db.execute(upsert, params)
assert db.execute('SELECT version FROM enhanced_manga_version WHERE manga_id=10').fetchone()[0] == 1
assert not db.execute('PRAGMA foreign_key_check').fetchall()
assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
print('PASS: reproduced original failure; migration37 preserves IDs/progress/history/versions and repairs browse/chapter UPSERTs, missing counters and restore suppression.')

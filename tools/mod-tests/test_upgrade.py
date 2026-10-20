"""Execute the real SQL migration against a synthetic released-mod database; never touch device data."""
import pathlib
import re
import sqlite3

root = pathlib.Path(__file__).resolve().parents[2]
new = root/'data/src/main/sqldelight/tachiyomi/migrations'

def sql(text):
    text = re.sub(r'^import .*?;\s*', '', text, flags=re.M)
    return re.sub(r'\b(INTEGER|TEXT|BLOB|REAL) AS [\w<>]+', r'\1', text)

def make_old():
    db = sqlite3.connect(':memory:')
    db.executescript((pathlib.Path(__file__).parent/'fixtures/schema-enhanced-v20.sql').read_text(encoding='utf-8'))
    return db

def insert(db, table, **values):
    for _, name, typ, required, default, pk in db.execute(f'PRAGMA table_info({table})'):
        if name not in values and required and default is None:
            values[name] = '' if typ in ('TEXT', 'BLOB') else 0
    cols = ','.join(values)
    db.execute(f'INSERT INTO {table} ({cols}) VALUES ({",".join("?" for _ in values)})', list(values.values()))

def migrate(db):
    for n in range(29,38):
        try:
            db.executescript(sql((new/f'{n}.sqm').read_text(encoding='utf-8')))
        except Exception as e:
            raise AssertionError(f'Migration {n}: {e}') from e

db = make_old()
for mid, url in [(10, '/child'), (11, '/child'), (20, '/merged')]:
    insert(db, 'mangas', _id=mid, source=100, url=url, title=f'Manga {mid}', favorite=1,
           date_added=1000, memo='{}', update_strategy=0, version=12)
for cid, mid in [(101,10), (102,11), (201,20)]:
    insert(db, 'chapters', _id=cid, manga_id=mid, url='/chapter/1', name='Chapter 1',
           read=1, last_page_read=7, memo='{}', chapter_number=1, version=7)
insert(db, 'history', _id=501, chapter_id=102, last_read=123456, time_read=12000)
insert(db, 'history_categories', _id=1, name='Manhwa', sort=0)
insert(db, 'history_category_mapping', _id=1, manga_id=11, category_id=1)
insert(db, 'history_groups', _id=1, name='Group')
insert(db, 'history_group_members', manga_id=11, group_id=1)
insert(db, 'linked_source_groups', _id=1, name='Linked')
insert(db, 'linked_source_members', _id=1, group_id=1, manga_id=11)
insert(db, 'update_watch', manga_id=11, background_refresh_enabled=1, last_background_check_at=999)
insert(db, 'update_watch_history', manga_id=11, timestamp=555, success=1, new_chapters=2, category='SUCCESS')
insert(db, 'update_watch_inbox', manga_id=11, manga_title='Child', source_id=100, source_name='Source',
       chapter_count=1, latest_chapter_id=102, chapter_ids='101, 102', first_found_at=111, last_found_at=222)
insert(db, 'merged', _id=1, merge_id=20, merge_url='/merged', manga_id=11, manga_url='/child', manga_source=100)
migrate(db)
child = db.execute("SELECT id FROM manga WHERE source_id=100 AND remote_url='/child'").fetchone()[0]
chapter = db.execute('SELECT id FROM chapter WHERE manga_id=?', (child,)).fetchone()[0]
assert db.execute('SELECT count(*) FROM manga').fetchone()[0] == 2
for table in ['history_category_mapping', 'history_group_members', 'linked_source_members', 'update_watch', 'update_watch_inbox', 'update_watch_history', 'merged']:
    assert db.execute(f'SELECT manga_id FROM {table}').fetchone()[0] == child, table
assert db.execute('SELECT latest_chapter_id FROM update_watch_inbox').fetchone()[0] == chapter
assert db.execute('SELECT user_read,user_last_page_read FROM chapter WHERE id=?',(chapter,)).fetchone() == (1,7)
assert db.execute('SELECT read_duration FROM history WHERE chapter_id=?',(chapter,)).fetchone()[0] == 12000
assert not db.execute('PRAGMA foreign_key_check').fetchall()
print('PASS: real SQL preserves reading progress, history, categories, groups, linked sources, merge, Update Watch state/inbox/history across upstream deduplication.')

assert db.execute('SELECT chapter_ids FROM update_watch_inbox').fetchone()[0] == str(chapter)
assert db.execute('SELECT version FROM enhanced_manga_version WHERE manga_id=?', (child,)).fetchone()[0] == 12
assert db.execute('SELECT version FROM enhanced_chapter_version WHERE chapter_id=?', (chapter,)).fetchone()[0] == 7
# Reading changes advance sync counters; restoring suppresses only its own transaction's changes.
db.execute('UPDATE chapter SET user_read=0 WHERE id=?',(chapter,))
assert db.execute('SELECT version FROM enhanced_chapter_version WHERE chapter_id=?',(chapter,)).fetchone()[0]==8
db.execute('UPDATE enhanced_sync_state SET restoring=1 WHERE id=1')
db.execute('UPDATE chapter SET user_read=1 WHERE id=?',(chapter,))
assert db.execute('SELECT version FROM enhanced_chapter_version WHERE chapter_id=?',(chapter,)).fetchone()[0]==8
db.execute('UPDATE enhanced_sync_state SET restoring=0 WHERE id=1')
print('PASS: chapter ID lists remapped, legacy sync counters retained, read changes tracked, restore suppression honored.')

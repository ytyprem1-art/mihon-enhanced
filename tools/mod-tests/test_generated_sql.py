"""Validate SQLDelight's emitted SQL, not just migration source text."""
from pathlib import Path
import re, json, sqlite3
root=Path(__file__).resolve().parents[2]
source=next((root/'data/build/generated').rglob('DatabaseImpl.kt')).read_text(encoding='utf-8')

def statements(block):
    pattern=r'driver.execute\(null, ("""[\s\S]*?"""\.trimMargin\(\)|"(?:\\.|[^"\\])*"), 0\)\.await\(\)'
    result=[]
    for match in re.finditer(pattern, block):
        literal=match[1]
        if literal.startswith('"""'):
            text=literal[3:literal.rindex('"""')]
            text='\n'.join(re.sub(r'^\s*\|','',line) for line in text.splitlines()).strip()
        else: text=json.loads(literal)
        result.append(text.replace("${'$'}",'$'))
    return result

def migrate(db):
    for match in re.finditer(r'if \(oldVersion <= (\d+) && newVersion > \d+\) \{([\s\S]*?)(?=\n      \})',source):
        version=int(match[1])
        if not 29<=version<=37:continue
        for sql in statements(match[2]):
            try:db.execute(sql)
            except Exception as e:raise AssertionError(f'Generated migration {version}: {sql[:500]}: {e}') from e

if __name__=='__main__':
    import runpy
    helpers=runpy.run_path(str(Path(__file__).with_name('test_upgrade.py')))
    db=helpers['make_old']()
    helpers['insert'](db,'mangas',_id=1,source=10,url='/test',title='Test',favorite=1,memo='{}')
    helpers['insert'](db,'chapters',_id=1,manga_id=1,url='/chapter',name='Chapter',read=1,last_page_read=12,memo='{}')
    helpers['insert'](db,'history',_id=1,chapter_id=1,last_read=1000,time_read=2000)
    migrate(db)
    assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    assert not db.execute('PRAGMA foreign_key_check').fetchall()
    assert db.execute('SELECT count(*) FROM history').fetchone()[0]==1
    assert db.execute('SELECT user_last_page_read FROM chapter').fetchone()[0]==12
    print('PASS: generated migration SQL preserves history and progress.')
    fresh=sqlite3.connect(':memory:')
    create=source[source.index('override fun create('):source.index('private fun migrateInternal')]
    for sql in statements(create):fresh.execute(sql)
    assert not fresh.execute('PRAGMA foreign_key_check').fetchall()
    print('PASS: generated clean-install schema executes successfully.')

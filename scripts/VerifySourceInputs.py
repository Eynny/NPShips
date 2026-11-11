"""Public-source checks only. This does not compile or validate Minecraft behavior."""

import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'gradle/dependency-integrity.json').read_text(encoding='utf8'))
wrapper = root / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(wrapper.read_bytes()).hexdigest() == manifest['wrapperSha256'], 'Wrapper JAR mismatch'
properties = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf8')
assert 'distributionSha256Sum=72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f' in properties
license_text = ' '.join((root / 'LICENSE').read_text(encoding='utf8').split())
template = ' '.join((root / 'src/main/resources/npships/licenses/MIT.txt').read_text(encoding='utf8')
                    .replace('<year>', '2026').replace('<copyright holders>', 'Eynny').split())
assert license_text == template, 'Original-source MIT terms differ from the retained standard template'
assert (root / 'TEMPLATE_LICENSE.txt').is_file(), 'Missing independent MDK notice'
en = json.loads((root / 'src/main/resources/assets/npships/lang/en_us.json').read_text(encoding='utf8'))
ru = json.loads((root / 'src/main/resources/assets/npships/lang/ru_ru.json').read_text(encoding='utf8'))
assert en.keys() == ru.keys(), 'EN/RU key mismatch'
print('PASS: wrapper hash, distribution checksum, standard MIT, MDK notice, EN/RU (' + str(len(en)) + ' keys).')
print('SOURCE CHECK ONLY: provisioned production build and Minecraft tests are separate gates.')

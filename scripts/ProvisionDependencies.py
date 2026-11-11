"""Copy exact legitimately obtained build inputs without downloading or overwriting.

The hashes identify reviewed binaries; they do not grant redistribution rights.
Obtain these exact inputs before running --from-dir <inputs> --to-dir libs:

Simulated: simulated-neoforge-1.21.1-1.3.2.jar (6,315,366 bytes), SHA-256
fdf9d250996a084b52fced3a5b0089e879a5cac05dc7bcf295ac1f4ebe5e2bff.
Extract META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.2.jar
from the legitimately obtained official Aeronautics 44pLdPGg bundle, outer SHA-256
a3f330d4757640f8715d991a43eedb826e4d0e51558d859c843ae6693bd35f1e.
Retain its upstream LICENSE.md. This script does not download or extract the bundle.

Companion: sable-companion-common-1.6.0.jar (35,448 bytes), SHA-256
156e277a5656f71bb7e267a5c9061f6b41965bd9c51e5e47428cbd1f980a3e7f.
Rebuild the pinned official source with Java 21:
python scripts/BootstrapCompanion.py --work-dir <new-work-dir> --output-dir <inputs>
The bootstrap checks official source commit 652ecf0e051846f3a433dd20126dd4d64ec3793a
and exact source/output hashes from gradle/companion-bootstrap.json. Other binaries
of the same version are not substitutes. Keep dependency binaries outside Git and
source archives. See NPShipsThirdPartyNotices.md for the distribution boundary.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(data)
    return digest.hexdigest()


def provision(source, target, manifest):
    planned = []
    # Validate every input/destination before making any copy.
    for entry in manifest['local']:
        name = entry['file']
        if Path(name).name != name or '/' in name or '\\' in name:
            raise ValueError('Manifest contains an unsafe filename: ' + name)
        src, dst = source / name, target / name
        if src.is_symlink() or not src.is_file() or src.stat().st_size != entry['bytes'] or sha256(src) != entry['sha256']:
            raise ValueError('Missing/mismatched input ' + name + '; expected SHA-256 ' + entry['sha256'])
        if dst.is_symlink() or (dst.exists() and (not dst.is_file() or sha256(dst) != entry['sha256'])):
            raise ValueError('Refusing to overwrite destination: ' + str(dst))
        planned.append((src, dst, entry))
    target.mkdir(parents=True, exist_ok=True)
    for src, dst, entry in planned:
        if not dst.exists():
            # Exclusive creation also refuses a destination created after preflight.
            created = False
            try:
                with dst.open('xb') as output:
                    created = True
                    with src.open('rb') as original:
                        shutil.copyfileobj(original, output, 1024 * 1024)
                if dst.stat().st_size != entry['bytes'] or sha256(dst) != entry['sha256']:
                    raise ValueError('Copy verification failed: ' + str(dst))
            except BaseException:
                if created:
                    dst.unlink()  # Only this invocation's exclusively created incomplete copy.
                raise
        print('Verified ' + str(dst) + ' SHA-256 ' + entry['sha256'])


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--from-dir', required=True, type=Path, help='Private legitimately obtained exact inputs')
    parser.add_argument('--to-dir', type=Path, default=root / 'libs')
    args = parser.parse_args()
    manifest = json.loads((root / 'gradle/dependency-integrity.json').read_text(encoding='utf8'))
    try:
        provision(args.from_dir.resolve(), args.to_dir.resolve(), manifest)
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + '\nRun python scripts/ProvisionDependencies.py --help; no version substitution is supported.\n')


if __name__ == '__main__':
    main()

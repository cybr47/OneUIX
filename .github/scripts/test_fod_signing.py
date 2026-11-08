import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from prepare_fod_signing import prepare


class FodSigningTest(unittest.TestCase):
    def test_missing_secret_does_not_fall_back_to_a_debug_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            with self.assertRaises(ValueError):
                prepare(root, {})
            self.assertEqual([], list((root / "app").iterdir()))

    def test_real_p12_and_properties_round_trip_without_shell_interpretation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            key = root / "fixture.p12"
            password = " test:pass=word\\with$symbols"
            environment = {**os.environ, "P12_PASSWORD": password}
            subprocess.run([
                "keytool", "-genkeypair", "-alias", "test-signing", "-dname", "CN=FOD test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1",
                "-keystore", str(key), "-storetype", "PKCS12", "-storepass:env", "P12_PASSWORD",
            ], env=environment, check=True, capture_output=True)
            prepare(root, {"P12_BASE64": base64.b64encode(key.read_bytes()).decode(),
                           "P12_PASSWORD": password})
            self.assertEqual(0o600, (root / "app/fod-signing.p12").stat().st_mode & 0o777)
            self.assertEqual(0o600, (root / "keystore.properties").stat().st_mode & 0o777)
            # Let Java's actual Properties parser verify all escaping.
            source = root / "Verify.java"
            source.write_text('''
                import java.nio.file.*;
                import java.util.*;
                class Verify {
                    public static void main(String[] args) throws Exception {
                        Properties p = new Properties();
                        try (var in = Files.newInputStream(Path.of(args[0]))) { p.load(in); }
                        if (!p.getProperty("storePassword").equals(System.getenv("P12_PASSWORD"))
                            || !p.getProperty("keyPassword").equals(System.getenv("P12_PASSWORD"))
                            || !p.getProperty("keyAlias").equals("test-signing")
                            || !p.getProperty("storeFile").equals("fod-signing.p12")) {
                            throw new AssertionError("Signing properties changed");
                        }
                    }
                }
            ''')
            subprocess.run(["java", str(source), str(root / "keystore.properties")],
                           env=environment, check=True, capture_output=True)

    def test_existing_files_are_not_overwritten_or_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            key = root / "app/fod-signing.p12"
            key.write_bytes(b"existing")
            with self.assertRaises(FileExistsError):
                prepare(root, {"P12_BASE64": base64.b64encode(b"new").decode(), "P12_PASSWORD": "test"})
            self.assertEqual(b"existing", key.read_bytes())


if __name__ == "__main__":
    unittest.main()

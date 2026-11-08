"""Use the fork's existing P12 secrets; never cache or print signing material."""
import base64
import os
from pathlib import Path
import subprocess


def properties_value(value: str) -> str:
    # Properties.load(InputStream) is Latin-1: escape UTF-16 units, including
    # whitespace, separators and newlines, without shell interpolation.
    encoded = value.encode("utf-16-be")
    return "".join("\\u" + encoded[i:i + 2].hex() for i in range(0, len(encoded), 2))


def prepare(root: Path, environment: dict[str, str]) -> None:
    encoded = environment.get("P12_BASE64", "")
    password = environment.get("P12_PASSWORD", "")
    if not encoded or not password:
        raise ValueError("Existing repository secrets P12_BASE64 and P12_PASSWORD are required")
    data = base64.b64decode("".join(encoded.split()), validate=True)
    key = root / "app" / "fod-signing.p12"
    properties = root / "keystore.properties"
    key_created = False
    properties_created = False
    try:
        with key.open("xb") as stream:
            key_created = True
            os.chmod(key, 0o600)
            stream.write(data)
        result = subprocess.run([
            "keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
            "-keystore", str(key), "-storetype", "PKCS12", "-storepass:env", "P12_PASSWORD",
        ], env={**os.environ, "P12_PASSWORD": password}, capture_output=True, text=True, check=True)
        current = None
        alias = None
        for line in result.stdout.splitlines():
            if line.startswith("Alias name: "):
                current = line.removeprefix("Alias name: ")
            elif line.strip() == "Entry type: PrivateKeyEntry":
                alias = current
                break
        if not alias:
            raise ValueError("No private signing key in the repository P12 secret")
        values = {"storeFile": key.name, "storePassword": password,
                  "keyAlias": alias, "keyPassword": password}
        with properties.open("x", encoding="ascii") as stream:
            properties_created = True
            os.chmod(properties, 0o600)
            stream.writelines(f"{name}={properties_value(value)}\n" for name, value in values.items())
    except BaseException:
        if key_created:
            key.unlink(missing_ok=True)
        if properties_created:
            properties.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    try:
        prepare(Path(__file__).resolve().parents[2], dict(os.environ))
    except Exception:
        # Do not include subprocess output, configuration or secret values in logs.
        raise SystemExit("FOD signing setup failed. Check P12_BASE64 and P12_PASSWORD; no debug-key fallback.")
    print("FOD signing configured from repository secrets.")

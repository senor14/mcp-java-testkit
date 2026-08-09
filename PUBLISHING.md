# Publishing to Maven Central

One-time setup, then each release is a single command.

## One-time setup

### 1. Central Portal account + namespace
1. Go to https://central.sonatype.com and **Sign in with GitHub** (as `senor14`).
2. Open **Namespaces** and add `io.github.senor14` — GitHub-based namespaces are verified automatically for the matching account.

### 2. Publishing token
1. Central Portal → account menu → **View Account** → **Generate User Token**.
2. Put the generated pair in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username><!-- token username --></username>
      <password><!-- token password --></password>
    </server>
  </servers>
</settings>
```

### 3. GPG signing key
```powershell
winget install GnuPG.GnuPG
gpg --full-generate-key        # RSA 4096, your name + GitHub email
gpg --list-keys --keyid-format long   # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
```

## Releasing a version

```powershell
# 1. Drop -SNAPSHOT in pom.xml (e.g. 0.1.0), commit
# 2. Deploy (builds jar + sources + javadoc, signs, uploads, auto-publishes)
#    - gpg.executable: Git-bundled GnuPG (nothing else installed on this machine)
#    - gpg.keyname with trailing "!": force-sign with the PRIMARY key. Without it GPG
#      signs with the newest signing-capable subkey, and Central then looks the key up
#      by the SUBKEY fingerprint — which keys.openpgp.org cannot serve and Ubuntu may
#      not have indexed yet, failing validation with "Could not find a public key".
mvn -B -Prelease "-Dgpg.executable=C:\Program Files\Git\usr\bin\gpg.exe" "-Dgpg.keyname=158E856D5A08A0D4DF28EA0A90963797DDB4DB38!" deploy
# 3. Tag and push
git tag -a v0.1.0 -m "v0.1.0"; git push origin v0.1.0
# 4. Bump pom.xml to next 0.2.0-SNAPSHOT, commit
```

Artifacts appear on https://central.sonatype.com/artifact/io.github.senor14/mcp-java-testkit within minutes; search indexing takes a few hours.

Notes:
- Local dry-run without credentials/GPG: `mvn -Prelease "-Dgpg.skip=true" "-DskipTests" package`
- On PowerShell, always quote `-D` flags containing dots.
- Periodically check for a newer `central-publishing-maven-plugin` on central.sonatype.com.

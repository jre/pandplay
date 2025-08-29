#!/bin/sh

set -x
set -e

umask 077

ksfile="${GITHUB_WORKSPACE:?error: GITHUB_WORKSPACE not set}/keystore.jks"
propfile="${GITHUB_WORKSPACE}/keystore.properties"

rm -f "$ksfile"
cat <<EOF | base64 -di > "$ksfile"
${KEYSTORE_BASE64:?error: KEYSTORE_BASE64 not set}
EOF

rm -f "$propfile"
cat <<EOF > "$propfile"
storeFile=$ksfile
storePassword=${KEYSTORE_PASSWORD:?error: KEYSTORE_PASSWORD not set}
keyPassword=${KEY_PASSWORD:?error: KEY_PASSWORD not set}
keyAlias=${KEY_ALIAS:?error: KEY_ALIAS not set}
EOF

#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

version="$1"

README_VERSION="$version" perl -0pi -e '
  my $version = $ENV{"README_VERSION"};
  s{(<artifactId>aws-oidc-s3-maven-wagon</artifactId>\s*<version>)[^<]+(</version>)}{$1$version$2}g;
' README.md

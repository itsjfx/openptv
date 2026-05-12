#!/usr/bin/env bash

set -eu -o pipefail
set -a

cd "$(dirname "$0")/../backend"
source ../.env
make run

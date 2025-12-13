#!/bin/bash
pushd $(dirname $0)
git pull && docker build . -t borispetrovich:latest && (docker compose down || true) && docker compose up -d
popd

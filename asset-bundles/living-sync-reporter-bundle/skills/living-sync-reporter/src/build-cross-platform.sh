#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bin_dir="$script_dir/../bin"

rm -rf "$bin_dir/linux-amd64" "$bin_dir/linux-arm64" "$bin_dir/darwin-amd64" "$bin_dir/darwin-arm64" "$bin_dir/windows-amd64"
mkdir -p "$bin_dir/linux-amd64" "$bin_dir/linux-arm64" "$bin_dir/darwin-amd64" "$bin_dir/darwin-arm64" "$bin_dir/windows-amd64"

build() {
  os="$1"
  arch="$2"
  ext="$3"
  out="$bin_dir/$os-$arch/living-sync-reporter$ext"
  CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath -ldflags='-s -w' -o "$out" "$script_dir"
}

build linux amd64 ""
build linux arm64 ""
build darwin amd64 ""
build darwin arm64 ""
build windows amd64 ".exe"

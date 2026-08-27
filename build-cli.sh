#!/bin/bash
# Builds the validation CLI without SwiftPM (works with CommandLineTools only).
# The SPM layout in Package.swift remains valid for opening in Xcode.
set -e
cd "$(dirname "$0")"
mkdir -p .build-direct
sed '/^import VialCore$/d' Sources/vialscan/main.swift > .build-direct/main.swift
swiftc -O -o vialscan Sources/VialCore/*.swift .build-direct/main.swift
echo "built ./vialscan"

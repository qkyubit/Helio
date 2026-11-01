#!/bin/sh
# Turns NVIDIA's static NGX loader (libnvsdk_ngx.a) into a shared library Java's FFM can load,
# plus ngx_trampoline.c (see there for why). Only NVSDK_NGX_* and helio_* are exported; libstdc++
# is the system one (libstdc++.so.6 is on every distro), keeping the .so small.
# Usage: native/build-ngx.sh <path to DLSS SDK checkout>
set -e
SDK=${1:?usage: $0 <DLSS SDK dir>}
DIR=$(dirname "$0")
OUT=$DIR/../src/client/resources/natives/linux-x86_64
mkdir -p "$OUT"
g++ -shared -fPIC -O2 -o "$OUT/libhelio_ngx.so" -x c "$DIR/ngx_trampoline.c" -x none \
	-Wl,--whole-archive "$SDK/lib/Linux_x86_64/libnvsdk_ngx.a" -Wl,--no-whole-archive \
	-Wl,--version-script="$DIR/ngx.map" -ldl -Wl,-z,defs -s
echo "built $OUT/libhelio_ngx.so"

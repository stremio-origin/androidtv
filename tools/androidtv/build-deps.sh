#!/bin/bash
# Builds the native libraries Android TV needs, with the Android NDK, once per ABI, into build/atv-<abi>/deps:
#   libSDL2.so (from the SDL2 source), libcurl.so (curl on mbedTLS, WebSockets on, the plain resolver: the threaded
#   one closes a descriptor twice and Android aborts the app for it; the app links it)
#   tools/androidtv/build-deps.sh arm64-v8a | armeabi-v7a
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ABI="${1:-arm64-v8a}"; API="${API:-21}"
PRIV="${ORIGIN_BUILD:-$HOME/origin-build}"
NDK="${NDK:-$PRIV/android/sdk/ndk/27.2.12479018}"
SDLSRC="${SDLSRC:-$PRIV/mac/SDL2-2.30.12}"
W="$PRIV/android/work-atv/$ABI"; OUT="$ROOT/build/atv-$ABI/deps"; mkdir -p "$W" "$OUT/lib" "$OUT/include"
TC=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DCMAKE_BUILD_TYPE=MinSizeRel -G Ninja
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")   # 16 KB pages: Android 15+ devices with 16 KB pages refuse a library laid out for 4 KB
REALPRIV="$(cd "$PRIV" && pwd -P)"   # (the build folder may be a link: CMake records its real path)
FL="-ffile-prefix-map=$HOME=/src -ffile-prefix-map=$PRIV=/build -ffile-prefix-map=$REALPRIV=/build -ffile-prefix-map=$ROOT=/app"   # no local paths or folder names in the libraries (the last map that fits is the one used)

# mbedTLS and curl built for speed (-O2: the TLS of every picture, addon and preview; 32-bit boxes do AES in software),
# SDL2 for size
if [ ! -f "$OUT/lib/libmbedtls.a" ]; then
  echo "== mbedTLS ($ABI)"
  [ -d "$W/mbedtls" ] || { mkdir -p "$W/mbedtls"; tar -xjf "$PRIV/src/mbedtls-3.6.2.tar.bz2" -C "$W/mbedtls" --strip-components 1; }
  cmake -S "$W/mbedtls" -B "$W/mbedtls-build" "${TC[@]}" -DCMAKE_BUILD_TYPE=Release -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DMBEDTLS_FATAL_WARNINGS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_C_FLAGS="$FL" -DCMAKE_INSTALL_PREFIX="$OUT" >/dev/null
  cmake --build "$W/mbedtls-build" >/dev/null && cmake --install "$W/mbedtls-build" >/dev/null
fi
if [ ! -f "$OUT/lib/libcurl.so" ]; then
  echo "== curl ($ABI)"
  [ -d "$W/curl" ] || { mkdir -p "$W/curl"; tar -xJf "$PRIV/src/curl-8.11.1.tar.xz" -C "$W/curl" --strip-components 1; }
  cmake -S "$W/curl" -B "$W/curl-build" "${TC[@]}" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF -DBUILD_LIBCURL_DOCS=OFF -DBUILD_MISC_DOCS=OFF -DENABLE_CURL_MANUAL=OFF \
    -DCURL_USE_MBEDTLS=ON -DCURL_USE_OPENSSL=OFF -DMBEDTLS_INCLUDE_DIR="$OUT/include" -DMBEDTLS_LIBRARY="$OUT/lib/libmbedtls.a" -DMBEDX509_LIBRARY="$OUT/lib/libmbedx509.a" -DMBEDCRYPTO_LIBRARY="$OUT/lib/libmbedcrypto.a" \
    -DCURL_USE_LIBPSL=OFF -DCURL_USE_LIBSSH2=OFF -DUSE_NGHTTP2=OFF -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF -DUSE_LIBIDN2=OFF -DCURL_DISABLE_LDAP=ON -DENABLE_WEBSOCKETS=ON -DENABLE_THREADED_RESOLVER=OFF \
    -DCURL_CA_BUNDLE=none -DCURL_CA_PATH=none -DCMAKE_C_FLAGS="$FL" -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" -DCMAKE_INSTALL_PREFIX="$OUT" >/dev/null
  cmake --build "$W/curl-build" >/dev/null && cmake --install "$W/curl-build" >/dev/null
fi
if [ ! -f "$OUT/lib/libSDL2.so" ]; then
  echo "== SDL2 ($ABI)"
  cmake -S "$SDLSRC" -B "$W/sdl-build" "${TC[@]}" -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF -DCMAKE_C_FLAGS="$FL" -DCMAKE_CXX_FLAGS="$FL" -DCMAKE_INSTALL_PREFIX="$OUT" >/dev/null
  cmake --build "$W/sdl-build" >/dev/null && cmake --install "$W/sdl-build" >/dev/null
fi
ls -l "$OUT/lib/"*.so | awk '{printf "%s  %.1f MB\n", $NF, $5/1048576}'

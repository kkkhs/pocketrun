# Third-party notices

This app was generated from [`@react-native-community/template` 0.88.1](https://github.com/react-native-community/template/tree/0.88-stable). The notices below apply to upstream files, not as a license grant for all PocketRun code.

## React Native template and source files

The template's MIT license is reproduced below. React Native-derived files also retain their existing Meta Platforms copyright headers.

MIT License

Copyright (c) 2018 react-native-community

Copyright (c) Meta Platforms, Inc. and affiliates.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Gradle wrapper and Android resources

The Gradle wrapper is licensed under Apache License 2.0. Its full license is included in `android/gradle/wrapper/gradle-wrapper.jar` at `META-INF/LICENSE`; the startup scripts retain their copyright and license headers.

Generated Android resources retain any Android Open Source Project notices in their source files. Apache License 2.0 is also available at <https://www.apache.org/licenses/LICENSE-2.0>.

## Embedded ADB client

The chapter 2 ADB client includes source adapted from RikkaApps/Shizuku commit `b844bc491f1790c72328e1a8e5b2349f8978f0ea` under Apache License 2.0. The complete source provenance and local changes are in [`android/adb/UPSTREAM.md`](android/adb/UPSTREAM.md). Its license is at `android/adb/LICENSE` and is also packaged in the APK.

Native and crypto dependencies keep their own licenses; the APK packages the relevant Shizuku, BoringSSL, Conscrypt, LLVM libc++ and Bouncy Castle notices under `android/adb/src/main/assets/third-party/`. They are not collectively relicensed as Apache-2.0.

## Official Anthropic Java SDK

Chapter 11 links the unmodified official [`anthropics/anthropic-sdk-java`](https://github.com/anthropics/anthropic-sdk-java) Maven release `com.anthropic:anthropic-java:3.11.0` (Apache License 2.0, Copyright 2023 Anthropic, PBC). Core and OkHttp client modules retain their upstream license resources under `META-INF/licenses/` in the dependency jars; Android dependency versions are frozen in `android/app/gradle.lockfile`.

Jackson, OkHttp/Okio, Kotlin and the SDK's schema/runtime dependencies keep their own upstream licenses; adding the SDK does not relicense PocketRun or those dependencies. No SDK source is copied into the App; the local synthetic protocol tests use demo data and are not official model results.

Other dependencies retain their own licenses in the installed packages. `android/app/debug.keystore` is the template's public development signing key, not a production signing credential.

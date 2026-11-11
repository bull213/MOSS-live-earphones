# Moonshine Android JNI compatibility fix

These sources are from `moonshine-ai/moonshine` tag `v0.1.6`, under the MIT
license. The two audio-input JNI functions have been changed to release the
float array acquired from the Android runtime, including on exceptions. The
upstream implementation does not release it; see
https://github.com/moonshine-ai/moonshine/issues/232. The Moonshine model and
inference libraries remain the official Maven 0.1.6 binaries.

The three `app/src/main/jniLibs/*/libmoonshine-jni.so` files are built from
these sources using Android NDK r28c and the matching Maven 0.1.6 AAR. Rebuild
them using `scripts/build-moonshine-jni.ps1`, then run the offline streaming and
native-memory tests before changing the SDK or replacing the binaries. The
application's copy is selected during Android's native-library merge.

`utf8.h` and `utf8/` are the UTF8-CPP headers shipped with Moonshine. Their
license is included in the app's `assets/licenses/utf8cpp-license.txt`.

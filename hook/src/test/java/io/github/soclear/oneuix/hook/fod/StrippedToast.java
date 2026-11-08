package io.github.soclear.oneuix.hook.fod;

// A top-level '$' name reproduces a firmware class without InnerClass metadata.
enum StrippedToast$ToastType { FingerprintAuthenticationHelp, FaceAuthenticationError }

final class StrippedToast {
    public void update(StrippedToast$ToastType type, String text, boolean a, boolean b, boolean c) {}
    public void update(String text, StrippedToast$ToastType type) {}
    public void update(String unrelatedText) {}
}

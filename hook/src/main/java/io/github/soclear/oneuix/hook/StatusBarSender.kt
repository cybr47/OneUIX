package io.github.soclear.oneuix.hook

internal object StatusBarSender {
    fun accepts(expectedPackage: String, expectedUid: Int, senderPackage: String?, senderUid: Int) =
        expectedUid >= 0 && senderUid == expectedUid && senderPackage == expectedPackage
}

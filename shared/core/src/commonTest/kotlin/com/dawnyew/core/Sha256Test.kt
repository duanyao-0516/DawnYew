package com.dawnyew.core
import kotlin.test.*
class Sha256Test {
    @Test fun standardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",Sha256.hex(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Sha256.hex("abc".encodeToByteArray()))
        val hash=Sha256();repeat(1000) { hash.update("a".repeat(1000).encodeToByteArray()) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",hash.finishHex())
    }
}

#include <jni.h>
#include <sodium.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <limits.h>

#define CHUNK 65536
#define EXTRA 17
#define AAD_BYTES 92
#define AUTH_ERROR "口令不正确或备份已损坏"
#define JNI_FN(name) Java_com_dawnyew_platform_crypto_Sodium_##name

_Static_assert(crypto_pwhash_SALTBYTES == 16, "salt profile mismatch");
_Static_assert(crypto_secretstream_xchacha20poly1305_KEYBYTES == 32, "key profile mismatch");
_Static_assert(crypto_secretstream_xchacha20poly1305_HEADERBYTES == 24, "header profile mismatch");
_Static_assert(crypto_secretstream_xchacha20poly1305_ABYTES == EXTRA, "frame profile mismatch");

typedef struct stream_state {
    uint64_t id;
    int direction;
    int finished;
    crypto_secretstream_xchacha20poly1305_state state;
    struct stream_state *next;
} stream_state;
static pthread_mutex_t states_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t kdf_mutex = PTHREAD_MUTEX_INITIALIZER;
static stream_state *states;
static uint64_t next_id = 1;

static void fail(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, type);
    if (cls) (*env)->ThrowNew(env, cls, message);
}
static int bytes(JNIEnv *env, jbyteArray array, int required) {
    if (!array || (*env)->GetArrayLength(env, array) != required) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid native crypto buffer");
        return 0;
    }
    return 1;
}
static jbyteArray result(JNIEnv *env, const unsigned char *data, size_t size) {
    jbyteArray array = (*env)->NewByteArray(env, (jsize) size);
    if (array) (*env)->SetByteArrayRegion(env, array, 0, (jsize) size, (const jbyte *) data);
    return array;
}
static stream_state *lookup(JNIEnv *env, jlong id, int direction) {
    for (stream_state *p = states; p; p = p->next) {
        if (p->id == (uint64_t) id && p->direction == direction && !p->finished) return p;
    }
    fail(env, "java/lang/IllegalStateException", "Crypto stream is closed or invalid");
    return NULL;
}
JNIEXPORT void JNICALL JNI_FN(initialize)(JNIEnv *env, jobject self) {
    (void) self;
    if (sodium_init() < 0 || strcmp(sodium_version_string(), "1.0.22") != 0)
        fail(env, "java/lang/IllegalStateException", "libsodium initialization/version mismatch");
}
JNIEXPORT jbyteArray JNICALL JNI_FN(randomBytes)(JNIEnv *env, jobject self, jint size) {
    (void) self;
    if (size < 1 || size > CHUNK) { fail(env, "java/lang/IllegalArgumentException", "Invalid random buffer size"); return NULL; }
    unsigned char buffer[CHUNK];
    randombytes_buf(buffer, (size_t) size);
    jbyteArray out = result(env, buffer, (size_t) size);
    sodium_memzero(buffer, sizeof buffer);
    return out;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(deriveKey)(JNIEnv *env, jobject self, jbyteArray password, jbyteArray salt) {
    (void) self;
    if (!password || !bytes(env, salt, 16)) return NULL;
    jsize length = (*env)->GetArrayLength(env, password);
    if (length < 12 || length > 512) { fail(env, "java/lang/IllegalArgumentException", "Invalid password encoding length"); return NULL; }
    unsigned char p[512] = {0}, s[16], key[32] = {0};
    (*env)->GetByteArrayRegion(env, password, 0, length, (jbyte *) p);
    (*env)->GetByteArrayRegion(env, salt, 0, 16, (jbyte *) s);
    jbyteArray out = NULL;
    if ((*env)->ExceptionCheck(env)) goto done;
    // Reject concurrent requests instead of queuing unbounded 64 MiB KDF jobs.
    if (pthread_mutex_trylock(&kdf_mutex) != 0) { fail(env, "java/io/IOException", "已有口令派生作业正在执行"); goto done; }
    int status = crypto_pwhash(key, sizeof key, (const char *) p, (unsigned long long) length,
                             s, 3, 67108864, crypto_pwhash_ALG_ARGON2ID13);
    pthread_mutex_unlock(&kdf_mutex);
    if (status != 0) fail(env, "java/io/IOException", "口令派生失败，内存资源不足");
    else out = result(env, key, sizeof key);
done:
    sodium_memzero(p, sizeof p); sodium_memzero(key, sizeof key); sodium_memzero(s, sizeof s);
    return out;
}
static jlong initialize_stream(JNIEnv *env, jbyteArray key, jbyteArray header, int direction) {
    if (!bytes(env, key, 32) || !bytes(env, header, 24)) return 0;
    unsigned char k[32], h[24];
    (*env)->GetByteArrayRegion(env, key, 0, 32, (jbyte *) k);
    if (!direction) (*env)->GetByteArrayRegion(env, header, 0, 24, (jbyte *) h);
    stream_state *s = NULL;
    jlong id = 0;
    if ((*env)->ExceptionCheck(env)) goto done;
    s = sodium_malloc(sizeof *s);
    if (!s) { fail(env, "java/lang/OutOfMemoryError", "Cannot allocate crypto state"); goto done; }
    memset(s, 0, sizeof *s);
    int status = direction ? crypto_secretstream_xchacha20poly1305_init_push(&s->state, h, k)
                           : crypto_secretstream_xchacha20poly1305_init_pull(&s->state, h, k);
    if (status != 0) { fail(env, "java/io/IOException", AUTH_ERROR); goto done; }
    if (direction) (*env)->SetByteArrayRegion(env, header, 0, 24, (jbyte *) h);
    if ((*env)->ExceptionCheck(env)) goto done;
    pthread_mutex_lock(&states_mutex);
    if (next_id > INT64_MAX) fail(env, "java/lang/IllegalStateException", "Crypto state counter exhausted");
    else {
        s->id = next_id++; s->direction = direction; s->next = states; states = s;
        id = (jlong) s->id; s = NULL;
    }
    pthread_mutex_unlock(&states_mutex);
done:
    if (s) { sodium_memzero(s, sizeof *s); sodium_free(s); }
    sodium_memzero(k, sizeof k); sodium_memzero(h, sizeof h);
    return id;
}
JNIEXPORT jlong JNICALL JNI_FN(initPush)(JNIEnv *env, jobject self, jbyteArray key, jbyteArray header) {
    (void) self; return initialize_stream(env, key, header, 1);
}
JNIEXPORT jlong JNICALL JNI_FN(initPull)(JNIEnv *env, jobject self, jbyteArray key, jbyteArray header) {
    (void) self; return initialize_stream(env, key, header, 0);
}
JNIEXPORT jbyteArray JNICALL JNI_FN(push)(JNIEnv *env, jobject self, jlong id, jbyteArray plain, jint length, jbyteArray aad, jboolean final) {
    (void) self;
    if (!plain || !bytes(env, aad, AAD_BYTES) || length < 0 || length > CHUNK || (*env)->GetArrayLength(env, plain) < length) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid plaintext frame"); return NULL;
    }
    unsigned char p[CHUNK], a[AAD_BYTES], c[CHUNK + EXTRA];
    (*env)->GetByteArrayRegion(env, plain, 0, length, (jbyte *) p);
    (*env)->GetByteArrayRegion(env, aad, 0, AAD_BYTES, (jbyte *) a);
    jbyteArray out = NULL;
    if ((*env)->ExceptionCheck(env)) goto done;
    unsigned long long clen = 0;
    pthread_mutex_lock(&states_mutex);
    stream_state *s = lookup(env, id, 1);
    if (s) {
        int status = crypto_secretstream_xchacha20poly1305_push(&s->state, c, &clen, p, (unsigned long long) length,
                        a, sizeof a, final ? crypto_secretstream_xchacha20poly1305_TAG_FINAL : crypto_secretstream_xchacha20poly1305_TAG_MESSAGE);
        if (status != 0 || clen != (unsigned long long) length + EXTRA) { s->finished = 1; fail(env, "java/io/IOException", "Backup encryption failed"); }
        else { s->finished = final ? 1 : 0; out = result(env, c, (size_t) clen); }
    }
    pthread_mutex_unlock(&states_mutex);
done:
    sodium_memzero(p, sizeof p); sodium_memzero(c, sizeof c); sodium_memzero(a, sizeof a);
    return out;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(pull)(JNIEnv *env, jobject self, jlong id, jbyteArray cipher, jbyteArray aad) {
    (void) self;
    if (!cipher || !bytes(env, aad, AAD_BYTES)) return NULL;
    jsize length = (*env)->GetArrayLength(env, cipher);
    if (length < EXTRA || length > CHUNK + EXTRA) { fail(env, "java/lang/IllegalArgumentException", "Invalid ciphertext frame"); return NULL; }
    unsigned char c[CHUNK + EXTRA], a[AAD_BYTES], p[CHUNK + 1];
    (*env)->GetByteArrayRegion(env, cipher, 0, length, (jbyte *) c);
    (*env)->GetByteArrayRegion(env, aad, 0, AAD_BYTES, (jbyte *) a);
    jbyteArray out = NULL;
    if ((*env)->ExceptionCheck(env)) goto done;
    unsigned long long plen = 0;
    pthread_mutex_lock(&states_mutex);
    stream_state *s = lookup(env, id, 0);
    if (s) {
        int status = crypto_secretstream_xchacha20poly1305_pull(&s->state, p + 1, &plen, p, c, (unsigned long long) length, a, sizeof a);
        if (status != 0 || plen != (unsigned long long) length - EXTRA) { s->finished = 1; fail(env, "java/io/IOException", AUTH_ERROR); }
        else { s->finished = p[0] == crypto_secretstream_xchacha20poly1305_TAG_FINAL; out = result(env, p, (size_t) plen + 1); }
    }
    pthread_mutex_unlock(&states_mutex);
done:
    sodium_memzero(c, sizeof c); sodium_memzero(a, sizeof a); sodium_memzero(p, sizeof p);
    return out;
}
JNIEXPORT void JNICALL JNI_FN(destroy)(JNIEnv *env, jobject self, jlong id) {
    (void) env; (void) self;
    pthread_mutex_lock(&states_mutex);
    stream_state **link = &states;
    while (*link) {
        stream_state *s = *link;
        if (s->id == (uint64_t) id) { *link = s->next; sodium_memzero(s, sizeof *s); sodium_free(s); break; }
        link = &s->next;
    }
    pthread_mutex_unlock(&states_mutex);
}

/* TEST ONLY: deterministic RNG must never be linked into the application. */
#include <sodium.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <assert.h>

static unsigned char rng_cursor;
static const char *rng_name(void) { return "dawnyew-test-only-sequential"; }
static void rng_buf(void *out, size_t n) { unsigned char *p=out; while(n--) *p++=rng_cursor++; }
static uint32_t rng_random(void) { uint32_t r; rng_buf(&r,sizeof r); return r; }
static randombytes_implementation rng = {rng_name,rng_random,NULL,NULL,rng_buf,NULL};
static void be(unsigned char *dst, uint64_t value, size_t n) {
    while(n) { dst[--n]=(unsigned char)(value&255); value >>= 8; }
}
static void hex(const unsigned char *p,size_t n) { for(size_t i=0;i<n;i++) printf("%02x",p[i]); }
static void fixture(size_t n,const unsigned char *key,const unsigned char *salt) {
    char filename[128]; snprintf(filename,sizeof filename,"pattern-%zu.dwybak",n);
    FILE *f=fopen(filename,"wb"); assert(f);
    unsigned char h[80]={0}, aad[92], m[65536], c[65553], decoded[65536], prefix[4];
    crypto_secretstream_xchacha20poly1305_state enc,dec;
    memcpy(h,"DWYBAK\r\n",8); be(h+8,1,2); be(h+10,80,2); be(h+12,1,2); be(h+14,1,2);
    be(h+16,3,4); be(h+20,67108864,8); be(h+28,65536,4); be(h+32,16,2); be(h+34,24,2);
    memcpy(h+40,salt,16); rng_cursor=16;
    assert(crypto_secretstream_xchacha20poly1305_init_push(&enc,h+56,key)==0);
    assert(crypto_secretstream_xchacha20poly1305_init_pull(&dec,h+56,key)==0);
    assert(fwrite(h,1,80,f)==80); memcpy(aad,h,80);
    for(size_t offset=0,index=0;;index++) {
        size_t len=n-offset; if(len>65536)len=65536;
        unsigned char tag=len==65536?crypto_secretstream_xchacha20poly1305_TAG_MESSAGE:crypto_secretstream_xchacha20poly1305_TAG_FINAL;
        for(size_t j=0;j<len;j++)m[j]=(unsigned char)((offset+j)%251);
        unsigned long long clen=0,mlen=0; unsigned char out_tag=255;
        be(aad+80,index,8); be(aad+88,len+17,4);
        assert(crypto_secretstream_xchacha20poly1305_push(&enc,c,&clen,m,len,aad,92,tag)==0);
        assert(clen==len+17); be(prefix,clen,4);
        assert(fwrite(prefix,1,4,f)==4); assert(fwrite(c,1,clen,f)==clen);
        assert(crypto_secretstream_xchacha20poly1305_pull(&dec,decoded,&mlen,&out_tag,c,clen,aad,92)==0);
        assert(mlen==len && out_tag==tag && memcmp(m,decoded,len)==0);
        offset+=len; if(tag==crypto_secretstream_xchacha20poly1305_TAG_FINAL)break;
    }
    assert(fclose(f)==0); sodium_memzero(&enc,sizeof enc); sodium_memzero(&dec,sizeof dec);
    printf("fixture=%s plaintext_bytes=%zu\n",filename,n);
}
int main(void) {
    assert(sodium_init()>=0);
    unsigned char salt[16],key[32]; for(size_t i=0;i<16;i++)salt[i]=(unsigned char)i;
    const unsigned char password[]={0x44,0x61,0x77,0x6e,0x59,0x65,0x77,0x2d,0x74,0x65,0x73,0x74,0x2d,0xe5,0x8f,0xa3,0xe4,0xbb,0xa4,0xf0,0x9f,0x97,0x9d};
    assert(crypto_pwhash(key,32,(const char*)password,sizeof password,salt,3,67108864,crypto_pwhash_ALG_ARGON2ID13)==0);
    printf("libsodium=%s\npassword_utf8_hex=",sodium_version_string());hex(password,sizeof password);
    printf("\nsalt_hex=");hex(salt,16);printf("\nkdf_key_hex=");hex(key,32);printf("\n");
    assert(randombytes_set_implementation(&rng)==0);
    const size_t sizes[]={0,1,65535,65536,65537,131072};
    for(size_t i=0;i<sizeof(sizes)/sizeof(sizes[0]);i++)fixture(sizes[i],key,salt);
    sodium_memzero(key,sizeof key); return 0;
}

package com.dawnyew.platform
import com.dawnyew.core.DomainEnvironment
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
class AndroidEnvironment:DomainEnvironment {
    override fun id()=UUID.randomUUID().toString()
    override fun localNow()=LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"))
    override fun utcNow()=Instant.now().toString()
    override fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFC)
}

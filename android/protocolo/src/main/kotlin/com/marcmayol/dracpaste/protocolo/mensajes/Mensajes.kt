package com.marcmayol.dracpaste.protocolo.mensajes

import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.aHex
import com.marcmayol.dracpaste.protocolo.red.ProtocoloException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Los mensajes del protocolo (`docs/protocol.md` §3, §4 y §5) y su ida y vuelta a JSON.
 *
 * El campo `t` identifica el tipo. Se decodifica en dos pasos —primero se mira `t`,
 * después se decodifica la clase concreta— en vez de usar polimorfismo de
 * kotlinx.serialization, porque el discriminador tiene que ser exactamente `t` en el
 * cable y un tipo desconocido no puede reventar: v1 debe poder ignorar en silencio lo
 * que le mande una versión más nueva.
 */
sealed interface Mensaje {
    val t: String
}

// -------------------------------------------------------------- Emparejamiento

@Serializable
data class PairRequest(
    override val t: String = TIPO,
    val v: Int = Protocolo.VERSION,
    val pk: String,
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val token: String,
) : Mensaje {
    companion object { const val TIPO = "PAIR_REQUEST" }
}

@Serializable
data class PairConfirm(
    override val t: String = TIPO,
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val fingerprint: String,
) : Mensaje {
    companion object { const val TIPO = "PAIR_CONFIRM" }
}

@Serializable
data class PairAck(
    override val t: String = TIPO,
    val fingerprint: String,
) : Mensaje {
    companion object { const val TIPO = "PAIR_ACK" }
}

// ------------------------------------------------------------------ Handshake

@Serializable
data class Hello(
    override val t: String = TIPO,
    val v: Int = Protocolo.VERSION,
    @SerialName("device_id") val deviceId: String,
    val challenge: String,
) : Mensaje {
    companion object { const val TIPO = "HELLO" }
}

@Serializable
data class ServerHello(
    override val t: String = TIPO,
    val v: Int = Protocolo.VERSION,
    @SerialName("device_id") val deviceId: String,
    val challenge: String,
) : Mensaje {
    companion object { const val TIPO = "SERVER_HELLO" }
}

@Serializable
data class Auth(
    override val t: String = TIPO,
    val echo: String,
) : Mensaje {
    companion object { const val TIPO = "AUTH" }
}

@Serializable
data class AuthOk(
    override val t: String = TIPO,
    val echo: String,
) : Mensaje {
    companion object { const val TIPO = "AUTH_OK" }
}

// --------------------------------------------------------------------- Sesión

@Serializable
data class Clip(
    override val t: String = TIPO,
    val type: String = Protocolo.TIPO_TEXTO,
    val payload: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    @SerialName("origin_id") val originId: String,
) : Mensaje {
    companion object {
        const val TIPO = "CLIP"

        /**
         * Construye un CLIP de texto calculando su `origin_id`.
         *
         * @throws ProtocoloException si el texto está vacío o pasa del máximo. Se
         *   comprueba aquí, en el único sitio por el que se crean los clips, y no en
         *   cada llamador.
         */
        fun deTexto(texto: String, timestampMs: Long = System.currentTimeMillis()): Clip {
            val bytes = texto.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) {
                throw ProtocoloException("No se envían clips vacíos")
            }
            if (bytes.size > Protocolo.MAX_CLIP_BYTES) {
                throw ProtocoloException(
                    "El clip ocupa ${bytes.size} bytes y el máximo es ${Protocolo.MAX_CLIP_BYTES}",
                )
            }
            return Clip(
                payload = Base64.getEncoder().encodeToString(bytes),
                timestampMs = timestampMs,
                originId = origenDe(texto),
            )
        }

        /** `origin_id` = SHA-256 del texto en UTF-8, truncado a 16 bytes, en hex. */
        fun origenDe(texto: String): String =
            Cripto.sha256(texto.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16).aHex()
    }

    /** El texto del clip. */
    fun texto(): String = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)

    /** ¿Es un tipo que esta versión sabe manejar? */
    fun esTexto(): Boolean = type == Protocolo.TIPO_TEXTO
}

@Serializable
data class Ping(
    override val t: String = TIPO,
    val seq: Long,
) : Mensaje {
    companion object { const val TIPO = "PING" }
}

@Serializable
data class Pong(
    override val t: String = TIPO,
    val seq: Long,
) : Mensaje {
    companion object { const val TIPO = "PONG" }
}

@Serializable
data class Unpair(override val t: String = TIPO) : Mensaje {
    companion object { const val TIPO = "UNPAIR" }
}

@Serializable
data class Bye(override val t: String = TIPO) : Mensaje {
    companion object { const val TIPO = "BYE" }
}

/** Un tipo que esta versión no conoce. No es un error: se ignora. */
data class MensajeDesconocido(override val t: String) : Mensaje

// -------------------------------------------------------------- Codificación

object CodecMensajes {

    private val json = Json {
        ignoreUnknownKeys = true // Una versión futura puede añadir campos; no es motivo para cortar.
        encodeDefaults = true
        explicitNulls = false
    }

    fun codificar(mensaje: Mensaje): ByteArray {
        val texto = when (mensaje) {
            is PairRequest -> json.encodeToString(PairRequest.serializer(), mensaje)
            is PairConfirm -> json.encodeToString(PairConfirm.serializer(), mensaje)
            is PairAck -> json.encodeToString(PairAck.serializer(), mensaje)
            is Hello -> json.encodeToString(Hello.serializer(), mensaje)
            is ServerHello -> json.encodeToString(ServerHello.serializer(), mensaje)
            is Auth -> json.encodeToString(Auth.serializer(), mensaje)
            is AuthOk -> json.encodeToString(AuthOk.serializer(), mensaje)
            is Clip -> json.encodeToString(Clip.serializer(), mensaje)
            is Ping -> json.encodeToString(Ping.serializer(), mensaje)
            is Pong -> json.encodeToString(Pong.serializer(), mensaje)
            is Unpair -> json.encodeToString(Unpair.serializer(), mensaje)
            is Bye -> json.encodeToString(Bye.serializer(), mensaje)
            is MensajeDesconocido ->
                throw ProtocoloException("No se envía un mensaje de tipo desconocido: ${mensaje.t}")
        }
        return texto.toByteArray(Charsets.UTF_8)
    }

    /**
     * Decodifica un mensaje. Un tipo desconocido devuelve [MensajeDesconocido] en vez de
     * lanzar: quien llama decide ignorarlo, que es lo que manda el protocolo.
     */
    fun decodificar(bytes: ByteArray): Mensaje {
        val objeto = try {
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
                ?: throw ProtocoloException("El mensaje no es un objeto JSON")
        } catch (e: Exception) {
            if (e is ProtocoloException) throw e
            throw ProtocoloException("El mensaje no es JSON válido", e)
        }

        val tipo = objeto["t"]?.jsonPrimitive?.content
            ?: throw ProtocoloException("El mensaje no lleva campo 't'")

        return try {
            when (tipo) {
                PairRequest.TIPO -> json.decodeFromJsonElement(PairRequest.serializer(), objeto)
                PairConfirm.TIPO -> json.decodeFromJsonElement(PairConfirm.serializer(), objeto)
                PairAck.TIPO -> json.decodeFromJsonElement(PairAck.serializer(), objeto)
                Hello.TIPO -> json.decodeFromJsonElement(Hello.serializer(), objeto)
                ServerHello.TIPO -> json.decodeFromJsonElement(ServerHello.serializer(), objeto)
                Auth.TIPO -> json.decodeFromJsonElement(Auth.serializer(), objeto)
                AuthOk.TIPO -> json.decodeFromJsonElement(AuthOk.serializer(), objeto)
                Clip.TIPO -> json.decodeFromJsonElement(Clip.serializer(), objeto)
                Ping.TIPO -> json.decodeFromJsonElement(Ping.serializer(), objeto)
                Pong.TIPO -> json.decodeFromJsonElement(Pong.serializer(), objeto)
                Unpair.TIPO -> json.decodeFromJsonElement(Unpair.serializer(), objeto)
                Bye.TIPO -> json.decodeFromJsonElement(Bye.serializer(), objeto)
                else -> MensajeDesconocido(tipo)
            }
        } catch (e: Exception) {
            throw ProtocoloException("El mensaje '$tipo' no tiene la forma esperada", e)
        }
    }
}

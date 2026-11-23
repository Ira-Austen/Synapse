package com.synapse.agent.parser

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class ManifestComponentInfo(
    val type: String, // "service" or "receiver"
    val className: String,
    val processName: String,
    val exported: Boolean?,
    val enabled: Boolean,
    val actions: List<String>
)

data class ParsedManifestInfo(
    val packageName: String,
    val defaultProcess: String,
    val componentsByProcess: Map<String, List<ManifestComponentInfo>>,
    val allComponents: List<ManifestComponentInfo>
)

/**
 * Pure-Kotlin binary AndroidManifest.xml (AXML) parser reading directly from APK via [ZipFile].
 * Completely avoids `AssetManager.addAssetPath` cookie reflection traps across Android 11-17.
 */
object BinaryAxmlParser {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    fun parseApk(apkPath: String): ParsedManifestInfo {
        val file = File(apkPath)
        require(file.exists()) { "APK not found: $apkPath" }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("AndroidManifest.xml missing in $apkPath")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return parseBytes(bytes)
        }
    }

    fun parseBytes(bytes: ByteArray): ParsedManifestInfo {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8) throw IllegalArgumentException("Truncated AXML payload")

        val rootType = buf.short.toInt() and 0xFFFF
        val rootHeaderSize = buf.short.toInt() and 0xFFFF
        val fileSize = buf.int
        require(rootType == RES_XML_TYPE) { "Not a binary AXML resource (type=0x${rootType.toString(16)})" }

        var offset = rootHeaderSize
        var stringPool: List<String> = emptyList()

        var packageName = ""
        var appProcess: String? = null
        val components = ArrayList<ManifestComponentInfo>()

        var curType: String? = null
        var curName: String? = null
        var curProcess: String? = null
        var curExported: Boolean? = null
        var curEnabled = true
        var curActions = ArrayList<String>()
        var inIntentFilter = false

        val endBound = minOf(fileSize, bytes.size)
        while (offset + 8 <= endBound) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < 8 || offset + chunkSize > endBound) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    stringPool = parseStringPool(bytes, offset, chunkSize)
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    if (offset + 36 <= endBound) {
                        val nameIdx = buf.getInt(offset + 20)
                        val attrStart = buf.getShort(offset + 24).toInt() and 0xFFFF
                        val attrSize = buf.getShort(offset + 26).toInt() and 0xFFFF
                        val attrCount = buf.getShort(offset + 28).toInt() and 0xFFFF
                        val tag = stringPool.getOrNull(nameIdx).orEmpty()

                        val attrs = LinkedHashMap<String, Any?>()
                        // Standard AXML: attribute entries start at offset + headerSize (16) + attrStart (20) = offset + 36
                        val firstAttrPos = offset + headerSize + attrStart
                        for (i in 0 until attrCount) {
                            val pos = firstAttrPos + i * attrSize
                            if (pos + 20 > offset + chunkSize) break
                            val attrNameIdx = buf.getInt(pos + 4)
                            val rawValIdx = buf.getInt(pos + 8)
                            val valueType = buf.get(pos + 15).toInt() and 0xFF
                            val valueData = buf.getInt(pos + 16)
                            val attrName = stringPool.getOrNull(attrNameIdx).orEmpty()
                            val value: Any? = when {
                                valueType == TYPE_STRING -> stringPool.getOrNull(valueData)
                                valueType == TYPE_INT_BOOLEAN -> valueData != 0
                                rawValIdx >= 0 -> stringPool.getOrNull(rawValIdx)
                                else -> valueData
                            }
                            attrs[attrName] = value
                        }

                        when (tag) {
                            "manifest" -> {
                                packageName = (attrs["package"] as? String).orEmpty()
                            }
                            "application" -> {
                                val p = attrs["process"] as? String
                                if (!p.isNullOrBlank()) appProcess = resolveProcess(packageName, p)
                            }
                            "service", "receiver" -> {
                                curType = tag
                                val rawName = (attrs["name"] as? String).orEmpty()
                                curName = resolveClassName(packageName, rawName)
                                val rawProc = attrs["process"] as? String
                                curProcess = if (!rawProc.isNullOrBlank()) {
                                    resolveProcess(packageName, rawProc)
                                } else {
                                    appProcess ?: packageName
                                }
                                curExported = attrs["exported"] as? Boolean
                                curEnabled = (attrs["enabled"] as? Boolean) ?: true
                                curActions = ArrayList()
                            }
                            "intent-filter" -> {
                                if (curType != null) inIntentFilter = true
                            }
                            "action" -> {
                                if (inIntentFilter) {
                                    val act = attrs["name"] as? String
                                    if (!act.isNullOrBlank()) curActions.add(act)
                                }
                            }
                        }
                    }
                }

                RES_XML_END_ELEMENT_TYPE -> {
                    if (offset + 24 <= endBound) {
                        val nameIdx = buf.getInt(offset + 20)
                        val tag = stringPool.getOrNull(nameIdx).orEmpty()
                        when (tag) {
                            "intent-filter" -> inIntentFilter = false
                            "service", "receiver" -> {
                                val type = curType
                                val name = curName
                                if (type == tag && !name.isNullOrBlank()) {
                                    components.add(
                                        ManifestComponentInfo(
                                            type = type,
                                            className = name,
                                            processName = curProcess ?: appProcess ?: packageName,
                                            exported = curExported,
                                            enabled = curEnabled,
                                            actions = curActions.toList()
                                        )
                                    )
                                }
                                curType = null
                                curName = null
                                curProcess = null
                                curExported = null
                                curEnabled = true
                                curActions = ArrayList()
                                inIntentFilter = false
                            }
                        }
                    }
                }
            }
            offset += chunkSize
        }

        val defaultProc = appProcess ?: packageName
        val grouped = components.groupBy { it.processName }
        return ParsedManifestInfo(
            packageName = packageName,
            defaultProcess = defaultProc,
            componentsByProcess = grouped,
            allComponents = components
        )
    }

    private fun parseStringPool(bytes: ByteArray, base: Int, chunkSize: Int): List<String> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = buf.getInt(base + 8)
        val flags = buf.getInt(base + 16)
        val stringsStart = buf.getInt(base + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0

        val result = ArrayList<String>(stringCount.coerceIn(0, 65536))
        val offsetsStart = base + 28
        val dataBase = base + stringsStart
        val limit = base + chunkSize

        for (i in 0 until stringCount) {
            val strOffset = buf.getInt(offsetsStart + i * 4)
            var p = dataBase + strOffset
            if (p < 0 || p >= limit) {
                result.add("")
                continue
            }
            if (isUtf8) {
                // Skip UTF-16 char length (1 or 2 bytes)
                val first = bytes[p++].toInt() and 0xFF
                if ((first and 0x80) != 0) p++
                // Read UTF-8 byte length (1 or 2 bytes)
                var byteLen = bytes[p++].toInt() and 0xFF
                if ((byteLen and 0x80) != 0) {
                    byteLen = ((byteLen and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF)
                }
                val safeLen = byteLen.coerceAtMost((limit - p).coerceAtLeast(0))
                result.add(String(bytes, p, safeLen, Charsets.UTF_8))
            } else {
                var charLen = buf.getShort(p).toInt() and 0xFFFF
                p += 2
                if ((charLen and 0x8000) != 0) {
                    charLen = ((charLen and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
                    p += 2
                }
                val byteLen = (charLen * 2).coerceAtMost((limit - p).coerceAtLeast(0))
                result.add(String(bytes, p, byteLen, Charsets.UTF_16LE))
            }
        }
        return result
    }

    private fun resolveClassName(pkg: String, raw: String): String = when {
        raw.startsWith(".") -> "$pkg$raw"
        !raw.contains(".") && pkg.isNotBlank() -> "$pkg.$raw"
        else -> raw
    }

    private fun resolveProcess(pkg: String, raw: String): String = when {
        raw.startsWith(":") -> "$pkg$raw"
        else -> raw
    }
}

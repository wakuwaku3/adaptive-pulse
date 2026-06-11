package io.github.wakuwaku3.adaptivepulse.spec

import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.full.memberProperties

/**
 * アプリの公開 surface を JSON spec (flat な name → type) として出力する。
 * release workflow が前回 release の spec asset と比較して semver bump を
 * 自動判定する (flame の release policy のアプリ向け適応。判定規則は
 * scripts/semver_bump.sh)。
 *
 * surface の定義: 設定項目 (SessionConfig のフィールド)、振動語彙 (SessionEvent)、
 * フェーズ (Phase)、AndroidManifest (権限・コンポーネント)。設定画面の項目は
 * SessionConfig を鏡映するためここには含めない。
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: spec <AndroidManifest.xml> <output.json>" }
    val entries = sortedMapOf<String, String>()

    SessionConfig::class.memberProperties.forEach { prop ->
        entries["config.${prop.name}"] = prop.returnType.toString()
    }
    SessionEvent::class.sealedSubclasses.forEach { sub ->
        entries["event.${sub.simpleName}"] = "event"
    }
    Phase.entries.forEach { phase ->
        entries["phase.${phase.name}"] = "phase"
    }

    val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(args[0]))
    fun collect(tag: String, kind: String) {
        val nodes = manifest.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val name = nodes.item(i).attributes.getNamedItem("android:name")?.nodeValue ?: continue
            entries["manifest.$tag.$name"] = kind
        }
    }
    collect("uses-permission", "permission")
    collect("uses-feature", "feature")
    collect("activity", "component")
    collect("service", "component")
    collect("receiver", "component")

    val json = entries.entries.joinToString(
        separator = ",\n",
        prefix = "{\n",
        postfix = "\n}\n",
    ) { (k, v) -> "  \"$k\": \"$v\"" }
    File(args[1]).apply { parentFile.mkdirs() }.writeText(json)
    println("spec: ${entries.size} entries -> ${args[1]}")
}

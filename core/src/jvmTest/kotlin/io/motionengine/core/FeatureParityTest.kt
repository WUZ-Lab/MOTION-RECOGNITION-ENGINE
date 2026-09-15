package io.motionengine.core

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class FeatureParityTest {
    @Test fun sharedGoldenCoordinatesAndWindowMatchPythonContract() {
        val file = File(System.getProperty("motion.fixtures"), "pose-features-v1.json")
        val fixture = MotionJson.format.parseToJsonElement(file.readText()).jsonObject
        val extractor = FeatureExtractor()
        val window = TemporalWindow(ModelManifest(sha256 = "0".repeat(64)))
        for (case in fixture.getValue("cases").jsonArray) {
            val item = case.jsonObject
            val pose = MotionJson.format.decodeFromString<PoseFrame>(item.getValue("pose").toString())
            val expected = item.getValue("expectedFeatures").jsonArray.map { it.jsonPrimitive.float }
            val actual = extractor.extract(pose)!!
            expected.forEachIndexed { i,value -> assertEquals(value, actual.values[i], .005f, "t=${pose.timestampMs} channel=$i") }
            window.add(pose.timestampMs, actual)
        }
        val expected = fixture.getValue("expectedWindow").jsonArray.flatMap { row -> row.jsonArray.map { it.jsonPrimitive.float } }
        val actual = window.snapshot().data
        expected.forEachIndexed { i,value -> assertEquals(value, actual[i], .005f, "window offset=$i") }
    }
}

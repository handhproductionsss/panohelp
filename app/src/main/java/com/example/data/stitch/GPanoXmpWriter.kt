package com.example.data.stitch

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Injects standard Google Photo Sphere (`GPano`) XMP metadata into a JPEG's APP1 segment
 * so Android Gallery, Google Photos, and desktop 360° viewers recognize the stitched image
 * as an interactive equirectangular panorama.
 */
object GPanoXmpWriter {

    private const val XMP_NAMESPACE_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    fun buildGPanoXmpPayload(width: Int, height: Int): ByteArray {
        val xml = """
            |<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            |<x:xmpmeta xmlns:x="adobe:ns:meta/">
            |  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |    <rdf:Description rdf:about=""
            |      xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
            |      GPano:UsePanoramaViewer="True"
            |      GPano:CaptureSoftware="PanoGuide"
            |      GPano:StitchingSoftware="PanoGuide On-Device Stitcher"
            |      GPano:ProjectionType="equirectangular"
            |      GPano:FullPanoWidthPixels="$width"
            |      GPano:FullPanoHeightPixels="$height"
            |      GPano:CroppedAreaImageWidthPixels="$width"
            |      GPano:CroppedAreaImageHeightPixels="$height"
            |      GPano:CroppedAreaLeftPixels="0"
            |      GPano:CroppedAreaTopPixels="0"
            |      GPano:PoseHeadingDegrees="0.0"
            |      GPano:InitialViewHeadingDegrees="0"
            |      GPano:InitialViewPitchDegrees="0" />
            |  </rdf:RDF>
            |</x:xmpmeta>
            |<?xpacket end="w"?>
        """.trimMargin()

        val headerBytes = XMP_NAMESPACE_HEADER.toByteArray(Charsets.US_ASCII)
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        return headerBytes + xmlBytes
    }

    fun injectIntoJpeg(jpegFile: File, width: Int, height: Int) {
        val original = FileInputStream(jpegFile).use { it.readBytes() }
        if (original.size < 4 || original[0] != 0xFF.toByte() || original[1] != 0xD8.toByte()) {
            return
        }

        val xmpPayload = buildGPanoXmpPayload(width, height)
        val segmentLength = xmpPayload.size + 2
        if (segmentLength > 65535) return

        val out = ByteArrayOutputStream(original.size + segmentLength + 16)
        // SOI marker FF D8
        out.write(0xFF)
        out.write(0xD8)

        // Write our APP1 XMP segment immediately after SOI
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(xmpPayload)

        // Append rest of JPEG bytes after SOI
        out.write(original, 2, original.size - 2)

        FileOutputStream(jpegFile).use { fos ->
            fos.write(out.toByteArray())
        }
    }
}

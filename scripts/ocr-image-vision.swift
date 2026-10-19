// Optional macOS OCR adapter for the read-only HLS feature experiment.
// Read one PNG from stdin; emit JSON observations in normalized top-left coordinates.
import Foundation
import Vision

func emit(_ value: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data([10]))
}

do {
    let image = FileHandle.standardInput.readDataToEndOfFile()
    guard !image.isEmpty else {
        throw NSError(domain: "FeatureProbeOCR", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Empty image input"])
    }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hant", "zh-Hans", "en-US"]
    request.usesLanguageCorrection = false
    try VNImageRequestHandler(data: image, options: [:]).perform([request])
    let observations: [[String: Any]] = (request.results ?? []).compactMap { observation in
        guard let text = observation.topCandidates(1).first else { return nil }
        // Vision can return a box slightly beyond an image edge. Normalize the
        // geometry without changing recognized text or confidence.
        let original = observation.boundingBox
        let box = original.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        guard !box.isNull && !box.isEmpty else { return nil }
        return ["text": text.string, "confidence": Double(text.confidence),
                "box": [Double(box.minX), Double(1 - box.maxY),
                        Double(box.width), Double(box.height)],
                "box_clipped_to_image": box != original]
    }
    emit(["engine": "Apple Vision accurate; zh-Hant,zh-Hans,en-US",
          "observations": observations])
} catch {
    emit(["engine": "Apple Vision", "observations": [],
          "error": error.localizedDescription])
    exit(1)
}

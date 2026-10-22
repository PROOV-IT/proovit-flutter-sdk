import 'package:proovit_capture/proovit_capture.dart';

/// Minimal SDK flow. [issuePartnerSession] must call the host application's
/// authenticated backend and return a short-lived, server-authorized session.
Future<void> captureOne(SessionProvider issuePartnerSession) async {
  final client = ProovitCaptureClient(
    baseUrl: Uri.parse('https://api.staging.proov-it.online/api/'),
    sessionProvider: issuePartnerSession,
  );
  try {
    final capability = await client.getCapabilities();
    final nativeCapture = capability.values['capture.native_signed'];
    if (nativeCapture is! Map ||
        nativeCapture['enabled'] != true ||
        nativeCapture['available_now'] == false) {
      throw StateError('Signed capture is unavailable for this company');
    }

    await client.enrollDeviceIfNeeded();
    final draft = await client.capturePhoto();
    final receipt = await client.submitCapture(draft);
    if (receipt.captureId != draft.captureId ||
        receipt.sha256.toLowerCase() != draft.sha256.toLowerCase() ||
        receipt.sizeBytes != draft.sizeBytes ||
        !['verified', 'verified_degraded'].contains(receipt.status)) {
      throw StateError('The API receipt does not match the captured media');
    }
  } finally {
    client.dispose();
  }
}

import 'package:flutter/services.dart';

class NativeCapture {
  static const _channel = MethodChannel('io.proovit.capture/methods.v1');

  Future<Map<dynamic, dynamic>> prepareIdentity(String identityScope) async =>
      (await _channel.invokeMapMethod<dynamic, dynamic>('prepareIdentity', {'identityScope': identityScope}))!;

  Future<String> signEnrollment(String identityScope, String nonce) async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>('signEnrollment', {'identityScope': identityScope, 'nonce': nonce});
    return result!['signatureDerBase64Url'] as String;
  }

  Future<Map<dynamic, dynamic>> capturePhoto({required String identityScope, required String companyId, required String actorId, required String deviceKeyId, required String challengeId, required String challengeNonce, required String captureId}) async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>('capturePhoto', {
      'identityScope': identityScope, 'companyId': companyId, 'actorId': actorId,
      'deviceKeyId': deviceKeyId, 'challengeId': challengeId, 'challengeNonce': challengeNonce, 'captureId': captureId,
    });
    if (result?['cancelled'] == true) throw const CaptureCancelled();
    return result!;
  }

  Future<List<Map<dynamic, dynamic>>> listPending(String identityScope) async =>
      (await _channel.invokeListMethod<dynamic>('listPending', {'identityScope': identityScope}) ?? const []).cast<Map<dynamic, dynamic>>();

  Future<void> verifyDraft({required String identityScope, required String captureId, required String sha256, required int sizeBytes, required String payload, required String signature}) async {
    await _channel.invokeMethod<bool>('verifyDraft', {'identityScope': identityScope, 'captureId': captureId, 'sha256': sha256, 'sizeBytes': sizeBytes, 'payload': payload, 'signature': signature});
  }
}

class CaptureCancelled implements Exception {
  const CaptureCancelled();
  @override
  String toString() => 'Capture annulée';
}

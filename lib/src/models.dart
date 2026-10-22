import 'dart:io';

enum TrustClassification {
  localSigned,
  serverReceiptObserved,
  serverReceiptVerifiedFields,
  pendingReconciliation,
  simulated,
}

class SdkSession {
  const SdkSession({required this.accessToken, required this.expiresAt, required this.companyId, required this.actorId, required this.identityScope});
  final String accessToken;
  final DateTime expiresAt;
  final String companyId;
  final String actorId;

  /// Opaque stable scope from the authenticated partner host. Used only to isolate local keys/journals.
  final String identityScope;
}

typedef SessionProvider = Future<SdkSession> Function({required bool forceRefresh});

class Capability {
  const Capability({required this.companyId, required this.mode, required this.policyVersion, required this.values});
  final String companyId;
  final String mode;
  final int policyVersion;
  final Map<String, dynamic> values;
  factory Capability.fromJson(Map<String, dynamic> json) => Capability(
    companyId: json['company_id'] as String,
    mode: json['mode'] as String,
    policyVersion: (json['policy_version'] as num).toInt(),
    values: Map<String, dynamic>.from(json['capabilities'] as Map? ?? const {}),
  );
}

class NativeCaptureDraft {
  const NativeCaptureDraft({required this.captureId, required this.path, required this.sha256, required this.sizeBytes, required this.mimeType, required this.payload, required this.signature, required this.publicKeyDerBase64, required this.trust, this.identityScope});
  final String captureId;
  final String path;
  final String sha256;
  final int sizeBytes;
  final String mimeType;
  final String payload;
  final String signature;
  final String publicKeyDerBase64;
  final TrustClassification trust;
  /// Opaque local journal scope captured when this draft was signed.
  final String? identityScope;
  File get file => File(path);
  factory NativeCaptureDraft.fromMap(Map<dynamic, dynamic> map, {TrustClassification trust = TrustClassification.localSigned, String? identityScope}) => NativeCaptureDraft(
    captureId: map['captureId'] as String,
    path: map['path'] as String,
    sha256: map['sha256'] as String,
    sizeBytes: (map['sizeBytes'] as num).toInt(),
    mimeType: map['mimeType'] as String,
    payload: map['payload'] as String,
    signature: map['signature'] as String,
    publicKeyDerBase64: map['publicKeyDerBase64'] as String,
    trust: trust, identityScope: identityScope,
  );
}

class CaptureReceipt {
  const CaptureReceipt({required this.captureId, required this.companyId, required this.sha256, required this.sizeBytes, required this.mediaMime, required this.status, required this.mediaRetained, required this.raw});
  final String captureId;
  final String companyId;
  final String sha256;
  final int sizeBytes;
  final String mediaMime;
  final String status;
  final bool mediaRetained;
  String? get deviceKeyId => raw['device_key_id'] as String?;
  String? get captureBinding => raw['capture_binding'] as String?;
  String? get challengeResult => raw['challenge_result'] as String?;
  final Map<String, dynamic> raw;
  factory CaptureReceipt.fromJson(Map<String, dynamic> json) => CaptureReceipt(
    captureId: json['capture_id'] as String,
    companyId: json['company_id'] as String,
    sha256: json['sha256'] as String,
    sizeBytes: (json['size_bytes'] as num).toInt(),
    mediaMime: json['media_mime'] as String,
    status: json['verification_status'] as String,
    mediaRetained: json['media_retained'] == true,
    raw: Map<String, dynamic>.from(json),
  );
}

class SdkError implements Exception {
  const SdkError({required this.statusCode, required this.message, required this.code, required this.details});
  final int statusCode;
  final String message;
  final String? code;
  final Map<String, dynamic> details;
  String? get reasonCode => details['reason_code'] as String?;
  String? get policyVersion => details['policy_version']?.toString();
  Object? get costs => details['cost'] ?? details['costs'] ?? details['announced_cost'];
  @override
  String toString() => 'SdkError($statusCode, ${code ?? reasonCode ?? message})';
}

class PendingCapture {
  const PendingCapture({required this.captureId, required this.sha256, required this.sizeBytes, required this.path, required this.payload, required this.signature, required this.state});
  final String captureId;
  final String? sha256;
  final int? sizeBytes;
  final String? path;
  final String? payload;
  final String? signature;
  final String state;
}

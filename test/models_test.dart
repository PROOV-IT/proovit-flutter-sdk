import 'package:flutter_test/flutter_test.dart';
import 'package:proovit_capture/proovit_capture.dart';

void main() {
  test('receipt fields preserve the SDK contract', () {
    final receipt = CaptureReceipt.fromJson({
      'capture_id': '123e4567-e89b-42d3-a456-426614174000',
      'company_id': '123e4567-e89b-42d3-a456-426614174001',
      'sha256': List.filled(64, 'a').join(),
      'size_bytes': 42,
      'media_mime': 'image/jpeg',
      'verification_status': 'verified',
      'media_retained': true,
    });
    expect(receipt.status, 'verified');
    expect(receipt.mediaRetained, isTrue);
    expect(receipt.sizeBytes, 42);
  });

  test('SDK errors retain reason, policy and cost fields', () {
    const error = SdkError(statusCode: 402, message: 'Insufficient tokens', code: 'insufficient_tokens', details: {
      'reason_code': 'capture_cost_denied', 'policy_version': 8, 'announced_cost': {'tokens': 3},
    });
    expect(error.reasonCode, 'capture_cost_denied');
    expect(error.policyVersion, '8');
    expect(error.costs, {'tokens': 3});
  });

  test('trust classes distinguish local, server and simulated results', () {
    expect(TrustClassification.values, contains(TrustClassification.localSigned));
    expect(TrustClassification.values, contains(TrustClassification.serverReceiptVerifiedFields));
    expect(TrustClassification.values, contains(TrustClassification.simulated));
  });
}

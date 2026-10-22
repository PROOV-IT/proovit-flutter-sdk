import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:proovit_capture/proovit_capture.dart';
import 'package:proovit_capture/src/native_capture.dart';

const company = 'company-1';
const actor = 'actor-1';
const scope = 'subject-a-scope';
const otherScope = 'subject-b-scope';
const keyId = '123e4567-e89b-42d3-a456-426614174001';
const serverChallengeId = '123e4567-e89b-42d3-a456-426614174002';
const nonce = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const captureId = '123e4567-e89b-42d3-a456-426614174003';
const mediaHash = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
const mediaBytes = 'abc';

SdkSession session(String token, {String identity = scope, String subject = actor}) => SdkSession(
  accessToken: token, expiresAt: DateTime.now().add(const Duration(hours: 1)), companyId: company, actorId: subject, identityScope: identity,
);

void main() {
  late HttpServer server;
  late Uri baseUrl;
  late Directory temp;
  late List<String> uploads;
  late Map<String, dynamic> receipt;
  late int uploadCount;
  late int challengeCount;
  late String? forcedStatus;
  late bool refreshCapabilities;
  late bool abortFirstUpload;
  late int? uploadFailureStatus;
  late String latestCaptureId;

  setUp(() async {
    uploads = [];
    uploadCount = 0; challengeCount = 0; forcedStatus = null; refreshCapabilities = false; abortFirstUpload = false; uploadFailureStatus = null; latestCaptureId = captureId;
    temp = await Directory.systemTemp.createTemp('proovit-flutter-http-');
    receipt = _validReceipt();
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    baseUrl = Uri.parse('http://${server.address.address}:${server.port}/api/');
    server.listen((request) async {
      final body = await utf8.decoder.bind(request).join();
      final path = request.uri.path;
      if (path == '/api/sdk/v1/capabilities') {
        if (refreshCapabilities && request.headers.value(HttpHeaders.authorizationHeader) == 'Bearer old') { _reply(request.response, 401, {'message': 'expired'}); return; }
        if (forcedStatus != null) { _reply(request.response, 409, {'message': forcedStatus}); return; }
        _reply(request.response, 200, {'data': {'company_id': company, 'mode': 'standard', 'policy_version': 1, 'capabilities': {}}});
      } else if (path == '/api/sdk/v1/capture-security/keys' && request.method == 'GET') {
        _reply(request.response, 200, {'key_id': null});
      } else if (path == '/api/sdk/v1/capture-security/enrollment-challenges') {
        _reply(request.response, 201, {'challenge_id': serverChallengeId, 'nonce': nonce});
      } else if (path == '/api/sdk/v1/capture-security/keys' && request.method == 'POST') {
        final enrollment = jsonDecode(body) as Map<String, dynamic>;
        expect(enrollment['platform'], 'android');
        expect(enrollment['enrollment_challenge_id'], serverChallengeId);
        _reply(request.response, 201, {'key_id': keyId});
      } else if (path == '/api/sdk/v1/capture-security/challenges') {
        challengeCount++;
        final challengeRequest = jsonDecode(body) as Map<String, dynamic>;
        expect(challengeRequest['key_id'], keyId);
        _reply(request.response, 201, {'id': serverChallengeId, 'capture_id': challengeRequest['capture_id'], 'mode': 'online', 'nonce': nonce});
      } else if (path == '/api/sdk/v1/captures' && request.method == 'POST') {
        uploadCount++;
        uploads.add(body);
        latestCaptureId = RegExp(r'"capture_id":"([^"]+)"').firstMatch(body)?.group(1) ?? captureId;
        final payloadText = RegExp(r'name="payload"\r\n\r\n([^\r]+)').firstMatch(body)?.group(1);
        if (payloadText != null) receipt['signed_payload'] = jsonDecode(payloadText);
        if (abortFirstUpload && uploadCount == 1) { (await request.response.detachSocket()).destroy(); return; }
        if (uploadCount == 1 && request.headers.value(HttpHeaders.authorizationHeader) == 'Bearer old') {
          _reply(request.response, 401, {'message': 'expired'}); return;
        }
        if (uploadFailureStatus != null) { _reply(request.response, uploadFailureStatus!, {'message': uploadFailureStatus == 409 ? 'capture_reconciliation_required' : 'scope_denied'}); return; }
        _reply(request.response, 201, {'data': {'capture_id': latestCaptureId}});
      } else if (path == '/api/sdk/v1/captures/$latestCaptureId/receipt') {
        _reply(request.response, 200, {'data': receipt..['capture_id'] = latestCaptureId});
      } else {
        _reply(request.response, 404, {'message': 'not found'});
      }
    });
  });

  tearDown(() async { await server.close(force: true); await temp.delete(recursive: true); });

  test('uses the current API contract, retries same-subject 401 once, and preserves multipart idempotency', () async {
    var refreshes = 0;
    var activeToken = 'old';
    final native = _FakeNative(temp);
    final client = ProovitCaptureClient(baseUrl: baseUrl, nativeCapture: native, sessionProvider: ({required forceRefresh}) async {
      if (forceRefresh) { refreshes++; activeToken = 'fresh'; }
      return session(activeToken);
    });
    final capabilities = await client.getCapabilities();
    expect(capabilities.companyId, company);
    await client.enrollDeviceIfNeeded();
    final draft = await client.capturePhoto();
    final result = await client.submitCapture(draft);
    expect(result.captureId, draft.captureId);
    expect(refreshes, 1);
    expect(challengeCount, 1);
    expect(uploadCount, 2);
    expect(uploads.first, contains('client_operation_id'));
    expect(uploads.first, contains('flutter-${draft.captureId}'));
    expect(uploads.last, contains('flutter-${draft.captureId}'));
    expect(uploads.first, contains('name="payload"'));
    expect(uploads.first, contains('name="signature"'));
    expect(uploads.first, contains('name="media"; filename="${draft.captureId}.jpg"'));
    expect(native.verifyCount, 1);
    client.dispose();
  });

  test('normalizes only the known message-only 409 error code', () async {
    forcedStatus = 'capture_reconciliation_required';
    final client = ProovitCaptureClient(baseUrl: baseUrl, sessionProvider: ({required forceRefresh}) async => session('token'));
    try {
      await client.getCapabilities();
      fail('expected reconciliation error');
    } on SdkError catch (error) {
      expect(error.statusCode, 409);
      expect(error.message, 'capture_reconciliation_required');
      expect(error.code, 'capture_reconciliation_required');
    }
    forcedStatus = 'capture_collision';
    try {
      await client.getCapabilities();
      fail('expected collision error');
    } on SdkError catch (error) { expect(error.code, isNull); }
    client.dispose();
  });

  test('does not reuse signed media after a subject switch during 401 retry', () async {
    var refresh = 0;
    var active = 'old';
    final native = _FakeNative(temp);
    final client = ProovitCaptureClient(baseUrl: baseUrl, nativeCapture: native, sessionProvider: ({required forceRefresh}) async {
      if (forceRefresh) { refresh++; active = 'other'; }
      return active == 'other' ? session('other', identity: otherScope, subject: 'other-actor') : session('old');
    });
    final draft = await native.draft(identityScope: scope);
    await expectLater(client.submitCapture(draft), throwsA(isA<FormatException>()));
    expect(refresh, 1);
    expect(uploadCount, 1);
    client.dispose();
  });

  test('does not retry signed media after tenant or client-scope replacement', () async {
    for (final replacement in <SdkSession Function()>[
      () => session('new', identity: otherScope),
      () => SdkSession(accessToken: 'new', expiresAt: DateTime.now().add(const Duration(hours: 1)), companyId: 'company-2', actorId: actor, identityScope: scope),
    ]) {
      uploadCount = 0; uploads.clear();
      final native = _FakeNative(temp);
      var active = 'old';
      final client = ProovitCaptureClient(baseUrl: baseUrl, nativeCapture: native, sessionProvider: ({required forceRefresh}) async {
        if (forceRefresh) active = 'new';
        return active == 'new' ? replacement() : session('old');
      });
      final draft = await native.draft(identityScope: scope);
      await expectLater(client.submitCapture(draft), throwsA(isA<FormatException>()));
      expect(active, 'new');
      expect(uploadCount, 1);
      client.dispose();
    }
  });

  test('keeps the same idempotency key when retrying a transport-aborted upload', () async {
    abortFirstUpload = true;
    final client = ProovitCaptureClient(baseUrl: baseUrl, sessionProvider: ({required forceRefresh}) async => session('token'), nativeCapture: _FakeNative(temp));
    final draft = await _FakeNative(temp).draft(identityScope: scope);
    await expectLater(client.submitCapture(draft), throwsA(anything));
    expect(uploadCount, 1);
    await client.submitCapture(draft);
    expect(uploadCount, 2);
    expect(uploads[0], contains('flutter-$captureId'));
    expect(uploads[1], contains('flutter-$captureId'));
    client.dispose();
  });

  test('surfaces 403 and message-only reconciliation 409 without replaying an upload', () async {
    final client = ProovitCaptureClient(baseUrl: baseUrl, sessionProvider: ({required forceRefresh}) async => session('token'), nativeCapture: _FakeNative(temp));
    var draft = await _FakeNative(temp).draft(identityScope: scope);
    uploadFailureStatus = 403;
    await expectLater(client.submitCapture(draft), throwsA(isA<SdkError>().having((error) => error.statusCode, 'status', 403)));
    expect(uploadCount, 1);
    uploadCount = 0; uploads.clear(); uploadFailureStatus = 409;
    draft = await _FakeNative(temp).draft(identityScope: scope);
    await expectLater(client.submitCapture(draft), throwsA(isA<SdkError>().having((error) => error.code, 'code', 'capture_reconciliation_required')));
    expect(uploadCount, 1);
    client.dispose();
  });

  test('fails capture closed when capability refresh changes the session subject', () async {
    var refresh = 0;
    var active = 'old';
    refreshCapabilities = true;
    final native = _FakeNative(temp);
    final client = ProovitCaptureClient(baseUrl: baseUrl, nativeCapture: native, sessionProvider: ({required forceRefresh}) async {
      if (forceRefresh) { refresh++; active = 'other'; }
      return active == 'other' ? session('other', identity: otherScope, subject: 'other-actor') : session('old');
    });
    await expectLater(client.capturePhoto(), throwsA(isA<FormatException>()));
    expect(refresh, 1);
    expect(challengeCount, 0);
    expect(native.captureCount, 0);
    client.dispose();
  });

  test('rejects receipt device, MIME, challenge and verification mismatches', () async {
    final client = ProovitCaptureClient(baseUrl: baseUrl, sessionProvider: ({required forceRefresh}) async => session('token'));
    await client.getCapabilities();
    final draft = await _FakeNative(temp).draft(identityScope: scope);
    for (final mutate in <void Function()>[
      () => receipt['device_key_id'] = 'wrong-key',
      () => receipt['media_mime'] = 'image/png',
      () => receipt['challenge_result'] = 'invalid',
      () => (receipt['signed_payload'] as Map<String, dynamic>)['challenge_id'] = 'wrong-challenge',
      () => (receipt['verification'] as Map<String, dynamic>)['signature_valid'] = false,
      () => (receipt['verification'] as Map<String, dynamic>)['media_valid'] = false,
      () => (receipt['verification'] as Map<String, dynamic>)['challenge_valid'] = false,
      () => receipt.remove('capture_binding'),
      () => receipt.remove('verification'),
      () => receipt['media_retained'] = false,
    ]) {
      receipt = _validReceipt();
      mutate();
      await expectLater(client.verifyReceipt(captureId, draft), throwsA(isA<FormatException>()));
    }
    receipt = _validReceipt()..['verification_status'] = 'verified_degraded';
    (receipt['verification'] as Map<String, dynamic>)['verification_status'] = 'verified_degraded';
    expect((await client.verifyReceipt(captureId, draft)).status, 'verified_degraded');
    client.dispose();
  });

  test('rejects same-length media tampering before upload', () async {
    final native = _FakeNative(temp);
    final client = ProovitCaptureClient(baseUrl: baseUrl, sessionProvider: ({required forceRefresh}) async => session('token'), nativeCapture: native);
    final draft = await native.draft(identityScope: scope);
    await draft.file.writeAsString('xyz', flush: true);
    await expectLater(client.submitCapture(draft), throwsA(isA<FormatException>()));
    expect(uploadCount, 0);
    client.dispose();
  });
}

void _reply(HttpResponse response, int status, Map<String, Object?> value) {
  response.statusCode = status;
  response.headers.contentType = ContentType.json;
  response.write(jsonEncode(value));
  response.close();
}

Map<String, dynamic> _validReceipt() => {
  'capture_id': captureId, 'company_id': company, 'sha256': mediaHash, 'size_bytes': 3, 'media_mime': 'image/jpeg',
  'verification_status': 'verified', 'media_retained': true, 'device_key_id': keyId, 'capture_binding': 'valid', 'challenge_result': 'online_redeemed',
  'signed_payload': {'schema': 'proovit.capture.v1', 'capture_id': captureId, 'company_id': company, 'user_id': actor, 'device_key_id': keyId,
    'challenge_id': serverChallengeId, 'challenge_mode': 'online', 'media_sha256': mediaHash, 'media_size_bytes': '3', 'media_mime': 'image/jpeg'},
  'verification': {'signature_valid': true, 'media_valid': true, 'challenge_valid': true, 'verification_status': 'verified', 'media_sha256': mediaHash, 'media_size_bytes': 3},
};

class _FakeNative extends NativeCapture {
  _FakeNative(this.temp);
  final Directory temp;
  int verifyCount = 0;
  int captureCount = 0;
  @override
  Future<Map<dynamic, dynamic>> prepareIdentity(String identityScope) async => {'installationId': 'install-$identityScope', 'publicKeyDerBase64': 'ZmFrZQ=='};
  @override
  Future<String> signEnrollment(String identityScope, String nonce) async => 'signature';
  @override
  Future<Map<dynamic, dynamic>> capturePhoto({required String identityScope, required String companyId, required String actorId, required String deviceKeyId, required String challengeId, required String challengeNonce, required String captureId}) async {
    captureCount++;
    return await _draft(identityScope: identityScope, id: captureId, companyId: companyId, actorId: actorId, deviceKeyId: deviceKeyId, challengeId: challengeId).then((draft) => {
      'captureId': draft.captureId, 'path': draft.path, 'sha256': draft.sha256, 'sizeBytes': draft.sizeBytes, 'mimeType': draft.mimeType,
      'payload': draft.payload, 'signature': draft.signature, 'publicKeyDerBase64': draft.publicKeyDerBase64,
      'localSignatureVerified': true, 'trust': 'native_signed',
    });
  }
  Future<NativeCaptureDraft> draft({required String identityScope}) async {
    return _draft(identityScope: identityScope);
  }
  Future<NativeCaptureDraft> _draft({required String identityScope, String id = captureId, String companyId = company, String actorId = actor, String deviceKeyId = keyId, String challengeId = serverChallengeId}) async {
    final file = File('${temp.path}/$id.jpg');
    await file.writeAsString(mediaBytes, flush: true);
    final payload = jsonEncode({'schema': 'proovit.capture.v1', 'capture_id': id, 'company_id': companyId, 'user_id': actorId,
      'device_key_id': deviceKeyId, 'challenge_id': challengeId, 'challenge_mode': 'online', 'media_sha256': mediaHash,
      'media_size_bytes': '3', 'media_mime': 'image/jpeg'});
    return NativeCaptureDraft.fromMap({'captureId': id, 'path': file.path, 'sha256': mediaHash, 'sizeBytes': 3, 'mimeType': 'image/jpeg', 'payload': payload,
      'signature': 'fake-signature', 'publicKeyDerBase64': 'ZmFrZQ==', 'localSignatureVerified': true, 'trust': 'native_signed'}, identityScope: identityScope);
  }
  @override
  Future<void> verifyDraft({required String identityScope, required String captureId, required String sha256, required int sizeBytes, required String payload, required String signature}) async {
    verifyCount++;
    if (await File('${temp.path}/$captureId.jpg').readAsString() != mediaBytes) throw const FormatException('tampered media');
  }
}

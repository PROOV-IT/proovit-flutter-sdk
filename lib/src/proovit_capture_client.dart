import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'models.dart';
import 'native_capture.dart';

class ProovitCaptureClient {
  ProovitCaptureClient({required Uri baseUrl, required this.sessionProvider, HttpClient? httpClient, NativeCapture? nativeCapture, this.logger})
      : baseUrl = baseUrl.toString().endsWith('/') ? baseUrl : baseUrl.replace(path: '${baseUrl.path}/'),
        _http = httpClient ?? HttpClient(), _ownsHttp = httpClient == null, _native = nativeCapture ?? NativeCapture();

  final Uri baseUrl;
  final SessionProvider sessionProvider;
  final HttpClient _http;
  final bool _ownsHttp;
  final NativeCapture _native;
  final void Function(String message)? logger;
  SdkSession? _session;
  Capability? _capability;
  String? _deviceKeyId;
  String? _sessionScope;
  bool _disposed = false;

  /// Releases the HTTP connection pool owned by this SDK client.
  void dispose() { if (_disposed) return; _disposed = true; if (_ownsHttp) _http.close(force: true); }

  Future<Capability> getCapabilities() async {
    final response = await _json('GET', '/sdk/v1/capabilities');
    final data = _object(response['data']);
    final value = Capability.fromJson(data);
    final session = await _currentSession();
    if (value.companyId != session.companyId) throw const FormatException('La société des capacités diffère de la session partenaire.');
    _capability = value;
    return value;
  }

  Future<void> enrollDeviceIfNeeded() async {
    final session = await _currentSession();
    await _enrollDeviceIfNeeded(session, _identityOf(session));
  }

  Future<void> _enrollDeviceIfNeeded(SdkSession session, _SessionIdentity expected) async {
    session = await _revalidateIdentity(expected);
    final identity = await _native.prepareIdentity(session.identityScope);
    session = await _revalidateIdentity(expected);
    final installationId = identity['installationId'] as String;
    final existing = await _json('GET', '/sdk/v1/capture-security/keys?installation_id=${Uri.encodeQueryComponent(installationId)}', expected: expected);
    session = await _revalidateIdentity(expected);
    final current = _object(existing['data'] ?? existing);
    if (current['key_id'] is String) { _deviceKeyId = current['key_id'] as String; return; }
    final challengeResponse = await _json('POST', '/sdk/v1/capture-security/enrollment-challenges', body: const {}, expected: expected);
    session = await _revalidateIdentity(expected);
    final challenge = _object(challengeResponse['data'] ?? challengeResponse);
    final nonce = challenge['nonce'] as String;
    final signature = await _native.signEnrollment(session.identityScope, nonce);
    session = await _revalidateIdentity(expected);
    final enrolledResponse = await _json('POST', '/sdk/v1/capture-security/keys', body: {
      'installation_id': installationId,
      'platform': 'android',
      'enrollment_challenge_id': challenge['challenge_id'],
      'enrollment_nonce': nonce,
      'public_key_der_base64': identity['publicKeyDerBase64'],
      'enrollment_signature_der_base64url': signature,
    }, expected: expected);
    await _revalidateIdentity(expected);
    _deviceKeyId = _object(enrolledResponse['data'] ?? enrolledResponse)['key_id'] as String;
  }

  Future<NativeCaptureDraft> capturePhoto({Map<String, Object?> context = const {}}) async {
    var session = await _currentSession();
    final expected = _identityOf(session);
    await getCapabilities();
    session = await _revalidateIdentity(expected);
    if (_deviceKeyId == null) await _enrollDeviceIfNeeded(session, expected);
    session = await _revalidateIdentity(expected);
    final captureId = _uuidV4();
    final challengeResponse = await _json('POST', '/sdk/v1/capture-security/challenges', body: {'key_id': _deviceKeyId, 'capture_id': captureId}, expected: expected);
    session = await _revalidateIdentity(expected);
    final challenge = _object(challengeResponse['data'] ?? challengeResponse);
    if (challenge['capture_id'] != captureId || challenge['mode'] != 'online') throw const FormatException('Le challenge serveur ne correspond pas à cette capture.');
    final raw = await _native.capturePhoto(
      identityScope: session.identityScope,
      companyId: session.companyId,
      actorId: session.actorId,
      deviceKeyId: _deviceKeyId!,
      challengeId: challenge['id'] as String,
      challengeNonce: challenge['nonce'] as String,
      captureId: captureId,
    );
    await _revalidateIdentity(expected);
    if (raw['localSignatureVerified'] != true || raw['trust'] != 'native_signed') throw const FormatException('La capture native signée n’a pas passé la vérification locale.');
    final draft = NativeCaptureDraft.fromMap(raw, identityScope: expected.identityScope);
    final payload = _object(jsonDecode(draft.payload));
    if (payload['company_id']?.toString() != expected.companyId || payload['user_id']?.toString() != expected.actorId ||
        payload['device_key_id']?.toString() != _deviceKeyId || payload['challenge_id']?.toString() != challenge['id']?.toString()) {
      throw const FormatException('Le brouillon signé ne correspond pas à la session et au challenge capturés.');
    }
    return draft;
  }

  /// Real on-device CameraX/P-256 fixture capture with synthetic identity/challenge; never uploads.
  /// Intended only for a local demo when no SDK backend is configured.
  Future<NativeCaptureDraft> captureFixturePhoto() async {
    final scope = 'g2a-flutter-local-fixture';
    await _native.prepareIdentity(scope);
    final nonceBytes = List<int>.generate(32, (_) => Random.secure().nextInt(256));
    final nonce = base64Url.encode(nonceBytes).replaceAll('=', '');
    final raw = await _native.capturePhoto(identityScope: scope, companyId: 'synthetic-company', actorId: 'synthetic-service-actor',
      deviceKeyId: _uuidV4(), challengeId: _uuidV4(), challengeNonce: nonce, captureId: _uuidV4());
    if (raw['localSignatureVerified'] != true) throw const FormatException('La signature locale du fixture est invalide.');
    return NativeCaptureDraft.fromMap(raw, trust: TrustClassification.simulated);
  }

  Future<CaptureReceipt> submitCapture(NativeCaptureDraft draft, {void Function(int sent, int total)? onProgress}) async {
    if (draft.trust != TrustClassification.localSigned) throw const FormatException('Seule une capture native signée peut être envoyée.');
    final file = draft.file;
    if (!await file.exists() || await file.length() != draft.sizeBytes || draft.sizeBytes < 1 || draft.sizeBytes > 100 * 1024 * 1024) {
      throw const FormatException('Le média local ne correspond plus à la capture signée.');
    }
    var session = await _currentSession();
    final expected = _identityOf(session);
    if (draft.identityScope != null && draft.identityScope != expected.identityScope) throw const FormatException('Le brouillon appartient à un autre scope de session.');
    final envelope = _object(jsonDecode(draft.payload));
    if (envelope['company_id']?.toString() != expected.companyId || envelope['user_id']?.toString() != expected.actorId) {
      throw const FormatException('Le brouillon signé appartient à un autre sujet ou tenant.');
    }
    if (envelope['capture_id']?.toString() != draft.captureId || envelope['media_sha256']?.toString().toLowerCase() != draft.sha256.toLowerCase() ||
        int.tryParse(envelope['media_size_bytes']?.toString() ?? '') != draft.sizeBytes || envelope['media_mime']?.toString() != draft.mimeType) {
      throw const FormatException('Les champs média du brouillon ne correspondent pas aux octets locaux.');
    }
    session = await _revalidateIdentity(expected);
    await _native.verifyDraft(identityScope: session.identityScope, captureId: draft.captureId, sha256: draft.sha256,
      sizeBytes: draft.sizeBytes, payload: draft.payload, signature: draft.signature);
    await _revalidateIdentity(expected);
    try {
      await _multipartCapture(draft, expected, onProgress);
    } on SdkError catch (error) {
      if (error.statusCode == 409 && (error.code == 'capture_reconciliation_required' || error.reasonCode == 'capture_reconciliation_required')) {
        throw SdkError(statusCode: 409, message: 'Réconciliation nécessaire; le brouillon signé et le média sont conservés.', code: 'capture_reconciliation_required', details: error.details);
      }
      rethrow;
    }
    await _revalidateIdentity(expected);
    final receipt = await verifyReceipt(draft.captureId, draft);
    await _revalidateIdentity(expected);
    return receipt;
  }

  Future<CaptureReceipt> getReceipt(String captureId) async {
    final response = await _json('GET', '/sdk/v1/captures/${Uri.encodeComponent(captureId)}/receipt');
    return CaptureReceipt.fromJson(_object(response['data']));
  }

  Future<CaptureReceipt> verifyReceipt(String captureId, NativeCaptureDraft localDraft) async {
    if (localDraft.trust != TrustClassification.localSigned) throw const FormatException('Le reçu ne peut être lié qu’à un brouillon natif signé avec challenge serveur.');
    final receipt = await getReceipt(captureId);
    final capability = _capability ?? await getCapabilities();
    final payload = _object(jsonDecode(localDraft.payload));
    final receiptPayload = _object(receipt.raw['signed_payload']);
    final verification = _object(receipt.raw['verification']);
    const bindingFields = ['schema', 'capture_id', 'company_id', 'user_id', 'device_key_id', 'challenge_id', 'challenge_mode',
      'challenge_nonce_sha256', 'media_sha256', 'media_size_bytes', 'media_mime'];
    final signedPayloadMatches = bindingFields.every((field) => receiptPayload[field]?.toString() == payload[field]?.toString());
    final challengeValid = (payload['challenge_mode'] == 'online' && receipt.challengeResult == 'online_redeemed') ||
        (payload['challenge_mode'] == 'offline_lease' && receipt.challengeResult == 'offline_lease_redeemed');
    if (receipt.status != 'verified' && receipt.status != 'verified_degraded') {
      throw FormatException('Statut de vérification du reçu non accepté : ${receipt.status}.');
    }
    if (!receipt.mediaRetained) {
      throw FormatException('Le média de la capture n’est pas retenu; reçu ${receipt.status} refusé pour cette reprise.');
    }
    if (receipt.captureId != localDraft.captureId || receipt.captureId != captureId || receipt.companyId != capability.companyId ||
        receipt.sha256.toLowerCase() != localDraft.sha256.toLowerCase() || receipt.sizeBytes != localDraft.sizeBytes ||
        receipt.mediaMime != localDraft.mimeType || receipt.deviceKeyId != payload['device_key_id']?.toString() ||
        !signedPayloadMatches || receipt.captureBinding != 'valid' || !challengeValid ||
        verification['signature_valid'] != true || verification['media_valid'] != true ||
        verification['challenge_valid'] != true || verification['verification_status'] != receipt.status ||
        verification['media_sha256']?.toString().toLowerCase() != localDraft.sha256.toLowerCase() ||
        (verification['media_size_bytes'] as num?)?.toInt() != localDraft.sizeBytes) {
      throw const FormatException('Les champs du reçu ne correspondent pas aux octets capturés.');
    }
    return receipt;
  }

  Future<List<PendingCapture>> listPending() async {
    final session = await _currentSession();
    final values = await _native.listPending(session.identityScope);
    return values.map((item) => PendingCapture(
      captureId: item['captureId'] as String, sha256: item['sha256'] as String?,
      sizeBytes: (item['sizeBytes'] as num?)?.toInt(), path: item['path'] as String?,
      payload: item['payload'] as String?, signature: item['signature'] as String?, state: item['state'] as String,
    )).toList(growable: false);
  }

  Future<List<CaptureReceipt>> resumePending({void Function(int sent, int total)? onProgress}) async {
    final receipts = <CaptureReceipt>[];
    for (final item in await listPending()) {
      if (item.state != 'RECOVERABLE' || item.payload == null || item.signature == null || item.path == null || item.sha256 == null || item.sizeBytes == null) continue;
      final decoded = _object(jsonDecode(item.payload!));
      final draft = NativeCaptureDraft(captureId: item.captureId, path: item.path!, sha256: item.sha256!, sizeBytes: item.sizeBytes!,
        mimeType: decoded['media_mime'] as String, payload: item.payload!, signature: item.signature!, publicKeyDerBase64: '', trust: TrustClassification.localSigned);
      receipts.add(await submitCapture(draft, onProgress: onProgress));
    }
    return receipts;
  }

  Future<Map<String, dynamic>> _json(String method, String path, {Map<String, Object?>? body, _SessionIdentity? expected}) async {
    var session = await _currentSession();
    if (expected != null) session = await _revalidateIdentity(expected);
    var response = await _requestJson(method, path, session, body);
    if (expected != null) await _revalidateIdentity(expected);
    if (response.statusCode == 401 && method == 'GET') {
      session = await sessionProvider(forceRefresh: true);
      _rememberSession(session);
      if (expected != null) _assertIdentity(expected);
      response = await _requestJson(method, path, session, body);
      if (expected != null) await _revalidateIdentity(expected);
    }
    final decoded = _decodeBody(response.bodyBytes);
    if (response.statusCode < 200 || response.statusCode >= 300) throw _sdkError(response.statusCode, decoded);
    return decoded;
  }

  Future<_Response> _requestJson(String method, String path, SdkSession session, Map<String, Object?>? body) async {
    final request = await _http.openUrl(method, _uri(path));
    request.followRedirects = false;
    request.headers.set(HttpHeaders.authorizationHeader, 'Bearer ${session.accessToken}');
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    request.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
    if (body != null) { request.headers.contentType = ContentType.json; request.write(jsonEncode(body)); }
    final response = await request.close();
    return _Response(response.statusCode, await response.fold<List<int>>(<int>[], (all, chunk) => all..addAll(chunk)));
  }

  Future<void> _multipartCapture(NativeCaptureDraft draft, _SessionIdentity expected, void Function(int, int)? progress) async {
    final boundary = 'proovit-${_uuidV4().replaceAll('-', '')}';
    Future<HttpClientResponse> send(SdkSession session) async {
      final media = draft.file;
      final request = await _http.postUrl(_uri('/sdk/v1/captures'));
      request.followRedirects = false;
      request.headers.set(HttpHeaders.authorizationHeader, 'Bearer ${session.accessToken}');
      request.headers.set(HttpHeaders.contentTypeHeader, 'multipart/form-data; boundary=$boundary');
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      final fields = <String, String>{'client_operation_id': 'flutter-${draft.captureId}', 'payload': draft.payload, 'signature': draft.signature};
      var length = 0;
      for (final entry in fields.entries) {
        length += utf8.encode('--$boundary\r\nContent-Disposition: form-data; name="${entry.key}"\r\n\r\n${entry.value}\r\n').length;
      }
      final fileHeader = '--$boundary\r\nContent-Disposition: form-data; name="media"; filename="${draft.captureId}.jpg"\r\nContent-Type: ${draft.mimeType}\r\n\r\n';
      length += utf8.encode(fileHeader).length + draft.sizeBytes + utf8.encode('\r\n--$boundary--\r\n').length;
      request.contentLength = length;
      for (final entry in fields.entries) request.add(utf8.encode('--$boundary\r\nContent-Disposition: form-data; name="${entry.key}"\r\n\r\n${entry.value}\r\n'));
      request.add(utf8.encode(fileHeader));
      var sent = 0;
      final mediaStream = media.openRead().transform(StreamTransformer<List<int>, List<int>>.fromHandlers(handleData: (chunk, sink) {
        sent += chunk.length; progress?.call(sent, draft.sizeBytes); sink.add(chunk);
      }));
      await request.addStream(mediaStream);
      request.add(utf8.encode('\r\n--$boundary--\r\n'));
      return request.close();
    }
    var session = await _revalidateIdentity(expected);
    var response = await send(session);
    var bytes = await response.fold<List<int>>(<int>[], (all, chunk) => all..addAll(chunk));
    await _revalidateIdentity(expected);
    if (response.statusCode == 401) {
      final refreshed = await sessionProvider(forceRefresh: true); _rememberSession(refreshed);
      _assertIdentity(expected);
      session = await _revalidateIdentity(expected);
      response = await send(session);
      bytes = await response.fold<List<int>>(<int>[], (all, chunk) => all..addAll(chunk));
      await _revalidateIdentity(expected);
    }
    final decoded = _decodeBody(bytes);
    if (response.statusCode < 200 || response.statusCode >= 300) throw _sdkError(response.statusCode, decoded);
  }

  Future<SdkSession> _currentSession() async {
    if (_disposed) throw StateError('ProovitCaptureClient has been disposed.');
    var session = _session;
    if (session == null || !session.expiresAt.isAfter(DateTime.now().add(const Duration(seconds: 10)))) {
      session = await sessionProvider(forceRefresh: session != null); _rememberSession(session);
    }
    if (!session.expiresAt.isAfter(DateTime.now()) || session.accessToken.isEmpty || session.identityScope.isEmpty) throw const FormatException('La session partenaire est expirée ou mal formée.');
    return session;
  }

  Future<SdkSession> _revalidateIdentity(_SessionIdentity expected) async {
    final refreshed = await sessionProvider(forceRefresh: false);
    _rememberSession(refreshed);
    _assertIdentity(expected);
    if (!refreshed.expiresAt.isAfter(DateTime.now()) || refreshed.accessToken.isEmpty) throw const FormatException('La session partenaire a expiré pendant la capture.');
    return refreshed;
  }

  Uri _uri(String path) => baseUrl.resolve(path.startsWith('/') ? path.substring(1) : path);
  void _rememberSession(SdkSession session) {
    final scope = '${session.companyId}\n${session.actorId}\n${session.identityScope}';
    if (_sessionScope != null && _sessionScope != scope) { _deviceKeyId = null; _capability = null; }
    _sessionScope = scope;
    _session = session;
  }
  _SessionIdentity _identityOf(SdkSession session) => _SessionIdentity(session.companyId, session.actorId, session.identityScope);
  void _assertIdentity(_SessionIdentity expected) {
    final current = _session;
    if (current != null && _identityOf(current) != expected) throw const FormatException('La session, le sujet ou le tenant a changé pendant la capture. Recommencez avec une nouvelle capture.');
  }
  Map<String, dynamic> _decodeBody(List<int> bytes) {
    if (bytes.isEmpty) return <String, dynamic>{};
    try { return _object(jsonDecode(utf8.decode(bytes))); } catch (_) { return <String, dynamic>{'message': utf8.decode(bytes, allowMalformed: true)}; }
  }
  Map<String, dynamic> _object(Object? value) => value is Map<String, dynamic> ? value : value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};
  SdkError _sdkError(int status, Map<String, dynamic> body) {
    final details = <String, dynamic>{...body, ..._object(body['error'])};
    final message = (details['message'] ?? body['message'] ?? 'Erreur SDK HTTP $status').toString();
    final candidate = details['code'] ?? body['code'] ?? details['reason_code'] ?? body['reason_code'] ?? message;
    const knownMessageCodes = {'capture_reconciliation_required', 'capture_receipt_binding_invalid', 'capture_receipt_bytes_unavailable_or_changed', 'capture_signing_key_unavailable', 'capture_receipt_signature_invalid', 'capture_receipt_challenge_invalid', 'scope_denied'};
    final code = details['code']?.toString() ?? body['code']?.toString() ?? details['reason_code']?.toString() ?? body['reason_code']?.toString() ?? (knownMessageCodes.contains(candidate) ? candidate.toString() : null);
    return SdkError(statusCode: status, message: message, code: code, details: details);
  }
  String _uuidV4() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes.map((e) => e.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }
}

class _SessionIdentity {
  const _SessionIdentity(this.companyId, this.actorId, this.identityScope);
  final String companyId;
  final String actorId;
  final String identityScope;
  @override
  bool operator ==(Object other) => other is _SessionIdentity && companyId == other.companyId && actorId == other.actorId && identityScope == other.identityScope;
  @override
  int get hashCode => Object.hash(companyId, actorId, identityScope);
}

class _Response {
  const _Response(this.statusCode, this.bodyBytes);
  final int statusCode;
  final List<int> bodyBytes;
}

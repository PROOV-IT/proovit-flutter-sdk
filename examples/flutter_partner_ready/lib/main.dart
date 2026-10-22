import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:proovit_capture/proovit_capture.dart';

void main() => runApp(const PartnerReadyApp());

class PartnerReadyApp extends StatelessWidget {
  const PartnerReadyApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'PROOV-IT Capture',
        theme: ThemeData(colorSchemeSeed: const Color(0xff1763b5), useMaterial3: true),
        home: const PartnerCapturePage(),
      );
}

class PartnerCapturePage extends StatefulWidget {
  const PartnerCapturePage({super.key});

  @override
  State<PartnerCapturePage> createState() => _PartnerCapturePageState();
}

class _PartnerCapturePageState extends State<PartnerCapturePage> {
  final _api = TextEditingController(text: 'https://api.staging.proov-it.online/api/');
  final _partnerEndpoint = TextEditingController(text: 'https://partner.example.com/partner/proov-it/session');
  final _hostBearer = TextEditingController();
  final _http = HttpClient();
  ProovitCaptureClient? _client;
  SdkSession? _session;
  Capability? _capability;
  NativeCaptureDraft? _draft;
  CaptureReceipt? _receipt;
  String _message = 'Connectez-vous à votre backend partenaire pour commencer.';
  bool _busy = false;
  double? _uploadProgress;

  @override
  void dispose() {
    _client?.dispose();
    _http.close(force: true);
    _api.dispose();
    _partnerEndpoint.dispose();
    _hostBearer.dispose();
    super.dispose();
  }

  Future<SdkSession> _issueSession({required bool forceRefresh}) async {
    final request = await _http.postUrl(Uri.parse(_partnerEndpoint.text.trim()));
    request.followRedirects = false;
    request.headers.set(HttpHeaders.authorizationHeader, 'Bearer ${_hostBearer.text.trim()}');
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    request.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
    request.write('{}');
    final response = await request.close();
    final body = jsonDecode(await response.transform(utf8.decoder).join()) as Map<String, dynamic>;
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw HttpException('Backend partenaire HTTP ${response.statusCode}: ${body['error'] ?? body['message'] ?? 'session_refused'}');
    }
    final data = body['data'] is Map<String, dynamic> ? body['data'] as Map<String, dynamic> : body;
    return SdkSession(
      accessToken: data['access_token'] as String,
      expiresAt: DateTime.parse(data['expires_at'] as String),
      companyId: data['company_id'] as String,
      actorId: data['actor_id'] as String,
      identityScope: data['identity_scope'] as String,
    );
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() { _busy = true; _receipt = null; _uploadProgress = null; });
    try {
      await action();
    } on SdkError catch (error) {
      setState(() => _message = 'API ${error.statusCode}: ${error.reasonCode ?? error.code ?? error.message}');
    } catch (error) {
      setState(() => _message = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _connect() => _run(() async {
        _client?.dispose();
        final client = ProovitCaptureClient(baseUrl: Uri.parse(_api.text.trim()), sessionProvider: _issueSession);
        final session = await _issueSession(forceRefresh: true);
        final capability = await client.getCapabilities();
        if (capability.companyId != session.companyId) throw const FormatException('La société des capabilities ne correspond pas à la session.');
        setState(() {
          _client = client;
          _session = session;
          _capability = capability;
          _draft = null;
          _receipt = null;
          _message = 'Session partenaire active · company ${session.companyId}';
        });
      });

  Future<void> _capture() => _run(() async {
        final client = _client ?? (throw StateError('Connectez d’abord une session partenaire.'));
        final capability = await client.getCapabilities();
        final nativeCapture = capability.values['capture.native_signed'];
        if (nativeCapture is! Map || nativeCapture['enabled'] != true || nativeCapture['available_now'] == false) {
          throw StateError('La capability capture.native_signed n’est pas disponible pour cette société.');
        }
        await client.enrollDeviceIfNeeded();
        final draft = await client.capturePhoto();
        setState(() { _draft = draft; _message = 'Photo signée localement · ${draft.captureId}'; });
        final receipt = await client.submitCapture(draft, onProgress: (sent, total) {
          if (mounted) setState(() => _uploadProgress = total == 0 ? null : sent / total);
        });
        setState(() {
          _receipt = receipt;
          _message = receipt.status == 'verified' || receipt.status == 'verified_degraded'
              ? 'Reçu serveur vérifié par comparaison des champs.'
              : 'Le reçu serveur requiert une vérification métier.';
        });
      });

  void _logout() {
    _client?.dispose();
    setState(() {
      _client = null;
      _session = null;
      _capability = null;
      _draft = null;
      _receipt = null;
      _hostBearer.clear();
      _message = 'Session locale effacée. Le backend partenaire doit également révoquer son association.';
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('PROOV-IT · Capture SDK')),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          const Text('Exemple autonome Android beta', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          const Text('Le bearer ci-dessous appartient à votre authentification hôte. Le secret permanent PROOV-IT reste sur votre serveur.'),
          const SizedBox(height: 20),
          TextField(controller: _api, decoration: const InputDecoration(labelText: 'API PROOV-IT base URL')),
          TextField(controller: _partnerEndpoint, decoration: const InputDecoration(labelText: 'Endpoint de session de votre backend')),
          TextField(controller: _hostBearer, obscureText: true, decoration: const InputDecoration(labelText: 'Bearer hôte')),
          const SizedBox(height: 12),
          Wrap(spacing: 12, runSpacing: 10, children: [
            FilledButton(onPressed: _busy ? null : _connect, child: const Text('Connexion / capabilities')),
            FilledButton(onPressed: _busy || _client == null ? null : _capture, child: const Text('Capture CameraX → reçu')),
            OutlinedButton(onPressed: _busy ? null : _logout, child: const Text('Déconnexion')),
          ]),
          if (_uploadProgress != null) ...[
            const SizedBox(height: 16),
            LinearProgressIndicator(value: _uploadProgress),
          ],
          const SizedBox(height: 20),
          SelectableText(_message),
          if (_capability != null) SelectableText('Capabilities: ${_capability!.values.keys.join(', ')}'),
          if (_draft != null) SelectableText('Capture: ${_draft!.captureId}\nSHA-256 local: ${_draft!.sha256}\nTaille: ${_draft!.sizeBytes} octets'),
          if (_receipt != null) SelectableText('Reçu API: ${_receipt!.captureId}\nStatut: ${_receipt!.status}\nSHA-256 serveur: ${_receipt!.sha256}\nTaille: ${_receipt!.sizeBytes} octets\nMédia retenu: ${_receipt!.mediaRetained}'),
          if (_session != null) const Text('La session est gardée en mémoire; aucun bearer n’est persisté par cet exemple.'),
        ]),
      );
}
